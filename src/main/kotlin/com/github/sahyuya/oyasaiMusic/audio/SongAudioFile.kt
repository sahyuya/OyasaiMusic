package com.github.sahyuya.oyasaiMusic.audio

import com.github.sahyuya.oyasaiMusic.model.NoteEvent
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.EOFException

/**
 * 音源データ設計（データ・システム設計書 2章）に基づく独自バイナリ(.bin)フォーマットの
 * 読み書きを行うクラス。テキスト解析のオーバーヘッドを避けるため、1音符=8バイト固定長。
 *
 * ヘッダー(16バイト固定):
 *   0-3   : マジックナンバー "OYMB" (0x4F 0x59 0x4D 0x42)
 *   4-5   : フォーマットバージョン (Short)
 *   6-7   : 予約領域 (将来拡張用, 現状は0)
 *   8-11  : 総音符数 (Int)
 *   12-15 : 総再生時間ミリ秒 (Int)
 *
 * ペイロード(8バイト/音符 の連続配列):
 *   0-3 : Time   (Int,  発音ミリ秒)
 *   4   : Inst   (Byte, 楽器ID 0〜255 ※符号なしとして解釈)
 *   5   : Pitch  (Byte, 音階 0〜24)
 *   6   : Volume (Byte, 音量 0〜100)
 *   7   : Pan    (Byte, 定位 -100〜100, 符号あり)
 *
 * 全ての多バイト整数はビッグエンディアンで記録する（DataOutputStream標準）。
 */
object SongAudioFile {

    private const val MAGIC = 0x4F594D42 // "OYMB"
    const val HEADER_SIZE = 16
    const val NOTE_SIZE = 8
    const val CURRENT_VERSION: Short = 1

    data class SongAudio(
        val version: Int,
        val totalDurationMs: Int,
        val notes: List<NoteEvent>,
    )

    /**
     * 音符リストを .bin ファイルへ書き出す。
     * 再生時のスケジューリングを単純化するため、書き出し前に時刻昇順へソートする。
     */
    fun write(file: File, notes: List<NoteEvent>) {
        file.parentFile?.mkdirs()
        val sorted = notes.sortedBy { it.timeMs }
        val totalDuration = sorted.maxOfOrNull { it.timeMs } ?: 0

        DataOutputStream(BufferedOutputStream(file.outputStream())).use { out ->
            out.writeInt(MAGIC)
            out.writeShort(CURRENT_VERSION.toInt())
            out.writeShort(0) // 予約領域
            out.writeInt(sorted.size)
            out.writeInt(totalDuration)

            for (note in sorted) {
                out.writeInt(note.timeMs)
                out.writeByte(note.instrument)
                out.writeByte(note.pitch.toInt())
                out.writeByte(note.volume)
                out.writeByte(note.pan) // Byteの範囲(-128..127)にそのまま収まる(-100..100)
            }
        }
    }

    /** .bin ファイルを読み込み、ヘッダーと音符リストを返す。 */
    fun read(file: File): SongAudio {
        DataInputStream(BufferedInputStream(file.inputStream())).use { input ->
            val magic = input.readInt()
            require(magic == MAGIC) { "不正な音源ファイルです（マジックナンバー不一致）: ${file.name}" }
            val version = input.readUnsignedShort()
            input.readUnsignedShort() // 予約領域を読み飛ばす
            val totalNotes = input.readInt()
            val totalDuration = input.readInt()

            val notes = ArrayList<NoteEvent>(totalNotes)
            repeat(totalNotes) {
                val timeMs = input.readInt()
                val inst = input.readUnsignedByte()
                val pitch = input.readUnsignedByte()
                val volume = input.readUnsignedByte()
                val pan = input.readByte().toInt() // 符号あり読み込み(-128..127)
                notes += NoteEvent(
                    timeMs = timeMs,
                    instrument = inst,
                    pitch = pitch.coerceIn(0, 24).toByte(),
                    volume = volume.coerceIn(0, 100),
                    pan = pan.coerceIn(-100, 100),
                )
            }

            // 末尾に余分なデータが無いことを軽く検証（壊れたファイルの早期発見用）
            if (input.available() > 0) {
                try {
                    input.readByte()
                    // 読めてしまった場合はファイルが壊れている可能性が高いが、致命的ではないため警告のみに留める。
                } catch (_: EOFException) {
                    // 想定内
                }
            }

            return SongAudio(version = version, totalDurationMs = totalDuration, notes = notes)
        }
    }
}
