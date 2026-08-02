package com.github.sahyuya.oyasaiMusic.audio

import com.github.sahyuya.oyasaiMusic.model.NoteEvent
import com.sk89q.worldedit.bukkit.BukkitAdapter
import com.sk89q.worldedit.extent.clipboard.Clipboard
import com.sk89q.worldedit.math.BlockVector3
import org.bukkit.Bukkit
import org.bukkit.block.BlockFace
import org.bukkit.Material
import org.bukkit.World
import org.bukkit.block.data.BlockData
import org.bukkit.block.data.Directional
import org.bukkit.block.data.FaceAttachable
import org.bukkit.block.data.type.NoteBlock as BukkitNoteBlock
import org.bukkit.block.data.type.RedstoneWire
import org.bukkit.block.data.type.Repeater
import java.util.PriorityQueue
import kotlin.math.roundToInt

/**
 * FAWEクリップボード内のレッドストーン回路を、実際に通電させずに時刻順でシミュレーションする録音器。
 *
 * ダストはBlockDataの接続面に従って同一tick内で伝播し、リピーターだけが設定値ごとに
 * 2 redstone tick (100ms) の遅延を加える。ボタン・レバーは本体と設置先の両方を電源として扱い、
 * ダストは直下の導体を弱く通電させる。一方で導体同士を連鎖させないため、床全体を誤って
 * 回路と判定せず、実際のレッドストーンに近い「部品を介した」信号伝播になる。
 */
object CircuitRecorder {

    private data class Signal(val pos: BlockVector3, val timeMs: Int, val power: Int) : Comparable<Signal> {
        override fun compareTo(other: Signal): Int = compareValuesBy(this, other, Signal::timeMs, { -it.power })
    }

    private data class RepeaterOutput(val output: BlockVector3, val delayMs: Int)
    private data class RawNote(
        val timeMs: Double,
        val instrument: Int,
        val pitch: Byte,
        val volume: Int,
        val pan: Int,
        val customSound: String?,
        val customSoundSeed: Long?,
    )

    private const val QUARTER_NOTE_MS = 500.0
    private const val MAX_SIGNAL_TIME_MS = 10 * 60 * 1000
    private const val MAX_REDSTONE_POWER = 15

    private val HORIZONTAL_FACES = listOf(BlockFace.NORTH, BlockFace.EAST, BlockFace.SOUTH, BlockFace.WEST)
    private val ADJACENT_6 = listOf(
        Triple(1, 0, 0), Triple(-1, 0, 0), Triple(0, 1, 0),
        Triple(0, -1, 0), Triple(0, 0, 1), Triple(0, 0, -1),
    )

