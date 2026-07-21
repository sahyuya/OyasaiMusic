package com.github.sahyuya.oyasaiMusic

import com.github.sahyuya.oyasaiMusic.gui.AmbientRecordSettingsMenu
import org.bukkit.GameMode
import org.bukkit.Material
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.block.Action
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.block.BlockRedstoneEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.inventory.EquipmentSlot

/**
 * UI/UX設計書9章「環境BGM用レコード」のインタラクション処理。
 *
 * このアイテムはバニラの音楽レコードではなく独自形式の楽曲データを指すため、ジュークボックスへの
 * 設置・取り出しはバニラの挿入処理をキャンセルしたうえで独自に模擬している
 * （[com.github.sahyuya.oyasaiMusic.audio.AmbientPlaybackRegistry]参照。要確認: 見た目上
 * ジュークボックスの中身は常に空のままになる点は妥協点）。
 */
class PhysicalRecordListener(private val plugin: OyasaiMusic) : Listener {

    @EventHandler
    fun onInteract(event: PlayerInteractEvent) {
        if (event.hand != EquipmentSlot.HAND) return
        val player = event.player
        val clickedBlock = event.clickedBlock

        // ジュークボックスからの取り出し（手ぶらで右クリック）。
        if (event.action == Action.RIGHT_CLICK_BLOCK && clickedBlock?.type == Material.JUKEBOX) {
            val heldItem = event.item
            if (heldItem == null || heldItem.type == Material.AIR) {
                val entry = plugin.ambientPlaybackRegistry.entryAt(clickedBlock.location) ?: return
                event.isCancelled = true
                plugin.ambientPlaybackRegistry.unregister(clickedBlock.location)
                val songId = entry.song.id ?: return
                val authorName = org.bukkit.Bukkit.getOfflinePlayer(entry.song.authorUuid).name ?: "不明"
                val material = Material.matchMaterial(entry.song.recordMaterial) ?: Material.MUSIC_DISC_13
                var ejected = PhysicalRecordItem.create(plugin, material, songId, entry.song.title, authorName)
                ejected = PhysicalRecordItem.withRange(plugin, ejected, entry.range)
                ejected = PhysicalRecordItem.withTrigger(plugin, ejected, entry.trigger)
                ejected = PhysicalRecordItem.withLoop(plugin, ejected, entry.loop)
                clickedBlock.world.dropItemNaturally(clickedBlock.location.clone().add(0.5, 1.0, 0.5), ejected)
                player.sendMessage("§a環境BGMを停止し、レコードを取り出しました。")
                return
            }
        }

        val item = event.item ?: return
        if (!PhysicalRecordItem.isRecordItem(plugin, item)) return

        // ジュークボックスへの設置（レコードを持って右クリック）。
        if (event.action == Action.RIGHT_CLICK_BLOCK && clickedBlock?.type == Material.JUKEBOX && !player.isSneaking) {
            event.isCancelled = true
            val songId = PhysicalRecordItem.songId(plugin, item) ?: return
            val song = plugin.songRepository.findById(songId)
            if (song == null) {
                player.sendMessage("§c楽曲データが見つかりません（削除された可能性があります）。")
                return
            }
            val range = PhysicalRecordItem.range(plugin, item)
            val trigger = PhysicalRecordItem.trigger(plugin, item)
            val loop = PhysicalRecordItem.loop(plugin, item)
            plugin.ambientPlaybackRegistry.register(clickedBlock.location, song, range, trigger, loop)
            player.sendMessage(
                "§a環境BGMを設置しました: ${song.title} " +
                        "(範囲:${range.label} / トリガー:${trigger.label} / ループ:${if (loop) "ON" else "OFF"})",
            )
            if (player.gameMode != GameMode.CREATIVE) {
                item.amount -= 1
            }
            return
        }

        // Shift+右クリック（ジュークボックス以外、または何もない場所）→ 環境BGM設定画面を開く。
        if (player.isSneaking && (event.action == Action.RIGHT_CLICK_AIR || event.action == Action.RIGHT_CLICK_BLOCK)) {
            if (clickedBlock?.type == Material.JUKEBOX) return // ジュークボックスへの設置操作を優先
            event.isCancelled = true
            plugin.menuManager.open(
                player,
                AmbientRecordSettingsMenu(plugin, plugin.menuManager, player, player.inventory.heldItemSlot),
                rememberAsPrevious = false,
            )
        }
    }

    @EventHandler
    fun onRedstone(event: BlockRedstoneEvent) {
        if (event.block.type != Material.JUKEBOX) return
        plugin.ambientPlaybackRegistry.onRedstoneChange(event.block.location, event.newCurrent > 0)
    }

    @EventHandler
    fun onBlockBreak(event: BlockBreakEvent) {
        if (event.block.type != Material.JUKEBOX) return
        plugin.ambientPlaybackRegistry.unregister(event.block.location)
    }
}