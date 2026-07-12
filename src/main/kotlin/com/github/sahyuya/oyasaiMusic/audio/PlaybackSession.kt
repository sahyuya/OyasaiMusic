package com.github.sahyuya.oyasaiMusic.audio

import com.github.sahyuya.oyasaiMusic.model.Song
import org.bukkit.entity.Player
import java.util.UUID
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 1回の再生（個人プレイヤー再生 or 環境BGM等の複数人再生）を表すセッション。
 * [PlaybackEngine.stop] で全スケジュール済みタスクをキャンセルできるよう、
 * スケジュールしたFutureを保持する。
 */
class PlaybackSession(
    val sessionId: UUID = UUID.randomUUID(),
    val song: Song,
    initialRecipients: Collection<Player>,
    val startTimeMillis: Long = System.currentTimeMillis(),
    /** ジュークボックス(環境音)再生かどうか。視聴回数カウント対象外の判定に使う（設計書7章）。 */
    val isAmbientPlayback: Boolean = false,
) {
    val recipients: MutableSet<UUID> = CopyOnWriteArraySet(initialRecipients.map { it.uniqueId })
    internal val scheduledTasks: MutableList<ScheduledFuture<*>> = mutableListOf()
    private val cancelled = AtomicBoolean(false)

    val isCancelled: Boolean get() = cancelled.get()

    fun cancel() {
        if (cancelled.compareAndSet(false, true)) {
            scheduledTasks.forEach { it.cancel(false) }
            scheduledTasks.clear()
        }
    }

    fun removeRecipient(playerUuid: UUID) {
        recipients.remove(playerUuid)
    }
}
