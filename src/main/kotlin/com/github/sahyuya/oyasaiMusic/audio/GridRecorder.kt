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
     * @param world 看板の読み取りに使用するワールド（//copy した際の元の建築がまだ残っている前提）
     */
    fun record(clipboard: Clipboard, bpm: Int, timeAxisFacing: BlockFace, world: org.bukkit.World): List<NoteEvent> {
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

                    val timeIndex = axis.indexOf(pos, min, max)
                    val timeMs = (timeIndex * stepMs).toInt()

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
            }
        }
        return notes
    }

    /**
     * プレイヤーのYawのみから、水平4方位(NORTH/SOUTH/EAST/WEST)のうち最も近いものを判定する。
     * `Entity#getFacing()` はPitch次第でUP/DOWNを返すことがあるため、
     * 録音の時間軸判定には代わりにこちらを使用する（水平4方位に必ず丸め込む）。
     */
    fun horizontalFacingFromYaw(yaw: Float): BlockFace {
        val normalized = ((yaw % 360) + 360) % 360
        return when {
            normalized < 45 || normalized >= 315 -> BlockFace.SOUTH
            normalized < 135 -> BlockFace.WEST
            normalized < 225 -> BlockFace.NORTH
            else -> BlockFace.EAST
        }
    }

    private fun resolveAxis(facing: BlockFace): TimeAxis = when (facing) {
        BlockFace.SOUTH -> TimeAxis.Z_POSITIVE
        BlockFace.NORTH -> TimeAxis.Z_NEGATIVE
        BlockFace.EAST -> TimeAxis.X_POSITIVE
        BlockFace.WEST -> TimeAxis.X_NEGATIVE
        // 水平4方位以外(UP/DOWN等)が渡された場合はフォールバックする
        else -> TimeAxis.Z_POSITIVE
    }

    /**
     * 時間軸として使う座標軸と、その向き（プレイヤーが向いている方向ほど時刻が進むように、
     * 座標が増える方向を使うか減る方向を使うかを表す）。
     */
    private enum class TimeAxis {
        X_POSITIVE, X_NEGATIVE, Z_POSITIVE, Z_NEGATIVE;

        fun indexOf(pos: BlockVector3, min: BlockVector3, max: BlockVector3): Int = when (this) {
            X_POSITIVE -> pos.x() - min.x()
            X_NEGATIVE -> max.x() - pos.x()
            Z_POSITIVE -> pos.z() - min.z()
            Z_NEGATIVE -> max.z() - pos.z()
        }
    }
}
