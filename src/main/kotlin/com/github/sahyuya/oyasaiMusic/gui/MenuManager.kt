package com.github.sahyuya.oyasaiMusic.gui

import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryCloseEvent
import org.bukkit.event.inventory.InventoryDragEvent
import org.bukkit.plugin.Plugin
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * GUI全体のライフサイクル（開閉・履歴・クリックの委譲）を一元管理する。
 *
 * UI/UX設計書 1章:「戻る」ボタンは1階層のみ履歴を記憶するため、
 * 直前の画面1つだけを [previous] に保持する（スタックではない）。
 *
 * UI/UX設計書 9章:「携帯用プレイヤー」右クリックで前回開いていたGUIを復元するため、
 * [lastKnown] はGUIを閉じても消さずに保持し続ける（[current] は開いている間だけ）。
 */
class MenuManager(private val plugin: Plugin) : Listener {

    private val current = ConcurrentHashMap<UUID, OyasaiMenu>()
    private val previous = ConcurrentHashMap<UUID, OyasaiMenu>()
    private val lastKnown = ConcurrentHashMap<UUID, OyasaiMenu>()

    /** open()内部で画面切り替え中に発火するInventoryCloseEventを無視するためのフラグ。 */
    private val switching = ConcurrentHashMap.newKeySet<UUID>()

    /**
     * @param rememberAsPrevious trueの場合、直前に開いていた画面を「戻る」用に記憶する。
     *        「戻る」自体で呼ぶ場合や、メインメニューへ戻る等で履歴をリセットしたい遷移ではfalseにする。
     */
    fun open(player: Player, menu: OyasaiMenu, rememberAsPrevious: Boolean = true) {
        val existing = current[player.uniqueId]
        if (rememberAsPrevious && existing != null && existing !== menu) {
            previous[player.uniqueId] = existing
        }
        current[player.uniqueId] = menu
        lastKnown[player.uniqueId] = menu
        switching.add(player.uniqueId)
        player.openInventory(menu.inventory)
        Bukkit.getScheduler().runTask(plugin, Runnable { switching.remove(player.uniqueId) })
    }

    /** UI/UX設計書1章の「戻る」ボタン用。記憶している1階層前の画面が無ければ何もしない。 */
    fun openPrevious(player: Player) {
        val prev = previous.remove(player.uniqueId) ?: return
        open(player, prev, rememberAsPrevious = false)
    }

    /** UI/UX設計書9章「携帯用プレイヤー」右クリック用：GUIを閉じていても直前の画面を返す。 */
    fun lastKnownMenu(playerUuid: UUID): OyasaiMenu? = lastKnown[playerUuid]

    fun currentMenu(playerUuid: UUID): OyasaiMenu? = current[playerUuid]

    private fun closeTracking(playerUuid: UUID) {
        current.remove(playerUuid)
        previous.remove(playerUuid)
    }

    @EventHandler(priority = EventPriority.HIGH)
    fun onClick(event: InventoryClickEvent) {
        val holder = event.inventory.holder as? OyasaiMenuHolder ?: return
        // GUI欄・プレイヤーインベントリ側どちらのクリックでも既定では持ち出し不可にする。
        // 画面側が明示的にfalseへ戻さない限りアイテムの移動は起きない。
        event.isCancelled = true
        holder.menu.onClick(event)
    }

    @EventHandler
    fun onDrag(event: InventoryDragEvent) {
        if (event.inventory.holder is OyasaiMenuHolder) event.isCancelled = true
    }

    @EventHandler
    fun onClose(event: InventoryCloseEvent) {
        val holder = event.inventory.holder as? OyasaiMenuHolder ?: return
        val player = event.player as? Player ?: return
        if (switching.contains(player.uniqueId)) return // 画面遷移による一時的なcloseは無視
        holder.menu.onClose(event)
        closeTracking(player.uniqueId)
    }
}