package com.github.sahyuya.oyasaiMusic.gui

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryCloseEvent
import org.bukkit.event.inventory.InventoryDragEvent
import org.bukkit.event.inventory.PrepareAnvilEvent
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.MenuType
import org.bukkit.inventory.view.AnvilView
import org.bukkit.persistence.PersistentDataType
import org.bukkit.plugin.Plugin
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * 「テキスト入力にはAnvilGUIライブラリ不使用、Paper純正のMenuType.ANVIL APIを使用」という
 * 技術方針に基づくテキスト入力ヘルパー（題名検索・作者名検索・楽曲タイトル変更・
 * プレイリスト名変更等で使用）。
 *
 * サヒュヤ氏から提供された動作実績のある実装例(SocialLikesAnvilInput.kt)を全面的に参考にし、
 * 当初の自作実装から以下を修正している（根本原因の特定含む）:
 *
 *   - 【根本原因】入力中のリネームテキストは [org.bukkit.inventory.AnvilInventory] ではなく
 *     [AnvilView]（org.bukkit.inventory.view.AnvilView）が持つプロパティだった。
 *     当初 `(event.inventory as? AnvilInventory)` のようにインベントリ側からキャストしており、
 *     これが常に失敗して入力テキストを取得できず、確定処理(onSubmit)が呼ばれていなかった。
 *   - セッション対象インベントリへのクリックは常に全てキャンセルし、結果アイテムを
 *     プレイヤーが実際に受け取ることが無いようにする（以前の実装はキャンセルに失敗する/
 *     キャンセルをやめる変更をしており、紙アイテムを取得できてしまっていた）。
 *   - `PrepareAnvilEvent` のたびに結果スロットのアイテムを、入力中テキストを表示名に
 *     埋め込んだプレースホルダーとして明示的に描画し直す（`event.result` を直接設定）。
 *   - 結果スロットをクリックした時点のテキストが空なら何もせず(表示だけ更新して)待機し、
 *     テキストがある場合のみセッションを破棄してGUIを閉じ、次tickでonSubmitを呼ぶ。
 *   - 修復コスト・経験値レベル制限を無効化し（`setRepairCost(0)` 等）、プレイヤーが
 *     レベル不足で結果を受け取れない事態を防ぐ。
 *   - PDCタグでセッション由来のアイテムを識別し、GUIを閉じた1tick後にプレイヤーの
 *     実インベントリ/カーソルへ漏れ出していないか確認して掃除する安全策も追加している。
 */
object AnvilTextInput : Listener {

    private const val OUTPUT_SLOT = 2

    private val legacySerializer = LegacyComponentSerializer.legacySection()
    private val plainTextSerializer = PlainTextComponentSerializer.plainText()

    private var sessionKey: NamespacedKey? = null
    private val sessions = ConcurrentHashMap<UUID, Session>()
    private var listenerInstalledFor: Plugin? = null

    private data class Session(
        val id: String,
        val plugin: Plugin,
        val inventory: Inventory,
        val placeholderItem: ItemStack,
        val onSubmit: (String) -> Unit,
    )

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
        val key = sessionKey ?: NamespacedKey(plugin, "anvil_input_session").also { sessionKey = it }

        val view = MenuType.ANVIL.builder()
            .title(title)
            .checkReachable(false)
            .build(player)
        configure(view)

        val placeholder = ItemStack(itemMaterial)
        if (initialText.isNotEmpty()) {
            placeholder.editMeta { it.displayName(Component.text(initialText)) }
        }

        val session = Session(UUID.randomUUID().toString(), plugin, view.topInventory, placeholder, onSubmit)
        sessions[player.uniqueId] = session

