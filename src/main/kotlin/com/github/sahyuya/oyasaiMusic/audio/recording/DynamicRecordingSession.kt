package com.github.sahyuya.oyasaiMusic.audio

import com.github.sahyuya.oyasaiMusic.model.NoteEvent
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 動的録音（データ・システム設計書 3章 `/record start <1〜4>`）の実行中セッション。
 *
 * `NotePlayEvent` をフックして録音するため、コマンド実行(start)〜終了(stop)の間、
 * プレイヤーごとに1つ保持される状態オブジェクト。
 *
 * @param quantizeStepMs 量子化グリッドの間隔。0.5 tick指定時は25msに対応する。
 */
class DynamicRecordingSession(
    val playerUuid: UUID,
    val startTimeNanos: Long,
    val quantizeStepMs: Long,
) {
    val notes: MutableList<NoteEvent> = CopyOnWriteArrayList()

    /** 引数の量子化単位から、便宜上の基準BPMを逆算する（60000 / stepMs）。楽曲のbpmカラムに使用。 */
    fun impliedBpm(): Int = (60000.0 / quantizeStepMs).toInt().coerceAtLeast(1)
}
