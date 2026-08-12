package com.github.sahyuya.oyasaiMusic.importing

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.time.Duration
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.GZIPInputStream

/**
 * OMMTが生成した`.oyasai`を、チャットへ貼り付けられる短いBase64URL断片として受け取る。
 *
 * 入力はコードとして実行せず、サイズ、文字種、順序、SHA-256、gzip展開後サイズを検証してから
 * 既存の厳格な`.oyasai`パーサーへ渡す。
 */
class OyasaiPasteTransferService {
  companion object {
    private const val MAX_CHUNK_LENGTH = 180
    private const val MAX_ENCODED_CHARACTERS = 32 * 1024 * 1024
    private const val MAX_TOTAL_ENCODED_CHARACTERS = 64 * 1024 * 1024
    private const val MAX_COMPRESSED_BYTES = 24 * 1024 * 1024
    private const val MAX_DECOMPRESSED_BYTES = 32 * 1024 * 1024
    private const val MAX_CHUNKS = 220_000
    private const val MAX_ACTIVE_SESSIONS = 32
    private val SESSION_TIMEOUT = Duration.ofMinutes(10).toMillis()
    private val BASE64_URL_CHUNK = Regex("^[A-Za-z0-9_-]+$")
    private val SHA_256_HEX = Regex("^[0-9a-fA-F]{64}$")
  }

  private data class Session(
      val expectedChunks: Int,
      val checksum: String,
      val createdAtMs: Long,
      val chunks: MutableList<String> = ArrayList(),
      var encodedCharacters: Int = 0,
  )

  data class SealedTransfer(
      val chunks: List<String>,
      val checksum: String,
  )

  private val sessions = ConcurrentHashMap<UUID, Session>()

  fun begin(playerId: UUID, expectedChunks: Int, checksum: String) {
    expireOldSessions()
    require(expectedChunks in 1..MAX_CHUNKS) { "分割数が範囲外です。" }
    require(SHA_256_HEX.matches(checksum)) { "SHA-256チェックサムが不正です。" }
    require(sessions.containsKey(playerId) || sessions.size < MAX_ACTIVE_SESSIONS) {
      "同時に処理できる転送数の上限に達しています。しばらくしてからやり直してください。"
    }
    sessions[playerId] =
        Session(
            expectedChunks = expectedChunks,
            checksum = checksum.lowercase(),
            createdAtMs = System.currentTimeMillis(),
        )
  }

  fun add(playerId: UUID, index: Int, chunk: String): Pair<Int, Int> {
    val session = activeSession(playerId)
    require(index == session.chunks.size) {
      "分割番号が順番どおりではありません。次は ${session.chunks.size} です。"
    }
    require(index < session.expectedChunks) { "宣言された分割数を超えています。" }
    require(chunk.length in 1..MAX_CHUNK_LENGTH && BASE64_URL_CHUNK.matches(chunk)) {
      "データ断片に使用できない文字があるか、1行が長すぎます。"
    }
    require(session.encodedCharacters + chunk.length <= MAX_ENCODED_CHARACTERS) {
      "コピペデータがサーバーの上限を超えています。"
    }
    require(sessions.values.sumOf { it.encodedCharacters.toLong() } + chunk.length <= MAX_TOTAL_ENCODED_CHARACTERS) {
      "サーバー全体のコピペ受信上限に達しています。しばらくしてからやり直してください。"
    }
    session.chunks += chunk
    session.encodedCharacters += chunk.length
    return session.chunks.size to session.expectedChunks
  }

  fun seal(playerId: UUID): SealedTransfer {
    val session = activeSession(playerId)
    require(session.chunks.size == session.expectedChunks) {
      "データが不足しています（${session.chunks.size}/${session.expectedChunks}）。"
    }
    require(sessions.remove(playerId, session)) { "転送状態が更新されました。最初からやり直してください。" }
    return SealedTransfer(session.chunks.toList(), session.checksum)
  }

  fun cancel(playerId: UUID): Boolean = sessions.remove(playerId) != null

  fun decode(transfer: SealedTransfer): ByteArray {
    val encodedLength = transfer.chunks.sumOf { it.length }
    require(encodedLength <= MAX_ENCODED_CHARACTERS) { "コピペデータがサーバーの上限を超えています。" }
    val encoded = buildString(encodedLength) { transfer.chunks.forEach(::append) }
    val compressed =
        try {
          Base64.getUrlDecoder().decode(encoded)
        } catch (error: IllegalArgumentException) {
          throw IllegalArgumentException("Base64URLデータが破損しています。", error)
        }
    require(compressed.size <= MAX_COMPRESSED_BYTES) { "圧縮データがサーバーの上限を超えています。" }
    val expectedChecksum = transfer.checksum.hexToByteArray()
    val actualChecksum = MessageDigest.getInstance("SHA-256").digest(compressed)
    require(MessageDigest.isEqual(expectedChecksum, actualChecksum)) {
      "チェックサムが一致しません。データの欠落または改変を検出しました。"
    }
    return gunzipBounded(compressed)
  }

  private fun activeSession(playerId: UUID): Session {
    val session = sessions[playerId] ?: throw IllegalArgumentException("転送が開始されていません。")
    if (System.currentTimeMillis() - session.createdAtMs > SESSION_TIMEOUT) {
      sessions.remove(playerId, session)
      throw IllegalArgumentException("転送が10分で期限切れになりました。最初からやり直してください。")
    }
    return session
  }

  private fun expireOldSessions() {
    val cutoff = System.currentTimeMillis() - SESSION_TIMEOUT
    sessions.entries.removeIf { it.value.createdAtMs < cutoff }
  }

  private fun gunzipBounded(compressed: ByteArray): ByteArray {
    val output = ByteArrayOutputStream(minOf(MAX_DECOMPRESSED_BYTES, compressed.size * 2))
    try {
      GZIPInputStream(ByteArrayInputStream(compressed)).use { input ->
        val buffer = ByteArray(8192)
        while (true) {
          val count = input.read(buffer)
          if (count < 0) break
          require(output.size() + count <= MAX_DECOMPRESSED_BYTES) {
            "展開後データがサーバーの上限を超えています。"
          }
          output.write(buffer, 0, count)
        }
      }
    } catch (error: IllegalArgumentException) {
      throw error
    } catch (error: Exception) {
      throw IllegalArgumentException("gzipデータが破損しています。", error)
    }
    return output.toByteArray()
  }

  private fun String.hexToByteArray(): ByteArray =
      ByteArray(length / 2) { index -> substring(index * 2, index * 2 + 2).toInt(16).toByte() }
}
