package com.github.sahyuya.oyasaiMusic.audio

import com.github.sahyuya.oyasaiMusic.model.NoteEvent
import com.sk89q.worldedit.bukkit.BukkitAdapter
import com.sk89q.worldedit.extent.clipboard.Clipboard
import com.sk89q.worldedit.math.BlockVector3
import org.bukkit.Material
import org.bukkit.block.data.BlockData
import org.bukkit.block.data.Powerable
import org.bukkit.block.data.type.NoteBlock as BukkitNoteBlock
import org.bukkit.block.data.type.Repeater
import java.util.ArrayDeque

/**
 * 回路型録音（データ・システム設計書 3章 `/record we default`）。
 *
 * FAWEクリップボード内のレッドストーン回路をBFS（幅優先探索）で辿り、
 * リピーターの遅延を累積加算しながら各ノートブロックの発音タイミングを逆算する。
 *
 * ## 簡易モデルであることの注意
 * 本物のレッドストーン挙動を完全再現するには膨大な実装が必要なため、
 * 本実装は以下のように単純化している（今後の精緻化が必要な場合は拡張してほしい）:
 *  - 起点は「常時電力を発する（と見なせる）ブロック」を自動探索する:
 *    レッドストーンブロック / 点灯中と仮定したレッドストーントーチ / ONのレバー
 *  - レッドストーンダストの伝播は 遅延0ms、同一Y・1段上り・1段下りの4方位のみを考慮する
 *    （実際のブロック形状に基づく接続可否までは判定しない）
 *  - リピーターは向いている方向にのみ伝播し、delay(1〜4) × 100ms を加算する
 *  - コンパレーターは今回は非対応（信号は通過しない）
 *  - 各座標はBFSで1度のみ処理する（強電力/弱電力の区別、同一ブロックへの複数経路到達による
 *    再トリガーは考慮しない）
 *  - ノートブロックは信号が届いた時点のクリップボード内の音階・楽器をそのまま採用する
 */
object CircuitRecorder {

    private data class Node(val pos: BlockVector3, val timeMs: Int)

    // 水平4方位 + 1段上り + 1段下りの、ダストが伝播しうる相対オフセット
    private val DUST_NEIGHBOR_OFFSETS = listOf(
        Triple(1, 0, 0), Triple(-1, 0, 0), Triple(0, 0, 1), Triple(0, 0, -1),
        Triple(1, 1, 0), Triple(-1, 1, 0), Triple(0, 1, 1), Triple(0, 1, -1),
        Triple(1, -1, 0), Triple(-1, -1, 0), Triple(0, -1, 1), Triple(0, -1, -1),
    )

    // ノートブロックへの通電判定に使う6方向
    private val ADJACENT_6 = listOf(
        Triple(1, 0, 0), Triple(-1, 0, 0), Triple(0, 1, 0), Triple(0, -1, 0), Triple(0, 0, 1), Triple(0, 0, -1),
    )

    fun record(clipboard: Clipboard): List<NoteEvent> {
        val region = clipboard.region
        val min = region.minimumPoint
        val max = region.maximumPoint

        val visited = HashSet<BlockVector3>()
        val queue = ArrayDeque<Node>()
        val notes = mutableListOf<NoteEvent>()

        // --- 1. 起点(電力源)をすべて探索してBFSキューへ投入 ---
        for (x in min.x()..max.x()) {
            for (y in min.y()..max.y()) {
                for (z in min.z()..max.z()) {
                    val pos = BlockVector3.at(x, y, z)
                    val data = blockDataAt(clipboard, pos) ?: continue
                    if (isPowerSource(data)) {
                        if (visited.add(pos)) {
                            queue.add(Node(pos, 0))
                        }
                    }
                }
            }
        }

        if (queue.isEmpty()) {
            return emptyList()
        }

        // --- 2. BFS ---
        while (queue.isNotEmpty()) {
            val (pos, timeMs) = queue.poll()

            // 2-1. 隣接するノートブロックへ通電 → 発音として記録
            for ((dx, dy, dz) in ADJACENT_6) {
                val neighborPos = pos.add(dx, dy, dz)
                if (!region.contains(neighborPos)) continue
                val neighborData = blockDataAt(clipboard, neighborPos) ?: continue
                if (neighborData is BukkitNoteBlock) {
                    notes += NoteEvent(
                        timeMs = timeMs,
                        instrument = InstrumentMapper.toId(neighborData.instrument),
                        pitch = neighborData.note.id,
                        volume = 100,
                        pan = 0,
                    )
                }
            }

            // 2-2. ダストへの伝播（遅延0ms）
            for ((dx, dy, dz) in DUST_NEIGHBOR_OFFSETS) {
                val neighborPos = pos.add(dx, dy, dz)
                if (!region.contains(neighborPos)) continue
                if (!visited.add(neighborPos)) continue
                val neighborData = blockDataAt(clipboard, neighborPos) ?: continue
                if (neighborData.material == Material.REDSTONE_WIRE) {
                    queue.add(Node(neighborPos, timeMs))
                }
            }

            // 2-3. リピーターへの伝播（delay×100msを加算し、facing方向の先へ出力）
            for ((dx, dy, dz) in listOf(Triple(1, 0, 0), Triple(-1, 0, 0), Triple(0, 0, 1), Triple(0, 0, -1))) {
                val repeaterPos = pos.add(dx, dy, dz)
                if (!region.contains(repeaterPos)) continue
                val repeaterData = blockDataAt(clipboard, repeaterPos) ?: continue
                if (repeaterData !is Repeater) continue
                // リピーターの入力側(背面)が現在位置を向いている場合のみ伝播させる
                val inputSide = repeaterData.facing.oppositeFace
                val relativeFromRepeaterToPos = directionBetween(repeaterPos, pos)
                if (relativeFromRepeaterToPos != inputSide) continue

                val outputPos = repeaterPos.add(
                    repeaterData.facing.modX, repeaterData.facing.modY, repeaterData.facing.modZ
                )
                if (!region.contains(outputPos)) continue
                val delayMs = repeaterData.delay * 100
                val newTime = timeMs + delayMs
                if (visited.add(outputPos)) {
                    queue.add(Node(outputPos, newTime))
                }
            }
        }

        return notes
    }

    private fun blockDataAt(clipboard: Clipboard, pos: BlockVector3): BlockData? = try {
        BukkitAdapter.adapt(clipboard.getFullBlock(pos))
    } catch (_: Exception) {
        null
    }

    private fun isPowerSource(data: BlockData): Boolean = when {
        data.material == Material.REDSTONE_BLOCK -> true
        data.material == Material.REDSTONE_TORCH -> true
        data.material == Material.REDSTONE_WALL_TORCH -> true
        data.material == Material.LEVER && data is Powerable && data.isPowered -> true
        else -> false
    }

    private fun directionBetween(from: BlockVector3, to: BlockVector3): org.bukkit.block.BlockFace {
        val dx = (to.x() - from.x()).coerceIn(-1, 1)
        val dy = (to.y() - from.y()).coerceIn(-1, 1)
        val dz = (to.z() - from.z()).coerceIn(-1, 1)
        return org.bukkit.block.BlockFace.values().firstOrNull {
            it.modX == dx && it.modY == dy && it.modZ == dz
        } ?: org.bukkit.block.BlockFace.SELF
    }
}
