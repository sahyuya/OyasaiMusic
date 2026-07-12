package com.github.sahyuya.oyasaiMusic.audio

import com.github.sahyuya.oyasaiMusic.model.NoteEvent
import org.bukkit.Location
import org.bukkit.SoundCategory
import org.bukkit.entity.Player
import kotlin.math.cos
import kotlin.math.sin

/**
 * 1音符を実際にプレイヤーへ送信する処理（データ・システム設計書 4章）。
 *
 * 生のパケット(ClientboundSoundPacket)を直接組み立てる代わりに、
 * Bukkit標準の `Player#playSound(Location, Sound, SoundCategory, float, float)` を
 * プレイヤーの頭の位置から左右にオフセットした仮想座標に対して呼び出すことで、
 * クライアント側の自然なステレオ定位計算に委ねている（結果として得られる音響効果は同等）。
 */
object SoundDispatcher {

    /** Pan ±100 に対して、仮想音源をプレイヤーから何ブロック左右にずらすか。 */
    private const val PAN_MAX_OFFSET_BLOCKS = 3.0

    /** Java版プレイヤー向け: 視線(Yaw)を基準としたステレオ定位で再生する。 */
    fun playForJava(recipient: Player, note: NoteEvent) {
        val sound = InstrumentMapper.soundFor(InstrumentMapper.toInstrument(note.instrument))
        val pitch = InstrumentMapper.pitchToPlaybackPitch(note.pitch)
        val volume = (note.volume / 100f).coerceIn(0f, 1f)
        val location = virtualLocation(recipient, note.pan)
        recipient.playSound(location, sound, SoundCategory.RECORDS, volume, pitch)
    }

    /**
     * 統合版(Bedrock/Geyser)プレイヤー向け: UI/UX設計書 1章・データ設計書 4-2章の制限に従い、
     * Panは常に無効化(中心固定)して再生する。和音数の間引き(chord limit)は
     * [com.oyasai.music.audio.playback.PlaybackEngine] 側で事前にフィルタ済みの
     * NoteEventのみがここに渡ってくる想定。
     */
    fun playForBedrock(recipient: Player, note: NoteEvent) {
        val sound = InstrumentMapper.soundFor(InstrumentMapper.toInstrument(note.instrument))
        val pitch = InstrumentMapper.pitchToPlaybackPitch(note.pitch)
        val volume = (note.volume / 100f).coerceIn(0f, 1f)
        recipient.playSound(recipient.eyeLocation, sound, SoundCategory.RECORDS, volume, pitch)
    }

    private fun virtualLocation(player: Player, pan: Int): Location {
        val eye = player.eyeLocation
        if (pan == 0) return eye
        val yawRad = Math.toRadians(player.location.yaw.toDouble())
        // 前方ベクトル(-sin, 0, cos)を90度回転させた右方向ベクトル。
        // ゲーム内での左右体感が逆であれば符号を反転して調整すること。
        val rightX = cos(yawRad)
        val rightZ = sin(yawRad)
        val offset = (pan.coerceIn(-100, 100) / 100.0) * PAN_MAX_OFFSET_BLOCKS
        return eye.clone().add(rightX * offset, 0.0, rightZ * offset)
    }
}
