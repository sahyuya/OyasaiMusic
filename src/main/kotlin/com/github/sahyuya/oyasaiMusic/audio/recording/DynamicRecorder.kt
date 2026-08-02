package com.github.sahyuya.oyasaiMusic.audio

import com.github.sahyuya.oyasaiMusic.model.NoteEvent
import org.bukkit.Instrument
import org.bukkit.Location
import org.bukkit.block.Block
import kotlin.math.roundToLong

/**
 * 動的録音（データ・システム設計書 3章 `/record start <1〜4>`）。
 * `NotePlayEvent` をフックする都度、[process] を呼び出して1音符分の [NoteEvent] を得る。
 *
 * - 発音ミリ秒は [DynamicRecordingSession.quantizeStepMs] を基準にクオンタイズ（最も近いグリッドへ丸める）
 * - 録音者の現在位置を毎回参照し、同一ワールド・半径48ブロック以内だけを記録する
 * - 音量は常に100、Panは常に0とする
 * - 看板は3行目の発音ずらしと4行目のカスタム音源だけを適用する
 */
object DynamicRecorder {

    private const val RECORDING_RADIUS = 48.0

    /**
     */
    fun process(
        session: DynamicRecordingSession,
        block: Block,
        instrument: Instrument,
        pitch: Byte,
        recorderLocation: Location,
        eventTimeNanos: Long = System.nanoTime(),
    ): NoteEvent? {
        if (block.world != recorderLocation.world) return null
        val dx = block.x + 0.5 - recorderLocation.x
        val dy = block.y + 0.5 - recorderLocation.y
        val dz = block.z + 0.5 - recorderLocation.z
        if (dx * dx + dy * dy + dz * dz > RECORDING_RADIUS * RECORDING_RADIUS) return null

        // --- 発音時刻をグリッドへクオンタイズ ---
        val elapsedMs = (eventTimeNanos - session.startTimeNanos).coerceAtLeast(0L) / 1_000_000.0
        val steps = (elapsedMs / session.quantizeStepMs).roundToLong()
        val quantizedMs = (steps * session.quantizeStepMs).toInt().coerceAtLeast(0)
        val quarterNoteMs = 60_000.0 / session.impliedBpm()
        val signDelay = SignOverrideProcessor.extractDelayFromWorld(block, quarterNoteMs) ?: 0
        val customSound = SignOverrideProcessor.extractCustomSoundFromWorld(block)

        val note = NoteEvent(
            timeMs = (quantizedMs + signDelay).coerceAtLeast(0),
            instrument = InstrumentMapper.toId(instrument),
            pitch = pitch.coerceIn(0, 24),
            volume = 100,
            pan = 0,
            customSound = customSound?.eventKey,
            customSoundSeed = customSound?.seed,
        )
        session.notes.add(note)
        return note
    }
}
