package com.github.sahyuya.oyasaiMusic.gui

import com.github.sahyuya.oyasaiMusic.OyasaiMusic
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryCloseEvent
import org.bukkit.event.inventory.InventoryDragEvent
import org.bukkit.event.player.PlayerQuitEvent
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * GUI全体のライフサイクル（開閉・履歴・クリックの委譲）を一元管理する。
 *
 * 【履歴管理（サヒュヤ氏の指示で改訂）】
 * 当初は「1階層のみ」記憶する単純な`previous`参照だったが、
 * 楽曲一覧→楽曲詳細→楽曲設定のように複数階層を辿った場合でも「戻る」で
 * 最初の画面まで戻れるようにしてほしいとの指示を受け、[history] をスタックに変更した。
 * また、何らかの理由でスタックが空になっていた場合（＝履歴を見失った場合）は、
 * 「戻る」を押しても何も起きない事態を避けるため、トップメニュー([MainMenuScreen])へ
 * フォールバックする。
 *
 * UI/UX設計書 9章:「携帯用プレイヤー」右クリックで前回開いていたGUIを復元するため、
 * [lastKnown] はGUIを閉じても消さずに保持し続ける（[current] は開いている間だけ）。
 *
 * 【不具合修正】「戻る」ボタンが効かない件について:
 * `AnvilTextInputSession` や `BookQuillUrlInput` はMenuManagerを介さず直接
 * `player.openInventory(...)` を呼ぶため、その際にも（元のGUIが暗黙的に閉じられることで）
 * `InventoryCloseEvent` が発生する。これによって履歴が失われないよう、GUIを閉じた程度では
 * [current]/[history] を破棄しない設計にしている（掃除はプレイヤー退出時のみ）。
 */
class MenuManager(private val plugin: OyasaiMusic) : Listener {

    companion object {
        /** GUIクリックの連打対策（サヒュヤ氏の指示: 100msのクールタイム）。 */
        private const val CLICK_COOLDOWN_MS = 100L
    }

    private val current = ConcurrentHashMap<UUID, OyasaiMenu>()
    private val history = ConcurrentHashMap<UUID, MutableList<OyasaiMenu>>()
    private val lastKnown = ConcurrentHashMap<UUID, OyasaiMenu>()
    private val lastClickMillis = ConcurrentHashMap<UUID, Long>()

    /**
     * @param rememberAsPrevious trueの場合、直前に開いていた画面を履歴スタックへ積む。
     *        「戻る」自体で呼ぶ場合や、メインメニューへ戻る等で履歴をリセットしたい遷移ではfalseにする。
     */
    fun open(player: Player, menu: OyasaiMenu, rememberAsPrevious: Boolean = true) {
        val existing = current[player.uniqueId]
        if (rememberAsPrevious && existing != null && existing !== menu) {
            history.getOrPut(player.uniqueId) { mutableListOf() }.add(existing)
        }
        current[player.uniqueId] = menu
        lastKnown[player.uniqueId] = menu
        player.openInventory(menu.inventory)
    }

    /**
     * UI/UX設計書1章の「戻る」ボタン用。履歴スタックから1つ前の画面を取り出して開く。
     * 履歴が空（＝見失った）場合はトップメニューへフォールバックする。
     */
    fun openPrevious(player: Player) {
        val stack = history[player.uniqueId]
        val prev = stack?.removeLastOrNull()
        if (prev == null) {
            open(player, MainMenuScreen(plugin, this, player), rememberAsPrevious = false)
            return
        }
        current[player.uniqueId] = prev
        lastKnown[player.uniqueId] = prev
        player.openInventory(prev.inventory)
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

        val player = event.whoClicked as? Player ?: return
        val now = System.currentTimeMillis()
        val last = lastClickMillis[player.uniqueId] ?: 0L
        if (now - last < CLICK_COOLDOWN_MS) return // 連打対策: クールタイム中は無視する
        lastClickMillis[player.uniqueId] = now

        holder.menu.onClick(event)
    }

    @EventHandler
    fun onDrag(event: InventoryDragEvent) {
        if (event.inventory.holder is OyasaiMenuHolder) event.isCancelled = true
    }

    @EventHandler
    fun onClose(event: InventoryCloseEvent) {
        val holder = event.inventory.holder as? OyasaiMenuHolder ?: return
        holder.menu.onClose(event)
        // NOTE: ここでは current/history を破棄しない（クラスコメント参照）。
    }

    @EventHandler
    fun onPlayerQuit(event: PlayerQuitEvent) {
        val uuid = event.player.uniqueId
        current.remove(uuid)
        history.remove(uuid)
        lastKnown.remove(uuid)
        lastClickMillis.remove(uuid)
    }
}