    fun record(clipboard: Clipboard, world: World): List<NoteEvent> {
        val region = clipboard.region
        val blocks = HashMap<BlockVector3, BlockData>()
        val sources = mutableListOf<BlockVector3>()
        val wires = HashSet<BlockVector3>()
        val repeaters = mutableListOf<Pair<BlockVector3, Repeater>>()

        val min = region.minimumPoint
        val max = region.maximumPoint
        for (x in min.x()..max.x()) for (y in min.y()..max.y()) for (z in min.z()..max.z()) {
            val pos = BlockVector3.at(x, y, z)
            val data = blockDataAt(clipboard, pos) ?: continue
            blocks[pos] = data
            when {
                isPowerSource(data) -> sources += pos
                data.material == Material.REDSTONE_WIRE -> wires += pos
                data is Repeater -> repeaters += pos to data
            }
        }
        if (sources.isEmpty()) return emptyList()

        // リピーターは背面の同じ高さにある入力だけを受け、正面へ設定値ぶん遅れて出力する。
        // ダストが背面にある場合と、背面ブロックが直接通電した場合の両方をこの入力点へ集約する。
        val repeaterInputs = HashMap<BlockVector3, MutableList<RepeaterOutput>>()
        repeaters.forEach { (pos, repeater) ->
            val input = pos.add(-repeater.facing.modX, -repeater.facing.modY, -repeater.facing.modZ)
            val output = pos.add(repeater.facing.modX, repeater.facing.modY, repeater.facing.modZ)
            val edge = RepeaterOutput(output, repeater.delay * 100)
            if (region.contains(input)) repeaterInputs.getOrPut(input) { mutableListOf() } += edge
        }

        val queue = PriorityQueue<Signal>()
        val bestSignals = HashMap<BlockVector3, Signal>()
        fun enqueue(pos: BlockVector3, timeMs: Int, power: Int) {
            if (!region.contains(pos) || timeMs > MAX_SIGNAL_TIME_MS || power <= 0) return
            val signal = Signal(pos, timeMs, power)
            val previous = bestSignals[pos]
            if (previous != null && (previous.timeMs < timeMs || previous.timeMs == timeMs && previous.power >= power)) return
            bestSignals[pos] = signal
            queue += signal
        }

        // ボタン・レバーは部品本体と設置先のブロックを、トーチは支えるブロックを時刻0で通電する。
        sources.forEach { source ->
            val sourceData = blocks[source] ?: return@forEach
            sourcePowerAnchors(source, sourceData).forEach { anchor -> enqueue(anchor, 0, MAX_REDSTONE_POWER) }
        }

        val notes = mutableListOf<RawNote>()
        val firedNotes = HashSet<BlockVector3>()
        fun recordNote(pos: BlockVector3, data: BukkitNoteBlock, timeMs: Int) {
            if (!firedNotes.add(pos)) return
            val exactDelay = SignOverrideProcessor.extractDelayMillisExactFromWorldPos(world, pos, QUARTER_NOTE_MS) ?: 0.0
            val customSound = SignOverrideProcessor.extractCustomSoundFromWorldPos(world, pos)
            notes += RawNote(
                timeMs = timeMs + exactDelay,
                instrument = InstrumentMapper.toId(data.instrument),
                pitch = data.note.id,
                volume = SignOverrideProcessor.extractFromWorldPos(world, pos).first ?: 100,
                pan = SignOverrideProcessor.extractFromWorldPos(world, pos).second ?: 0,
                customSound = customSound?.eventKey,
                customSoundSeed = customSound?.seed,
            )
        }

        fun enqueueWireOnTopOf(anchor: BlockVector3, timeMs: Int, power: Int) {
            val dust = anchor.add(0, 1, 0)
            if (dust in wires) enqueue(dust, timeMs, power)
        }

        fun enqueueDustNextToSource(source: BlockVector3, timeMs: Int, power: Int) {
            HORIZONTAL_FACES.forEach { face ->
                val dust = source.offset(face)
                if (dust in wires) enqueue(dust, timeMs, power)
            }
        }

        while (queue.isNotEmpty()) {
            val signal = queue.poll()
            if (bestSignals[signal.pos] != signal) continue
            val data = blocks[signal.pos]

            if (data is RedstoneWire) {
                // ダストは接続面に従うダストだけへ伝播する。直下の導体は弱く通電するが、
                // その導体から別の導体へは伝播させない（ダストそのものを万能な導線にしない）。
                connectedWireNeighbors(signal.pos, data, blocks).forEach { neighbor ->
                    enqueue(neighbor, signal.timeMs, signal.power - 1)
                }
                enqueue(signal.pos.add(0, -1, 0), signal.timeMs, signal.power)
                recordAdjacentNotes(signal.pos, signal.timeMs, blocks, ::recordNote)
                repeaterInputs[signal.pos]?.forEach { edge ->
                    enqueue(edge.output, signal.timeMs + edge.delayMs, MAX_REDSTONE_POWER)
                }
            } else {
                // 通電したブロックは隣接ノートと上面ダストを起動できる。固体ブロック同士へは
                // 伝播しないため、回路に無関係な床・壁を経路に含めない。
                if (data is BukkitNoteBlock) recordNote(signal.pos, data, signal.timeMs)
                recordAdjacentNotes(signal.pos, signal.timeMs, blocks, ::recordNote)
                enqueueWireOnTopOf(signal.pos, signal.timeMs, signal.power)
                repeaterInputs[signal.pos]?.forEach { edge ->
                    enqueue(edge.output, signal.timeMs + edge.delayMs, MAX_REDSTONE_POWER)
                }

                // ボタン・レバー本体は、横に接続されたダストも直接起動できる。
                if (data != null && isDirectDustPowerSource(data)) {
                    enqueueDustNextToSource(signal.pos, signal.timeMs, signal.power)
                }
            }
        }

        val firstTime = notes.minOfOrNull { it.timeMs } ?: return emptyList()
        return notes.sortedBy { it.timeMs }.map { note ->
            NoteEvent(
                timeMs = (note.timeMs - firstTime).roundToInt().coerceAtLeast(0),
                instrument = note.instrument,
                pitch = note.pitch,
                volume = note.volume,
                pan = note.pan,
                customSound = note.customSound,
                customSoundSeed = note.customSoundSeed,
            )
        }
    }

