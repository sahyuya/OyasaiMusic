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
 * プレイヤーの頭を基準とした仮想座標に対して呼び出すことで、クライアント側の
 * 自然なステレオ定位計算に委ねている。
 *
 * ## Pan配置ロジック（2024/07 修正）
 * 仮想音源は「プレイヤーの正面を0度として、左右90度ずつに広がる半径1ブロックの
 * 半円弧」の上に配置する。
 *   - Pan = 0   → 正面(ローカル座標で ^ ^ ^1 の位置、= 目の前)
 *   - Pan = 100 → 正面から右へ90度  (設計書通り 100=右)
 *   - Pan = -100→ 正面から左へ90度  (設計書通り -100=左)
 * 中間値は弧上を線形補間した角度に配置する。
 *
 * 以前の実装ではPan=0のとき仮想音源とプレイヤーの座標が完全に一致しており、
 * クライアント側の定位計算が不定になって常に一方向（ワールド+X寄り）から
 * 聞こえる不具合があったため、常に半径1ブロックのオフセットを持たせるよう修正した。
 * また、右方向ベクトルの符号が反転していた（実際の右ではなく左を指していた）バグも修正済み。
 */
object SoundDispatcher {

    /** 仮想音源をプレイヤーから何ブロック離すか（"単位円"上に配置するため1.0）。 */
    private const val SOURCE_RADIUS_BLOCKS = 1.0

    /** Volume100(=note.volume=100)のときにplaySoundへ渡す実際のvolumeパラメータ。300%相当。 */
    private const val VOLUME_SCALE = 30.0f

    /** Java版プレイヤー向け: 視線(Yaw)を基準とした半円ステレオ定位で再生する。 */
    fun playForJava(recipient: Player, note: NoteEvent) {
        val sound = InstrumentMapper.soundFor(InstrumentMapper.toInstrument(note.instrument))
        val pitch = InstrumentMapper.pitchToPlaybackPitch(note.pitch)
        val volume = volumeParam(note.volume)
        val location = virtualLocation(recipient, note.pan)
        recipient.playSound(location, sound, SoundCategory.RECORDS, volume, pitch)
    }

    /**
     * 統合版(Bedrock/Geyser)プレイヤー向け: UI/UX設計書 1章・データ設計書 4-2章の制限に従い、
     * Panは常に無効化(常に正面固定)して再生する。和音数の間引き(chord limit)は
     * [PlaybackEngine] 側で事前にフィルタ済みのNoteEventのみがここに渡ってくる想定。
     */
    fun playForBedrock(recipient: Player, note: NoteEvent) {
        val sound = InstrumentMapper.soundFor(InstrumentMapper.toInstrument(note.instrument))
        val pitch = InstrumentMapper.pitchToPlaybackPitch(note.pitch)
        val volume = volumeParam(note.volume)
        // Pan=0固定(=正面1ブロック)。座標を完全一致させない理由はvirtualLocationのコメントを参照。
        val location = virtualLocation(recipient, 0)
        recipient.playSound(location, sound, SoundCategory.RECORDS, volume, pitch)
    }

    private fun volumeParam(volume0to100: Int): Float =
        (volume0to100.coerceIn(0, 100) / 100f) * VOLUME_SCALE

    private fun virtualLocation(player: Player, pan: Int): Location {
        val eye = player.eyeLocation
        val yawRad = Math.toRadians(player.location.yaw.toDouble())

        // 前方ベクトル: Minecraftの標準的なyaw→方向ベクトルの式 (-sin, 0, cos)
        val forwardX = -sin(yawRad)
        val forwardZ = cos(yawRad)
        // 右方向ベクトル: 前方を90度時計回り(上から見て)に回転させたもの。
        // yaw=0(南向き)のとき右手は西(-X)を指すため (-cos, 0, -sin) が正しい
        // （以前の実装は符号を誤り、常に+X寄りに聞こえるバグの原因になっていた）。
        val rightX = -cos(yawRad)
        val rightZ = -sin(yawRad)

        // Pan(-100..100)を、正面を0度とした左右±90度の弧上の角度θへ変換する。
        val theta = Math.toRadians(pan.coerceIn(-100, 100) / 100.0 * 90.0)
        val dirX = forwardX * cos(theta) + rightX * sin(theta)
        val dirZ = forwardZ * cos(theta) + rightZ * sin(theta)

        return eye.clone().add(dirX * SOURCE_RADIUS_BLOCKS, 0.0, dirZ * SOURCE_RADIUS_BLOCKS)
    }
}
