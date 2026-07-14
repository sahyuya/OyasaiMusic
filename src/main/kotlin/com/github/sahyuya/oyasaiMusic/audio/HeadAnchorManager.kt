package com.github.sahyuya.oyasaiMusic.audio

import org.bukkit.entity.ArmorStand
import org.bukkit.entity.Entity
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.plugin.Plugin
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * [PlaybackMode.DEFAULT]（旧Entity Emitter方式）用の、プレイヤーと一体化したSound.Emitterを管理する。
 *
 * ## 経緯
 * 当初は「見えないマーカーエンティティを毎tickプレイヤーの目線位置へテレポートさせる」方式を
 * 検討したが、リスナー数が増えるほど毎tickのテレポート処理が積み重なり負荷が増える。
 *
 * そこで、肩乗りオウムのように*プレイヤーへ完全に追従する*仕組みとして、Bukkit標準の
 * 騎乗(vehicle/passenger)機構を採用した。**不可視・当たり判定なし(marker)の[ArmorStand]を
 * プレイヤーに騎乗させる**ことで、追従処理はサーバーが通常のエンティティ同期の一部として
 * 自動的に行い、本プラグイン側で毎tickの追従処理を持つ必要が無くなる
 * （バニラの肩乗りオウム自体はNBTのみで管理されサーバー側にEntityとして存在しないため
 * `Sound.Emitter`として直接参照できず採用できなかった）。
 *
 * ArmorStandを選んだ理由:
 *   - 鳴き声等のアンビエントサウンドを一切持たない（本物のオウムだと鳴き声の抑制が必要になる）
 *   - `isMarker=true`で当たり判定を消せるため他プレイヤーの操作を一切妨げない
 *   - 無敵化・非表示化が標準APIで確実に行える
 *
 * 騎乗中に死亡等で外れた場合は、次にこのマーカーが必要になったタイミング（＝次の音符再生時）で
 * [getOrCreateAnchor] が自動的に再生成・再騎乗させる（自己修復方式のため、死亡イベントを
 * 個別に監視する必要が無い）。
 */
class HeadAnchorManager(private val plugin: Plugin) : Listener {

    private val anchors = ConcurrentHashMap<UUID, ArmorStand>()

    fun start() {
        org.bukkit.Bukkit.getPluginManager().registerEvents(this, plugin)
    }

    fun stop() {
        anchors.values.forEach { if (it.isValid) it.remove() }
        anchors.clear()
    }

    /**
     * プレイヤーに騎乗済みの有効なマーカーを返す。無い・無効・騎乗が外れている場合は
     * 新しく生成して騎乗し直す（自己修復）。
     */
    fun getOrCreateAnchor(player: Player): Entity {
        val existing = anchors[player.uniqueId]
        if (existing != null && existing.isValid && existing.vehicle?.uniqueId == player.uniqueId) {
            return existing
        }
        return mountNewAnchor(player)
    }

    private fun mountNewAnchor(player: Player): ArmorStand {
        anchors.remove(player.uniqueId)?.let { if (it.isValid) it.remove() }

        val stand = player.world.spawn(player.location, ArmorStand::class.java) { s ->
            s.isVisible = false
            s.isMarker = true
            s.isInvulnerable = true
            s.isSilent = true
            s.isPersistent = false
            s.setGravity(false)
            s.setBasePlate(false)
            s.setCanPickupItems(false)
        }
        player.addPassenger(stand)
        anchors[player.uniqueId] = stand
        return stand
    }

    @EventHandler
    fun onQuit(event: PlayerQuitEvent) {
        anchors.remove(event.player.uniqueId)?.let { if (it.isValid) it.remove() }
    }
}