    private fun blockDataAt(clipboard: Clipboard, pos: BlockVector3): BlockData? {
        val fullBlock = clipboard.getFullBlock(pos)
        return runCatching { BukkitAdapter.adapt(fullBlock) }.getOrElse {
            runCatching { Bukkit.createBlockData(fullBlock.asString) }.getOrNull()
        }
    }

    private fun isPowerSource(data: BlockData): Boolean = when (data.material) {
        Material.REDSTONE_BLOCK, Material.REDSTONE_TORCH, Material.REDSTONE_WALL_TORCH, Material.LEVER -> true
        else -> data.material.name.endsWith("_BUTTON")
    }

    /** ボタン・レバー・壁付きトーチの設置先を、部品本体と同じ電源として返す。 */
    private fun sourcePowerAnchors(pos: BlockVector3, data: BlockData): Set<BlockVector3> = buildSet {
        add(pos)
        attachedSupport(pos, data)?.let(::add)
    }

    private fun attachedSupport(pos: BlockVector3, data: BlockData): BlockVector3? = when {
        data is FaceAttachable && data is Directional -> when (data.attachedFace) {
            FaceAttachable.AttachedFace.FLOOR -> pos.add(0, -1, 0)
            FaceAttachable.AttachedFace.CEILING -> pos.add(0, 1, 0)
            FaceAttachable.AttachedFace.WALL -> pos.offset(data.facing.oppositeFace)
        }
        data.material == Material.REDSTONE_TORCH -> pos.add(0, -1, 0)
        data.material == Material.REDSTONE_WALL_TORCH && data is Directional -> pos.offset(data.facing.oppositeFace)
        else -> null
    }

    /** ボタン・レバーは横に接続されたダストを直接通電できる。 */
    private fun isDirectDustPowerSource(data: BlockData): Boolean =
        data.material == Material.LEVER || data.material.name.endsWith("_BUTTON")

    /**
     * RedstoneWireの接続面をそのまま使い、同じ高さ・上り坂・下り坂のダストだけを返す。
     * 下り坂は低い側のダストがUP接続を持つため、その逆向きの面を確認する。
     */
    private fun connectedWireNeighbors(
        pos: BlockVector3,
        wire: RedstoneWire,
        blocks: Map<BlockVector3, BlockData>,
    ): Set<BlockVector3> = buildSet {
        HORIZONTAL_FACES.forEach { face ->
            when (wire.getFace(face)) {
                RedstoneWire.Connection.SIDE -> add(pos.offset(face))
                RedstoneWire.Connection.UP -> add(pos.offset(face).add(0, 1, 0))
                RedstoneWire.Connection.NONE -> Unit
            }

            val lower = pos.offset(face).add(0, -1, 0)
            val lowerWire = blocks[lower] as? RedstoneWire
            if (lowerWire?.getFace(face.oppositeFace) == RedstoneWire.Connection.UP) add(lower)
        }
        removeIf { blocks[it]?.material != Material.REDSTONE_WIRE }
    }

    private fun recordAdjacentNotes(
        pos: BlockVector3,
        timeMs: Int,
        blocks: Map<BlockVector3, BlockData>,
        record: (BlockVector3, BukkitNoteBlock, Int) -> Unit,
    ) {
        ADJACENT_6.forEach { (x, y, z) ->
            val notePos = pos.add(x, y, z)
            val note = blocks[notePos] as? BukkitNoteBlock ?: return@forEach
            record(notePos, note, timeMs)
        }
    }

    private fun BlockVector3.offset(face: BlockFace): BlockVector3 = add(face.modX, face.modY, face.modZ)
}
