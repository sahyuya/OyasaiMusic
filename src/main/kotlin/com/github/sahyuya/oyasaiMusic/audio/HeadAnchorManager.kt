package com.github.sahyuya.oyasaiMusic.audio

import org.bukkit.Bukkit
import org.bukkit.entity.AreaEffectCloud
import org.bukkit.entity.ArmorStand
import org.bukkit.entity.Entity
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.plugin.Plugin
import org.bukkit.scheduler.BukkitTask
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * [PlaybackMode.DEFAULT]（デフォルト再生）用の、プレイヤーの頭の高さに追従するSound.Emitterを管理する。
 *
 * ## 経緯（騎乗方式から追従方式へ変更）
 * 当初はプレイヤーへ[ArmorStand]を騎乗(passenger)させる方式を採用したが、実機検証の結果、
 * 騎乗中のマーカーは見た目（頭に乗る）は正しく機能するものの、それをSound.Emitterとして
 * 使っても音が再生されない不具合が確認された。乗せている本人（vehicle）自身に対して、
 * その騎乗物（passenger）をエンティティ追跡・パケット解決の対象として正しく扱えない
 * （＝乗せている本人には自分の騎乗物のエンティティが「見えている」扱いにならない）可能性が高い。
 *
 * そのため、**騎乗はさせず、通常の独立したエンティティとして毎tickプレイヤーの目線位置へ
 * テレポートで追従させる方式**に変更した。通常のエンティティ追跡の対象になるため
 * Sound.Emitterとして確実に機能する。
 *
 * 負荷対策として、マーカーは「現在再生中で追従が必要なプレイヤー」のみ生成し
 * （[acquire]/[release] による参照カウント）、追従用の定期タスクもその集合が空の間は
 * 何も行わない。再生が終わるとマーカー自体を消去する（要望通り、再生中のみ存在する状態にする）。
 */
class HeadAnchorManager(private val plugin: Plugin) : Listener {

    private val anchors = ConcurrentHashMap<UUID, AreaEffectCloud>()
    // 現在再生中で追従が必要なプレイヤーの参照カウント（同時に複数の再生セッションが
    // 同じリスナーへ向けて動くこともあるため、単純なON/OFFではなくカウントで管理する）
    private val activeSessionCount = ConcurrentHashMap<UUID, Int>()
    private var syncTask: BukkitTask? = null

    fun start() {
        Bukkit.getPluginManager().registerEvents(this, plugin)
        syncTask = Bukkit.getScheduler().runTaskTimer(plugin, Runnable { syncAll() }, 1L, 1L)
    }

    fun stop() {
        syncTask?.cancel()
        syncTask = null
        anchors.values.forEach { if (it.isValid) it.remove() }
        anchors.clear()
        activeSessionCount.clear()
    }

    /** 再生セッション開始時に呼ぶ。参照カウントを増やし、マーカーが無ければ生成する。 */
    fun acquire(player: Player) {
        activeSessionCount.merge(player.uniqueId, 1, Int::plus)
        anchors.computeIfAbsent(player.uniqueId) { spawnAnchor(player) }
    }

    /** 再生セッション終了時に呼ぶ。参照カウントが0になったらマーカーを消去する。 */
    fun release(playerUuid: UUID) {
        val remaining = activeSessionCount.compute(playerUuid) { _, count -> ((count ?: 1) - 1).coerceAtLeast(0) }
        if (remaining == 0) {
            anchors.remove(playerUuid)?.let { if (it.isValid) it.remove() }
        }
    }

    /**
     * その都度Sound.Emitterとして使うEntityを返す。[acquire]を呼ばずに使われた場合
     * （念のための保険）は、参照カウント無しの一時的なマーカーとして生成する。
     */
    fun getOrCreateAnchor(player: Player): Entity {
        val existing = anchors[player.uniqueId]
        if (existing != null && existing.isValid) return existing
        return spawnAnchor(player).also { anchors[player.uniqueId] = it }
    }

    private fun spawnAnchor(player: Player): AreaEffectCloud =
        player.world.spawn(player.eyeLocation, AreaEffectCloud::class.java) { s ->
            s.isInvulnerable = true
            s.isSilent = true
            s.isPersistent = false
            s.setGravity(false)
        }

    private fun syncAll() {
        if (activeSessionCount.isEmpty()) return
        for ((uuid, count) in activeSessionCount) {
            if (count <= 0) continue
            val player = Bukkit.getPlayer(uuid) ?: continue
            val anchor = anchors[uuid] ?: continue
            if (!anchor.isValid) continue
            anchor.teleportAsync(player.eyeLocation)
        }
    }

    @EventHandler
    fun onQuit(event: PlayerQuitEvent) {
        val uuid = event.player.uniqueId
        activeSessionCount.remove(uuid)
        anchors.remove(uuid)?.let { if (it.isValid) it.remove() }
    }
}