        view.topInventory.setItem(0, markSessionItem(placeholder.clone(), session.id, key))
        view.topInventory.setItem(OUTPUT_SLOT, createResultItem(session, initialText, key))
        view.open()
    }

    @EventHandler
    fun onPrepareAnvil(event: PrepareAnvilEvent) {
        val player = event.view.player as? Player ?: return
        val session = sessions[player.uniqueId] ?: return
        if (event.inventory != session.inventory) return
        val key = sessionKey ?: return
        val view = event.view as? AnvilView ?: return

        configure(view)
        event.result = createResultItem(session, view.renameText, key)
    }

    @EventHandler
    fun onInventoryClick(event: InventoryClickEvent) {
        val player = event.whoClicked as? Player ?: return
        val view = event.view as? AnvilView ?: return
        val session = sessions[player.uniqueId] ?: return
        if (view.topInventory != session.inventory) return

        // このインベントリ内のクリックは常に全てキャンセルする(アイテムを実際に持ち出させない)。
        event.isCancelled = true
        if (event.rawSlot != OUTPUT_SLOT) return

        val key = sessionKey ?: return
        val text = normalize(view.renameText)
        if (text.isBlank()) {
            // まだ何も入力されていない状態でのクリックは無視し、表示だけ更新して待機する。
            view.topInventory.setItem(OUTPUT_SLOT, createResultItem(session, text, key))
            return
        }

        sessions.remove(player.uniqueId)
        session.inventory.clear()
        player.closeInventory()
        Bukkit.getScheduler().runTask(session.plugin, Runnable { session.onSubmit(text) })
    }

    @EventHandler
    fun onInventoryDrag(event: InventoryDragEvent) {
        val player = event.whoClicked as? Player ?: return
        val session = sessions[player.uniqueId] ?: return
        if (event.view.topInventory != session.inventory) return
        event.isCancelled = true
    }

    @EventHandler
    fun onInventoryClose(event: InventoryCloseEvent) {
        val player = event.player as? Player ?: return
        val session = sessions[player.uniqueId] ?: return
        if (event.view.topInventory != session.inventory) return

        sessions.remove(player.uniqueId)
        session.inventory.clear()
        val key = sessionKey ?: return
        // 何らかの理由でアイテムが実インベントリ/カーソルへ漏れ出していた場合の掃除
        // （クローズ処理の1tick後に確認する。session.pluginは登録元プラグインのため常に有効）。
        Bukkit.getScheduler().runTask(session.plugin, Runnable { purgeLeakedSessionItems(player, session.id, key) })
    }

    private fun configure(view: AnvilView) {
        view.setRepairCost(0)
        view.setRepairItemCountCost(0)
        view.setMaximumRepairCost(Int.MAX_VALUE)
        view.bypassEnchantmentLevelRestriction(true)
    }

    private fun createResultItem(session: Session, text: String?, key: NamespacedKey): ItemStack {
        val item = session.placeholderItem.clone()
        val normalized = normalize(text)
        if (normalized.isNotBlank()) {
            item.editMeta { it.displayName(Component.text(normalized)) }
        }
        return markSessionItem(item, session.id, key)
    }

    private fun normalize(text: String?): String =
        plainTextSerializer.serialize(legacySerializer.deserialize(text ?: "")).trim()

    private fun markSessionItem(item: ItemStack, sessionId: String, key: NamespacedKey): ItemStack {
        item.editMeta { it.persistentDataContainer.set(key, PersistentDataType.STRING, sessionId) }
        return item
    }

    private fun isSessionItem(item: ItemStack?, sessionId: String, key: NamespacedKey): Boolean {
        if (item == null || item.type == Material.AIR) return false
        return item.itemMeta?.persistentDataContainer?.get(key, PersistentDataType.STRING) == sessionId
    }

    private fun purgeLeakedSessionItems(player: Player, sessionId: String, key: NamespacedKey) {
        val inventory = player.inventory
        for (slot in 0 until inventory.size) {
            if (isSessionItem(inventory.getItem(slot), sessionId, key)) {
                inventory.setItem(slot, null)
            }
        }
        if (isSessionItem(player.itemOnCursor, sessionId, key)) {
            player.setItemOnCursor(ItemStack(Material.AIR))
        }
    }

    private fun installListenerOnce(plugin: Plugin) {
        if (listenerInstalledFor === plugin) return
        listenerInstalledFor = plugin
        Bukkit.getPluginManager().registerEvents(this, plugin)
    }
}