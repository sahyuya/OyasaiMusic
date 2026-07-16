package com.github.sahyuya.oyasaiMusic.gui

import net.kyori.adventure.text.Component
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryCloseEvent
import org.bukkit.event.inventory.PrepareAnvilEvent
import org.bukkit.inventory.AnvilInventory
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.MenuType
import org.bukkit.plugin.Plugin
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * 「テキスト入力にはAnvilGUIライブラリ不使用、Paper純正のMenuType.ANVIL APIを使用」という
 * 技術方針に基づくテキスト入力ヘルパー（題名検索・作者名検索・楽曲タイトル変更・
 * プレイリスト名変更等で使用する想定）。
 *
 * 実装方針:
 *   1. [MenuType.ANVIL] で金床画面を開き、1st slotにリネーム対象のアイテムを設置する。
 *   2. [PrepareAnvilEvent] を監視し、入力中のテキスト(AnvilInventory.getRenameText)を都度キャッシュする。
 *   3. 3rd slot(結果スロット, rawSlot=2)がクリックされた時点、またはGUIが閉じられた時点の
 *      最新テキストを確定値として [onSubmit] へ渡す。空文字列の場合はキャンセル扱いとして呼ばない。
 *
 * 注意: 本サンドボック環境にはPaper APIの実jarが無くコンパイル確認ができていない。
 * [MenuType.ANVIL.create] や AnvilInventory.getRenameText のシグネチャはPaper 1.21系の
 * 公開APIに基づく想定のため、ビルド時に差異があればメソッド名を調整してほしい。
 */
class AnvilTextInput private constructor(
    private val onSubmit: (String) -> Unit,
) {
    private var latestText: String = ""
    private var submitted = false

    private fun updateText(text: String) {
        latestText = text
    }

    private fun confirm() {
        if (submitted) return
        submitted = true
        val text = latestText.trim()
        if (text.isNotEmpty()) onSubmit(text)
    }

    companion object {
        private val sessions = ConcurrentHashMap<UUID, AnvilTextInput>()
        private val listenerInstalledFor = ConcurrentHashMap.newKeySet<Plugin>()

        /**
         * @param initialText 金床の1st slotに置くアイテムの初期表示名（プレースホルダー文言）
         * @param itemMaterial 1st slotに置くアイテムの素材（既定: PAPER）
         * @param onSubmit 確定した文字列を受け取るコールバック（メインスレッドで呼ばれる）
         */
        fun open(
            plugin: Plugin,
            player: Player,
            title: Component,
            initialText: String = "",
            itemMaterial: Material = Material.PAPER,
            onSubmit: (String) -> Unit,
        ) {
            installListenerOnce(plugin)
            val session = AnvilTextInput(onSubmit)
            session.updateText(initialText)
            sessions[player.uniqueId] = session

            val view = MenuType.ANVIL.create(player, title)
            if (view == null) {
                player.sendMessage("§c金床画面を開けませんでした。")
                sessions.remove(player.uniqueId)
                return
            }
            val anvilInventory = view.topInventory as AnvilInventory
            val item = ItemStack(itemMaterial)
            if (initialText.isNotEmpty()) {
                item.editMeta { it.displayName(Component.text(initialText)) }
            }
            anvilInventory.setItem(0, item)
            player.openInventory(view)
        }

        private fun installListenerOnce(plugin: Plugin) {
            if (!listenerInstalledFor.add(plugin)) return
            Bukkit.getPluginManager().registerEvents(object : Listener {
                @EventHandler
                fun onPrepare(event: PrepareAnvilEvent) {
                    val viewer = event.view.player as? Player ?: return
                    val session = sessions[viewer.uniqueId] ?: return
                    session.updateText(event.view.renameText ?: session.latestText)
                }

                @EventHandler
                fun onClick(event: InventoryClickEvent) {
                    val viewer = event.whoClicked as? Player ?: return
                    val session = sessions[viewer.uniqueId] ?: return
                    if (event.inventory !is AnvilInventory) return
                    if (event.rawSlot != 2) return // 結果スロット(確定ボタン)
                    event.isCancelled = true
                    session.confirm()
                    viewer.closeInventory()
                }

                @EventHandler
                fun onClose(event: InventoryCloseEvent) {
                    val viewer = event.player as? Player ?: return
                    val session = sessions.remove(viewer.uniqueId) ?: return
                    // 未確定のままGUIを閉じた場合も、入力途中のテキストを確定値として扱う
                    // （空欄のまま閉じればonSubmitは呼ばれない）。
                    session.confirm()
                }
            }, plugin)
        }
    }
}