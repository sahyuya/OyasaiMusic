package com.github.sahyuya.oyasaiMusic.audio

import com.github.sahyuya.oyasaiMusic.model.NoteEvent
import com.sk89q.worldedit.bukkit.BukkitAdapter
import com.sk89q.worldedit.extent.clipboard.Clipboard
import com.sk89q.worldedit.math.BlockVector3
import org.bukkit.Material
import org.bukkit.World
import org.bukkit.block.data.BlockData
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
 *  - 起点は「回路のスタート地点になり得るブロック」を自動探索する:
 *    レッドストーンブロック / レッドストーントーチ(立て・壁掛け) / レバー(ON/OFF問わず) / 各種ボタン。
 *    これはクリップボードを静的に解析しているだけで実際に通電させているわけではないため、
 *    レバーのON/OFF状態やボタンの押下状態は問わず「起点の目印」として扱っている。
 *  - 起点は直接隣接していないダスト（例:「レバーが取り付いた壁の上に乗ったダスト」等）も
 *    拾えるよう、起点周辺 3×3(水平)×2段(同じY・1つ上のY) の範囲まで広げて探索する
 *    （[SOURCE_REACH_OFFSETS]）。
 *  - **ノートブロックは通常の固体ブロックと同様に電力を伝える**: 「リピーター→ノートブロック→
 *    その真上に乗ったダスト→次のリピーター→…」という連鎖に対応するため、ノートブロックが
 *    発音した時点でそのノートブロック自身もBFSの伝播元として扱う。
 *  - リピーターは向いている方向にのみ伝播し、delay(1〜4) × 100ms を加算する
 *  - コンパレーターは今回は非対応（信号は通過しない）
 *  - 各座標はBFSで1度のみ処理する（強電力/弱電力の区別、同一ブロックへの複数経路到達による
 *    再トリガーは考慮しない）
 *  - ノートブロックは信号が届いた時点のクリップボード内の音階・楽器をそのまま採用する
 *  - 音量・Panは、ノートブロック真上の看板があれば[SignOverrideProcessor]で上書きする
 *    （ワールド上にまだ残っている元の看板を読み取る。詳細は[SignOverrideProcessor.extractFromWorldPos]）
 */
object CircuitRecorder {

    private data class Node(val pos: BlockVector3, val timeMs: Int)

    // 水平4方位 + 真上/真下 + 1段上り + 1段下りの、ダスト(または通電した固体ブロックの上のダスト)が
    // 伝播しうる相対オフセット。真上(0,1,0)は「ノートブロックや電源ブロックの真上に乗ったダスト」を
    // 拾うために必須。
    private val DUST_NEIGHBOR_OFFSETS = listOf(
        Triple(1, 0, 0), Triple(-1, 0, 0), Triple(0, 0, 1), Triple(0, 0, -1),
        Triple(0, 1, 0), Triple(0, -1, 0),
        Triple(1, 1, 0), Triple(-1, 1, 0), Triple(0, 1, 1), Triple(0, 1, -1),
        Triple(1, -1, 0), Triple(-1, -1, 0), Triple(0, -1, 1), Triple(0, -1, -1),
    )

    // ノートブロックへの通電判定に使う6方向
    private val ADJACENT_6 = listOf(
        Triple(1, 0, 0), Triple(-1, 0, 0), Triple(0, 1, 0), Triple(0, -1, 0), Triple(0, 0, 1), Triple(0, 0, -1),
    )

    // リピーター探索に使う水平4方位
    private val CARDINAL_4 = listOf(Triple(1, 0, 0), Triple(-1, 0, 0), Triple(0, 0, 1), Triple(0, 0, -1))

    // 起点(電力源)から「取り付け先ブロックの上に乗ったダスト」等を拾うための広域オフセット
    // (水平3x3 × 同じY/1つ上のY、自分自身を除く17マス)
    private val SOURCE_REACH_OFFSETS: List<Triple<Int, Int, Int>> = buildList {
        for (dy in 0..1) {
            for (dx in -1..1) {
                for (dz in -1..1) {
                    if (dx == 0 && dz == 0 && dy == 0) continue
                    add(Triple(dx, dy, dz))
                }
            }
        }
    }

