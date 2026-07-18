package com.github.sahyuya.oyasaiMusic.gui

import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryCloseEvent
import org.bukkit.event.inventory.InventoryDragEvent
import org.bukkit.event.player.PlayerQuitEvent
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
 *
 * 【不具合修正】「戻る」ボタンが効かない件について:
 * 当初は `InventoryCloseEvent` のたびに [current]/[previous] を破棄していたが、
 * `AnvilTextInputSession` や `BookQuillUrlInput` はMenuManagerを介さず直接
 * `player.openInventory(...)` を呼ぶため、その際にも（元のGUIが暗黙的に閉じられることで）
 * `InventoryCloseEvent` が発生してしまい、金床/本入力を1回使っただけで履歴が消えて
 * 「戻る」が効かなくなっていた。この根本原因への対応として、GUIを閉じた程度では
 * [current]/[previous] を破棄しないようにし、次に [open] が呼ばれた時点で自然に
 * 上書きされる設計に変更した。メモリ上の掃除は、プレイヤーが実際にサーバーを
 * 離れたとき([onPlayerQuit])にのみ行う。
 */
class MenuManager(private val plugin: Plugin) : Listener {

    private val current = ConcurrentHashMap<UUID, OyasaiMenu>()
    private val previous = ConcurrentHashMap<UUID, OyasaiMenu>()
    private val lastKnown = ConcurrentHashMap<UUID, OyasaiMenu>()

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
        player.openInventory(menu.inventory)
    }

    /** UI/UX設計書1章の「戻る」ボタン用。記憶している1階層前の画面が無ければ何もしない。 */
    fun openPrevious(player: Player) {
        val prev = previous.remove(player.uniqueId) ?: return
        open(player, prev, rememberAsPrevious = false)
    }

    /** UI/UX設計書9章「携帯用プレイヤー」右クリック用：GUIを閉じていても直前の画面を返す。 */
    fun lastKnownMenu(playerUuid: UUID): OyasaiMenu? = lastKnown[playerUuid]

    fun currentMenu(playerUuid: UUID): OyasaiMenu? = current[playerUuid]

    /**
     * 現在開いている画面を再描画する（例: 再生中/一時停止状態が変わった、いいねやフォローの
     * 結果が確定した等、非同期処理の完了を受けて表示を更新したい場合に呼ぶ）。
     * プレイヤーがGUIを閉じている場合は何もしない。
     */
    fun refreshCurrent(playerUuid: UUID) {
        current[playerUuid]?.refresh()
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
        holder.menu.onClose(event)
        // NOTE: ここでは current/previous を破棄しない（上記クラスコメント参照）。
    }

    @EventHandler
    fun onPlayerQuit(event: PlayerQuitEvent) {
        val uuid = event.player.uniqueId
        current.remove(uuid)
        previous.remove(uuid)
        lastKnown.remove(uuid)
    }
}