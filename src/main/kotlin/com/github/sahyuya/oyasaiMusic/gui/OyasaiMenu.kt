package com.github.sahyuya.oyasaiMusic.gui

import net.kyori.adventure.text.Component
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryCloseEvent
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.InventoryHolder

/**
 * 6×9 SPA構造（UI/UX設計書 1章）の各画面が実装するインターフェース。
 * 画面の実体は開くたびに新しいインスタンスとして生成され、[inventory] を
 * [MenuManager] が [org.bukkit.entity.HumanEntity.openInventory] へ渡す。
 *
 * 実装クラスは [GuiChrome.render] を呼び、左列ナビゲーション(slot 0,9,18,27,36)と
 * 下段メディアコントローラー(slot 45〜53)を必ず描画すること。
 * コンテンツ領域は5×8=40スロット（各行の1〜8列目, slot 1-8,10-17,19-26,28-35,37-44）。
 * 参照画像では一貫して先頭行(slot 1-8)が空欄だったため、一覧系画面では
 * [com.github.sahyuya.oyasaiMusic.gui.screens.SongListMenu.LIST_SLOTS] のように
 * 残り4行(32スロット)のみを使う運用にしている（詳細は本文の質問事項を参照）。
 */
interface OyasaiMenu {

    /** この画面を開いたプレイヤー。 */
    val viewer: Player

    /** 実際に開くInventory本体。 */
    val inventory: Inventory

    /**
     * クリックを処理する。[MenuManager] が呼び出し前に既に `event.isCancelled = true` を
     * 設定済みなので、アイテムを取り出させたい場合は明示的に `event.isCancelled = false` にすること
     * （現状どの画面もアイテムの持ち出しは想定していない）。
     */
    fun onClick(event: InventoryClickEvent)

    /** GUIを閉じた時点の処理（片付け等）。既定では何もしない。 */
    fun onClose(event: InventoryCloseEvent) {}

    /**
     * 現在の状態に基づき画面を再描画する（[MenuManager.refreshCurrent] から呼ばれる）。
     * 再生中/一時停止状態の変化等、非同期処理の完了を受けて表示を更新する必要がある画面は
     * 必ずこれをオーバーライドして自身のrender()相当を呼ぶこと。既定では何もしない。
     */
    fun refresh() {}
}

/**
 * [Inventory.getHolder] に格納し、[MenuManager] がクリックイベントから
 * どの [OyasaiMenu] インスタンスに委譲すべきかを判定するためのラッパー。
 */
class OyasaiMenuHolder(val menu: OyasaiMenu) : InventoryHolder {
    override fun getInventory(): Inventory = menu.inventory
}

/**
 * 54スロット(6×9)のSPA画面共通の基底クラス。Inventory生成と[OyasaiMenuHolder]の
 * 紐付けのみを担い、コンテンツ描画・クリック処理は各サブクラスに委ねる。
 */
abstract class BaseGridMenu(
    final override val viewer: Player,
    title: Component,
) : OyasaiMenu {
    // Kotlinではプロパティ初期化子の中で`this`を他オブジェクトのコンストラクタへ渡すこと自体は可能
    // （OyasaiMenuHolderはmenuの参照を保持するだけで、コンストラクタ内でmenuのメソッドを呼ばないため安全）。
    final override val inventory: Inventory = Bukkit.createInventory(OyasaiMenuHolder(this), 54, title)
}