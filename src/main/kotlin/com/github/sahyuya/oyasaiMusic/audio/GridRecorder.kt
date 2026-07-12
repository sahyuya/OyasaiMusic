package com.github.sahyuya.oyasaiMusic.audio

import com.github.sahyuya.oyasaiMusic.model.NoteEvent
import com.sk89q.worldedit.bukkit.BukkitAdapter
import com.sk89q.worldedit.extent.clipboard.Clipboard
import com.sk89q.worldedit.math.BlockVector3
import org.bukkit.block.BlockFace
import org.bukkit.block.data.type.NoteBlock as BukkitNoteBlock

/**
 * グリッド型録音（データ・システム設計書 3章 `/record we grid <BPM>`）。
 *
 * FAWEクリップボード内を、コマンド実行時のプレイヤーの水平向き（東西南北のいずれか）を
 * 時間軸として走査する。時間軸方向の座標インデックスを「拍」とみなし、
 * BPMから算出した等間隔ミリ秒（stepMs = 60000 / BPM）を割り当てる。
 * 時間軸と直交する2軸に並ぶ複数のノートブロックは同一時刻の和音として扱われる。
 *
 * 音量・Panはデフォルト値(100 / 0)とし、[SignOverrideProcessor] による
 * 看板上書きがあればそちらを優先する（設計書3章の「音量とPanの静的取得」）。
 */
object GridRecorder {

    /**
     * @param clipboard 走査対象のFAWEクリップボード（プレイヤーが事前に //copy 等で確保したもの）
     * @param bpm 基準BPM
     * @param timeAxisFacing 時間軸として使用する水平向き（コマンド実行時のプレイヤーの向きを渡す）
     */
    fun record(clipboard: Clipboard, bpm: Int, timeAxisFacing: BlockFace): List<NoteEvent> {
        require(bpm > 0) { "BPMは1以上である必要があります: $bpm" }
        val stepMs = 60000.0 / bpm

        val region = clipboard.region
        val min = region.minimumPoint
        val max = region.maximumPoint
        val axis = resolveAxis(timeAxisFacing)

        val notes = mutableListOf<NoteEvent>()

        for (x in min.x()..max.x()) {
            for (y in min.y()..max.y()) {
                for (z in min.z()..max.z()) {
                    val pos = BlockVector3.at(x, y, z)
                    val baseBlock = clipboard.getFullBlock(pos)
                    val bukkitData = BukkitAdapter.adapt(baseBlock)
                    val noteBlockData = bukkitData as? BukkitNoteBlock ?: continue

                    val timeIndex = axis.indexOf(pos, min)
                    val timeMs = (timeIndex * stepMs).toInt()

                    var volume = 100
                    var pan = 0
                    val (overrideVolume, overridePan) = SignOverrideProcessor.extractFromClipboard(clipboard, pos)
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
            }
        }
        return notes
    }

    private fun resolveAxis(facing: BlockFace): TimeAxis = when (facing) {
        BlockFace.NORTH, BlockFace.SOUTH -> TimeAxis.Z
        BlockFace.EAST, BlockFace.WEST -> TimeAxis.X
        // 水平4方位以外(UP/DOWN等)が渡された場合はZ軸へフォールバックする
        else -> TimeAxis.Z
    }

    private enum class TimeAxis {
        X, Z;

        fun indexOf(pos: BlockVector3, min: BlockVector3): Int = when (this) {
            X -> pos.x() - min.x()
            Z -> pos.z() - min.z()
        }
    }
}