    /**
     * @param clipboard 走査対象のFAWEクリップボード
     * @param world 看板の読み取りに使用するワールド（//copy した際の元の建築がまだ残っている前提）
     */
    fun record(clipboard: Clipboard, world: World): List<NoteEvent> {
        val region = clipboard.region
        val min = region.minimumPoint
        val max = region.maximumPoint

        val visited = HashSet<BlockVector3>()
        val queue = ArrayDeque<Node>()
        val notes = mutableListOf<NoteEvent>()
        // (ノートブロック位置, 発音ミリ秒) の組で発音を重複記録しないようにする
        val firedNoteKeys = HashSet<Pair<BlockVector3, Int>>()

        // --- 1. 起点(電力源)をすべて探索してBFSキューへ投入 ---
        for (x in min.x()..max.x()) {
            for (y in min.y()..max.y()) {
                for (z in min.z()..max.z()) {
                    val pos = BlockVector3.at(x, y, z)
                    val data = blockDataAt(clipboard, pos) ?: continue
                    if (!isPowerSource(data)) continue

                    if (visited.add(pos)) {
                        queue.add(Node(pos, 0))
                    }

                    // 起点に直接隣接しないダスト・ノートブロックも拾う（広域探索）
                    for ((dx, dy, dz) in SOURCE_REACH_OFFSETS) {
                        val reachPos = pos.add(dx, dy, dz)
                        if (!region.contains(reachPos)) continue
                        val reachData = blockDataAt(clipboard, reachPos) ?: continue

                        if (reachData.material == Material.REDSTONE_WIRE) {
                            if (visited.add(reachPos)) {
                                queue.add(Node(reachPos, 0))
                            }
                        } else if (reachData is BukkitNoteBlock) {
                            fireNoteAndContinue(reachData, reachPos, 0, world, notes, firedNoteKeys, visited, queue)
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

            // 2-1. 隣接するノートブロックへ通電 → 発音として記録し、そのノートブロック自身も
            //      (真上に乗ったダスト等への)伝播元として扱う
            for ((dx, dy, dz) in ADJACENT_6) {
                val neighborPos = pos.add(dx, dy, dz)
                if (!region.contains(neighborPos)) continue
                val neighborData = blockDataAt(clipboard, neighborPos) ?: continue
                if (neighborData is BukkitNoteBlock) {
                    fireNoteAndContinue(neighborData, neighborPos, timeMs, world, notes, firedNoteKeys, visited, queue)
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
            for ((dx, dy, dz) in CARDINAL_4) {
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

    /**
     * ノートブロックの発音を記録し、看板による音量/Pan上書きを適用したうえで、
     * そのノートブロック自身をBFSの伝播元としてキューへ追加する
     * （「リピーター→ノートブロック→その上のダスト→次のリピーター」という連鎖に対応するため）。
     */
    private fun fireNoteAndContinue(
        noteBlockData: BukkitNoteBlock,
        pos: BlockVector3,
        timeMs: Int,
        world: World,
        notes: MutableList<NoteEvent>,
        firedNoteKeys: MutableSet<Pair<BlockVector3, Int>>,
        visited: MutableSet<BlockVector3>,
        queue: ArrayDeque<Node>,
    ) {
        val key = pos to timeMs
        if (firedNoteKeys.add(key)) {
            var volume = 100
            var pan = 0
            val (overrideVolume, overridePan) = SignOverrideProcessor.extractFromWorldPos(world, pos)
            overrideVolume?.let { volume = it }
            overridePan?.let { pan = it }

            notes += NoteEvent(
                timeMs = timeMs,
                instrument = InstrumentMapper.toId(noteBlockData.instrument),
                pitch = noteBlockData.note.id,
                volume = volume,
                pan = pan,
            )
        }
        if (visited.add(pos)) {
            queue.add(Node(pos, timeMs))
        }
    }

    private fun blockDataAt(clipboard: Clipboard, pos: BlockVector3): BlockData? = try {
        BukkitAdapter.adapt(clipboard.getFullBlock(pos))
    } catch (_: Exception) {
        null
    }

    /**
     * 起点(電力源)候補かどうかを判定する。クリップボードを静的に解析するだけなので、
     * レバーのON/OFF状態やボタンの押下状態は問わない（起点の目印として扱うのみ）。
     */
    private fun isPowerSource(data: BlockData): Boolean = when (data.material) {
        Material.REDSTONE_BLOCK, Material.REDSTONE_TORCH, Material.REDSTONE_WALL_TORCH, Material.LEVER -> true
        else -> data.material.name.endsWith("_BUTTON")
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
