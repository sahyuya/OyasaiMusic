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
 * OMMTが生成した`.oyasai`を、Paperダイアログへ貼り付けるBase64URL文字列として受け取る。
 *
 * 通常は1回で完了する。Minecraftのダイアログ返信NBTには約32KBの上限があるため、大きな曲だけ
 * 約23KBずつ継続して受け取る。入力はコードとして実行せず、サイズ、文字種、順序、SHA-256、
 * gzip展開後サイズを検証してから既存の厳格な`.oyasai`パーサーへ渡す。
 */
class OyasaiPasteTransferService {
  companion object {
    const val MAX_DIALOG_INPUT_CHARACTERS = 24_000
    private const val MAX_SEGMENT_PAYLOAD_CHARACTERS = 23_500
    private const val MAX_ENCODED_CHARACTERS = 32 * 1024 * 1024
    private const val MAX_TOTAL_ENCODED_CHARACTERS = 64 * 1024 * 1024
    private const val MAX_COMPRESSED_BYTES = 24 * 1024 * 1024
    private const val MAX_DECOMPRESSED_BYTES = 32 * 1024 * 1024
    private const val MAX_SEGMENTS = 1_500
    private const val MAX_ACTIVE_SESSIONS = 32
    private val SESSION_TIMEOUT = Duration.ofMinutes(10).toMillis()
    private val BASE64_URL_PAYLOAD = Regex("^[A-Za-z0-9_-]+$")
    private val SHA_256_HEX = Regex("^[0-9a-fA-F]{64}$")
    private val TRANSFER_ID = Regex("^[0-9a-fA-F]{32}$")
  }

  private data class Session(
      val transferId: String,
      val expectedSegments: Int,
      val checksum: String,
      val createdAtMs: Long,
      val segments: MutableList<String> = ArrayList(),
      var encodedCharacters: Int = 0,
  )

  data class SealedTransfer(
      val segments: List<String>,
      val checksum: String,
  )

  sealed interface ReceiveResult {
    data class Pending(val received: Int, val expected: Int) : ReceiveResult

    data class Complete(val transfer: SealedTransfer) : ReceiveResult
  }

  private val sessions = ConcurrentHashMap<UUID, Session>()

  /** `OMMT1:<転送ID>:<番号>:<総数>:<SHA-256>:<Base64URL>` を1回分受け取る。 */
  @Synchronized
  fun receive(playerId: UUID, rawText: String): ReceiveResult {
    expireOldSessions()
    val text = rawText.trim()
    require(text.length in 1..MAX_DIALOG_INPUT_CHARACTERS) {
      "1回分のデータが空か、Minecraftで安全に送信できる長さを超えています。"
    }
    val fields = text.split(':', limit = 6)
    require(fields.size == 6 && fields[0] == "OMMT1") { "OMMT1形式のデータではありません。" }
    val transferId = fields[1].lowercase()
    val index = fields[2].toIntOrNull() ?: throw IllegalArgumentException("送信番号が不正です。")
    val expectedSegments = fields[3].toIntOrNull() ?: throw IllegalArgumentException("送信回数が不正です。")
    val checksum = fields[4].lowercase()
    val payload = fields[5]
    require(TRANSFER_ID.matches(transferId)) { "転送IDが不正です。" }
    require(expectedSegments in 1..MAX_SEGMENTS && index in 1..expectedSegments) {
      "送信番号または送信回数が範囲外です。"
    }
    require(SHA_256_HEX.matches(checksum)) { "SHA-256チェックサムが不正です。" }
    require(payload.length in 1..MAX_SEGMENT_PAYLOAD_CHARACTERS && BASE64_URL_PAYLOAD.matches(payload)) {
      "データに使用できない文字があるか、1回分が長すぎます。"
    }

    val previous = sessions[playerId]
    val session =
        if (previous == null || previous.transferId != transferId) {
          require(index == 1) { "新しい転送は1回目のデータから貼り付けてください。" }
          require(previous != null || sessions.size < MAX_ACTIVE_SESSIONS) {
            "同時に処理できる転送数の上限に達しています。しばらくしてからやり直してください。"
          }
          Session(
                  transferId = transferId,
                  expectedSegments = expectedSegments,
                  checksum = checksum,
                  createdAtMs = System.currentTimeMillis(),
              )
              .also { sessions[playerId] = it }
        } else {
          previous
        }

    require(session.expectedSegments == expectedSegments && session.checksum == checksum) {
      "同じ転送内で送信回数またはチェックサムが変わっています。最初からやり直してください。"
    }
    require(index == session.segments.size + 1) {
      "貼り付け順が違います。次は ${session.segments.size + 1}/${session.expectedSegments} です。"
    }
    require(session.encodedCharacters + payload.length <= MAX_ENCODED_CHARACTERS) {
      "コピペデータがサーバーの上限を超えています。"
    }
    require(sessions.values.sumOf { it.encodedCharacters.toLong() } + payload.length <= MAX_TOTAL_ENCODED_CHARACTERS) {
      "サーバー全体のコピペ受信上限に達しています。しばらくしてからやり直してください。"
    }
    session.segments += payload
    session.encodedCharacters += payload.length
    if (session.segments.size < session.expectedSegments) {
      return ReceiveResult.Pending(session.segments.size, session.expectedSegments)
    }
    require(sessions.remove(playerId, session)) { "転送状態が更新されました。最初からやり直してください。" }
    return ReceiveResult.Complete(SealedTransfer(session.segments.toList(), session.checksum))
  }

  @Synchronized
  fun cancel(playerId: UUID): Boolean = sessions.remove(playerId) != null

  fun decode(transfer: SealedTransfer): ByteArray {
    val encodedLength = transfer.segments.sumOf { it.length }
    require(encodedLength <= MAX_ENCODED_CHARACTERS) { "コピペデータがサーバーの上限を超えています。" }
    val encoded = buildString(encodedLength) { transfer.segments.forEach(::append) }
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
