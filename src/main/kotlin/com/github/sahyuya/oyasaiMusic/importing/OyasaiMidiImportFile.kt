package com.github.sahyuya.oyasaiMusic.importing

import com.google.gson.JsonParser
import com.github.sahyuya.oyasaiMusic.audio.InstrumentMapper
import com.github.sahyuya.oyasaiMusic.model.NoteEvent
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.File
import org.bukkit.Instrument

/** OMMTが生成する、バージョンに依存しない楽器IDを持つ`.oyasai`ファイルを読み込む。 */
object OyasaiMidiImportFile {
  private const val MAGIC = 0x4F594D49 // OYMI
  private const val VERSION = 1
  private const val HEADER_SIZE = 20L
  private const val NOTE_SIZE = 8L
  private const val MAX_METADATA_BYTES = 16 * 1024 * 1024
  private const val MAX_EXISTING_OYMB_NOTES = 1_000_000L

  data class ImportedSong(
      val title: String,
      val bpm: Int,
      val durationMs: Long,
      val notes: List<NoteEvent>,
  )

  fun read(file: File): ImportedSong {
    require(file.isFile) { "インポートファイルが見つかりません: ${file.name}" }
    require(file.length() >= HEADER_SIZE) { "インポートファイルのヘッダーが不足しています。" }

    DataInputStream(BufferedInputStream(file.inputStream())).use { input ->
      require(input.readInt() == MAGIC) { "OMMTの.oyasaiファイルではありません。" }
      val version = input.readUnsignedShort()
      require(version == VERSION) { "未対応の.oyasaiバージョンです: $version" }
      input.readUnsignedShort() // 予約領域
      val metadataLength = input.readInt().toLong() and 0xFFFF_FFFFL
      val noteCount = input.readInt().toLong() and 0xFFFF_FFFFL
      val durationMs = input.readInt().toLong() and 0xFFFF_FFFFL

      require(metadataLength in 2..MAX_METADATA_BYTES.toLong()) { "メタデータ長が不正です。" }
      require(noteCount <= Int.MAX_VALUE.toLong()) { "このサーバーで扱えるノート数を超えています。" }
      require(noteCount <= MAX_EXISTING_OYMB_NOTES) {
        "現在のOyasaiMusic音源で読み込めるノート数（$MAX_EXISTING_OYMB_NOTES）を超えています。"
      }
      val expectedLength = HEADER_SIZE + metadataLength + noteCount * NOTE_SIZE
      require(file.length() == expectedLength) { "ファイル長とヘッダー情報が一致しません。" }

      val metadataBytes = input.readNBytes(metadataLength.toInt())
      require(metadataBytes.size == metadataLength.toInt()) { "メタデータが途中で切れています。" }
      val metadata = metadataBytes.toString(Charsets.UTF_8)
      val metadataRoot =
          try {
            JsonParser.parseString(metadata).asJsonObject
          } catch (error: Exception) {
            throw IllegalArgumentException("メタデータJSONが不正です。", error)
          }
      require(metadataRoot.get("format")?.asString == "oyasai-midi-import") {
        "インポート形式の識別情報がありません。"
      }
      require(metadataRoot.get("version")?.asInt == VERSION) {
        "メタデータのバージョンがヘッダーと一致しません。"
      }
      val songMetadata = metadataRoot.getAsJsonObject("song")
      val title =
          runCatching { songMetadata?.get("title")?.asString }
              .getOrNull()
              ?.trim()
              ?.take(120)
              .orEmpty()
              .ifBlank { "無題の楽曲" }
      val bpm =
          runCatching { songMetadata?.get("displayBpm")?.asInt }
              .getOrNull()
              ?.coerceIn(1, 999)
              ?: 120

      val notes = ArrayList<NoteEvent>(noteCount.toInt())
      repeat(noteCount.toInt()) {
        val timeMs = input.readInt().toLong() and 0xFFFF_FFFFL
        val stableInstrumentId = input.readUnsignedByte()
        val pitch = input.readUnsignedByte()
        val volume = input.readUnsignedByte()
        val pan = input.readByte().toInt()
        require(timeMs <= Int.MAX_VALUE.toLong()) { "発音時刻がOyasaiMusicの上限を超えています。" }
        require(timeMs <= durationMs) { "総再生時間を超えるノートがあります。" }
        require(pitch in 0..24) { "音階が0〜24の範囲外です。" }
        require(volume in 0..100) { "音量が0〜100の範囲外です。" }
        require(pan in -100..100) { "Panが-100〜100の範囲外です。" }
        val instrument = stableInstrument(stableInstrumentId)
        notes +=
            NoteEvent(
                timeMs = timeMs.toInt(),
                instrument = InstrumentMapper.toId(instrument),
                pitch = pitch.toByte(),
                volume = volume,
                pan = pan,
            )
      }
      require(input.read() == -1) { "ファイル末尾に余分なデータがあります。" }
      return ImportedSong(title, bpm, durationMs, notes)
    }
  }

  private fun stableInstrument(id: Int): Instrument =
      when (id) {
        0 -> Instrument.PIANO
        1 -> Instrument.BASS_GUITAR
        2 -> Instrument.BASS_DRUM
        3 -> Instrument.SNARE_DRUM
        4 -> Instrument.STICKS
        5 -> Instrument.FLUTE
        6 -> Instrument.BELL
        7 -> Instrument.GUITAR
        8 -> Instrument.CHIME
        9 -> Instrument.XYLOPHONE
        10 -> Instrument.IRON_XYLOPHONE
        11 -> Instrument.COW_BELL
        12 -> Instrument.DIDGERIDOO
        13 -> Instrument.BIT
        14 -> Instrument.BANJO
        15 -> Instrument.PLING
        else -> throw IllegalArgumentException("未対応の安定楽器IDです: $id")
      }

}
