package com.github.sahyuya.oyasaiMusic.gui

import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerEditBookEvent
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.BookMeta
import org.bukkit.plugin.Plugin
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * 「URL入力には本と羽ペン（Book-and-Quill）を使用」という技術方針に基づく入力ヘルパー
 * （楽曲設定画面「参考URL登録」で使用する想定）。
 *
 * Book-and-Quillの編集UIはサーバー側から直接開くAPIが無く、クライアントが手に持った
 * WRITABLE_BOOKを右クリックすることでのみ開かれる。そのため以下のフローを取る:
 *   1. プレイヤーの手（現在選択中のホットバースロット）を一時退避し、案内文言入りの
 *      WRITABLE_BOOKを持たせる。
 *   2. プレイヤーがそれを右クリックして編集 → 「完了」を押すと [PlayerEditBookEvent] が
 *      発火するので、1ページ目のテキストをURLとして受け取る。
 *   3. 手を退避前の状態へ戻し、コールバックを呼ぶ（呼び出し側でGUIを再度開き直す想定）。
 *
 * 制限: プレイヤーが本を右クリックせずに手放したりログアウトした場合、完了を検知できない
 * （退避前アイテムを失う可能性がある）。今後タイムアウト処理や、手放し検知
 * （PlayerDropItemEvent等）での復元処理を追加検討。
 */
object BookQuillUrlInput {

    private val pending = ConcurrentHashMap<UUID, Pending>()
    private var listenerInstalled = false

    private class Pending(val slot: Int, val previousItem: ItemStack?, val onSubmit: (String) -> Unit)

    fun open(
        plugin: Plugin,
        player: Player,
        guideText: String = "参考URLを1ページ目に入力して「完了」を押してください。",
        onSubmit: (String) -> Unit,
    ) {
        installListenerOnce(plugin)

        val slot = player.inventory.heldItemSlot
        val previous = player.inventory.getItem(slot)
        pending[player.uniqueId] = Pending(slot, previous, onSubmit)

        val book = ItemStack(Material.WRITABLE_BOOK)
        book.editMeta { meta ->
            meta as BookMeta
            meta.addPage(guideText)
        }
        player.inventory.setItem(slot, book)
        player.sendMessage("§a本を右クリックして開き、URLを入力後「完了」を押してください。")
    }

    private fun installListenerOnce(plugin: Plugin) {
        if (listenerInstalled) return
        listenerInstalled = true
        Bukkit.getPluginManager().registerEvents(object : Listener {
            @EventHandler
            fun onEdit(event: PlayerEditBookEvent) {
                val player = event.player
                val session = pending.remove(player.uniqueId) ?: return

                player.inventory.setItem(session.slot, session.previousItem)

                // BookMeta#getPage(int)は1始まりのページ番号を取る旧来API（プレーンな文字列を返す）。
                val text = event.newBookMeta.getPage(1)?.trim().orEmpty()
                if (text.isNotEmpty()) session.onSubmit(text)
            }
        }, plugin)
    }
}