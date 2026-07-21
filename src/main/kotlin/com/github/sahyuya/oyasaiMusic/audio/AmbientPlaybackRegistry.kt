package com.github.sahyuya.oyasaiMusic.audio

import com.github.sahyuya.oyasaiMusic.OyasaiMusic
import com.github.sahyuya.oyasaiMusic.AmbientRange
import com.github.sahyuya.oyasaiMusic.AmbientTrigger
import com.github.sahyuya.oyasaiMusic.model.Song
import org.bukkit.Location
import org.bukkit.entity.Player
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * 環境BGM用レコード（UI/UX設計書9章）が設置されたジュークボックスの再生状態を管理する。
 *
 * 【実装方針（要確認）】
 * このアイテムはバニラの音楽レコードではなく独自形式の楽曲のため、ジュークボックスへの
 * 挿入時にバニラの挿入処理自体はキャンセルし（見た目上ジュークボックスは空のまま）、
 * このレジストリが座標をキーに独自の再生セッションを管理する。
 *   - トリガー=ジュークボックス: 設置と同時に再生開始
 *   - トリガー=RS信号: 設置場所への通電/断電で再生開始/停止
 *   - トリガー=接近: 範囲内にプレイヤーが入った時点で自動的に再生開始、居なくなったら停止
 * 範囲内のプレイヤーは [tick] （1秒ごとに呼び出す想定）で追従させ、動いて範囲外に出た
 * プレイヤーへは音を止め、新たに入ってきたプレイヤーには鳴らし始める。
 */
class AmbientPlaybackRegistry(private val plugin: OyasaiMusic) {

    data class AmbientEntry(
        val location: Location,
        val song: Song,
        val range: AmbientRange,
        val trigger: AmbientTrigger,
        val loop: Boolean,
        var session: PlaybackSession? = null,
    )

    private val entries = ConcurrentHashMap<String, AmbientEntry>()

    private fun key(location: Location): String = "${location.world?.uid}:${location.blockX}:${location.blockY}:${location.blockZ}"

    fun entryAt(location: Location): AmbientEntry? = entries[key(location)]

    fun register(location: Location, song: Song, range: AmbientRange, trigger: AmbientTrigger, loop: Boolean) {
        val k = key(location)
        entries[k] = AmbientEntry(location.clone(), song, range, trigger, loop)
        if (trigger == AmbientTrigger.JUKEBOX) startPlayback(k)
    }

    fun unregister(location: Location) {
        val k = key(location)
        stopPlayback(k)
        entries.remove(k)
    }

    fun onRedstoneChange(location: Location, powered: Boolean) {
        val k = key(location)
        val entry = entries[k] ?: return
        if (entry.trigger != AmbientTrigger.REDSTONE) return
        if (powered) startPlayback(k) else stopPlayback(k)
    }

    /** 接近トリガーの開始判定・範囲内リスナーの追従のため、定期的(1秒毎想定)に呼び出す。 */
    fun tick() {
        entries.forEach { (k, entry) ->
            val nearby = nearbyPlayers(entry)

            if (entry.trigger == AmbientTrigger.PROXIMITY) {
                if (nearby.isNotEmpty() && entry.session == null) startPlayback(k)
                if (nearby.isEmpty() && entry.session != null) stopPlayback(k)
            }

            val session = entry.session ?: return@forEach
            val nearbyUuids = nearby.map { it.uniqueId }.toSet()
            session.recipients.filter { it !in nearbyUuids }.forEach { session.recipients.remove(it) }
            nearby.forEach { p -> session.recipients.add(p.uniqueId) }
        }
    }

    /** プラグイン無効化時に全セッションを止める。 */
    fun stopAll() {
        entries.values.forEach { it.session?.let { s -> plugin.playbackEngine.stop(s) } }
        entries.clear()
    }

    private fun nearbyPlayers(entry: AmbientEntry): List<Player> {
        val world = entry.location.world ?: return emptyList()
        val range = entry.range.blocks
        return world.players.filter { p -> range == null || p.location.distance(entry.location) <= range }
    }

    private fun startPlayback(key: String) {
        val entry = entries[key] ?: return
        if (entry.session != null) return
        val file = File(plugin.audioDirectory, entry.song.fileName)
        if (!file.exists()) return
        val audio = SongAudioFile.read(file)
        val recipients = nearbyPlayers(entry)
        val session = plugin.playbackEngine.play(
            song = entry.song,
            notes = audio.notes,
            recipients = recipients,
            isAmbientPlayback = true,
            onCompletion = {
                entry.session = null
                if (entry.loop) startPlayback(key)
            },
        )
        entry.session = session
    }

    private fun stopPlayback(key: String) {
        val entry = entries[key] ?: return
        entry.session?.let { plugin.playbackEngine.stop(it) }
        entry.session = null
    }
}