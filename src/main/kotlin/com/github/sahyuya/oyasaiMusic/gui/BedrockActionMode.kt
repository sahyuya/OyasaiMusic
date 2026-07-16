package com.github.sahyuya.oyasaiMusic.gui

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * UI/UX設計書 1章・5章:
 * 統合版(Bedrock)プレイヤーはShift/右クリック等の複雑な操作ができないため、
 * 左列⑤(水色ブロック)でこの「アクションモード」を切り替えてから
 * 通常の左クリック(タップ)だけで操作する。
 *
 * 各値は UI/UX設計書 5章の表の列（①左クリック ②Shift+左クリック ③右クリック ④Shift+右クリック）
 * に対応する。
 */
enum class ActionMode(val displayIndex: Int) {
    PRIMARY(1),
    SECONDARY(2),
    TERTIARY(3),
    QUATERNARY(4);

    fun next(): ActionMode = entries[(ordinal + 1) % entries.size]
}

/**
 * プレイヤーごとの現在のアクションモードを保持する（インメモリ、再起動で初期化）。
 * Java版プレイヤーはこの状態を使わず、通常のクリック種別をそのまま使う
 * （[com.github.sahyuya.oyasaiMusic.util.BedrockUtil.isBedrock] で判定）。
 */
object BedrockActionMode {
    private val modes = ConcurrentHashMap<UUID, ActionMode>()

    fun get(playerUuid: UUID): ActionMode = modes.getOrDefault(playerUuid, ActionMode.PRIMARY)

    fun cycle(playerUuid: UUID): ActionMode {
        val next = get(playerUuid).next()
        modes[playerUuid] = next
        return next
    }

    fun reset(playerUuid: UUID) {
        modes.remove(playerUuid)
    }
}