package com.github.sahyuya.oyasaiMusic.audio

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * プレイヤーごとの動的録音セッション([DynamicRecordingSession])を管理する。
 * `/record start` で開始、`/record stop` で終了して下書き保存へ渡す。
 */
class RecordingSessionManager {

    private val sessions = ConcurrentHashMap<UUID, DynamicRecordingSession>()

    fun isRecording(playerUuid: UUID): Boolean = sessions.containsKey(playerUuid)

    fun start(playerUuid: UUID, quantizeStepMs: Long): DynamicRecordingSession {
        require(quantizeStepMs > 0) { "量子化間隔は正の値である必要があります: $quantizeStepMs" }
        val session = DynamicRecordingSession(
            playerUuid = playerUuid,
            startTimeNanos = System.nanoTime(),
            quantizeStepMs = quantizeStepMs,
        )
        sessions[playerUuid] = session
        return session
    }

    fun get(playerUuid: UUID): DynamicRecordingSession? = sessions[playerUuid]

    /** セッションを終了し、その状態を返す（呼び出し側が保存処理を行う）。 */
    fun stop(playerUuid: UUID): DynamicRecordingSession? = sessions.remove(playerUuid)

    fun hasAnySession(): Boolean = sessions.isNotEmpty()

    fun activeSessions(): Collection<DynamicRecordingSession> = sessions.values
}
