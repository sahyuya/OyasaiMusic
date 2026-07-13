package com.github.sahyuya.oyasaiMusic.audio

import com.github.sahyuya.oyasaiMusic.model.NoteEvent
import net.kyori.adventure.sound.Sound as AdventureSound
import org.bukkit.Location
import org.bukkit.SoundCategory
import org.bukkit.entity.Player
import kotlin.math.cos
import kotlin.math.sin

/**
 * 1音符を実際にプレイヤーへ送信する処理（データ・システム設計書 4章）。
 *
 * [PlaybackMode.ENTITY_EMITTER] (デフォルト):
 *   Adventure APIの `Audience#playSound(Sound, Sound.Emitter)` に `Sound.Emitter.self()` を渡すことで、
 *   音源をプレイヤー自身のエンティティに紐づけて送信する（内部的には `ClientboundSoundEntityPacket`
 *   が使われる）。音源がプレイヤーに追従し続けるため、歩きながら聴いても音響が乱れない。
 *   Panという概念自体が存在しないため、定位計算は行わない。
 *
 * [PlaybackMode.POSITIONAL] (オプション/高音質版):
 *   従来通り `Player#playSound(Location, Sound, SoundCategory, float, float)` を、プレイヤー正面を
 *   基準とした半円弧上の仮想座標に対して呼び出す方式。ステレオ定位(Pan)を再現できる一方、
 *   発音した瞬間のプレイヤー位置・向きを基準に座標を固定するため、その音が鳴り切るまでの間に
 *   プレイヤーが動くと定位がズレて感じられることがある。
 */
object SoundDispatcher {

    /** POSITIONAL方式で、仮想音源をプレイヤーから何ブロック離すか（"単位円"上に配置するため1.0）。 */
    private const val SOURCE_RADIUS_BLOCKS = 1.0

    fun play(recipient: Player, note: NoteEvent, mode: PlaybackMode, isBedrock: Boolean) {
        when (mode) {
            PlaybackMode.ENTITY_EMITTER -> playEntityEmitter(recipient, note)
            // 統合版(Bedrock)は設計書の制約によりPanを常に無効化(=正面固定)する
            PlaybackMode.POSITIONAL -> playPositional(recipient, note, pan = if (isBedrock) 0 else note.pan)
        }
    }

    private fun playEntityEmitter(recipient: Player, note: NoteEvent) {
        val bukkitSound = InstrumentMapper.soundFor(InstrumentMapper.toInstrument(note.instrument))
        val pitch = InstrumentMapper.pitchToPlaybackPitch(note.pitch)
        val volume = volumeParam(note.volume)
        val adventureSound = AdventureSound.sound(bukkitSound.key(), AdventureSound.Source.RECORD, volume, pitch)
        recipient.playSound(adventureSound, AdventureSound.Emitter.self())
    }

    private fun playPositional(recipient: Player, note: NoteEvent, pan: Int) {
        val sound = InstrumentMapper.soundFor(InstrumentMapper.toInstrument(note.instrument))
        val pitch = InstrumentMapper.pitchToPlaybackPitch(note.pitch)
        val volume = volumeParam(note.volume)
        val location = virtualLocation(recipient, pan)
        recipient.playSound(location, sound, SoundCategory.RECORDS, volume, pitch)
    }

    private fun volumeParam(volume0to100: Int): Float = volume0to100.coerceIn(0, 100) / 100f

    /**
     * POSITIONAL方式専用。プレイヤー正面を0度として左右90度ずつに広がる半径1ブロックの
     * 半円弧上に仮想音源を配置する。Pan=0は正面(ローカル座標 ^ ^ ^1)、Pan+100は右90度、
     * Pan-100は左90度になる。
     */
    private fun virtualLocation(player: Player, pan: Int): Location {
        val eye = player.eyeLocation
        val yawRad = Math.toRadians(player.location.yaw.toDouble())

        val forwardX = -sin(yawRad)
        val forwardZ = cos(yawRad)
        val rightX = -cos(yawRad)
        val rightZ = -sin(yawRad)

        val theta = Math.toRadians(pan.coerceIn(-100, 100) / 100.0 * 90.0)
        val dirX = forwardX * cos(theta) + rightX * sin(theta)
        val dirZ = forwardZ * cos(theta) + rightZ * sin(theta)

        return eye.clone().add(dirX * SOURCE_RADIUS_BLOCKS, 0.0, dirZ * SOURCE_RADIUS_BLOCKS)
    }
}
