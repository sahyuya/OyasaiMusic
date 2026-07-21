package com.github.sahyuya.oyasaiMusic.gui

import com.github.sahyuya.oyasaiMusic.OyasaiMusic
import com.github.sahyuya.oyasaiMusic.model.Playlist
import com.github.sahyuya.oyasaiMusic.util.BedrockUtil
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.event.inventory.ClickType
import org.bukkit.event.inventory.InventoryClickEvent

/**
 * ④ お気に入り♪プレイリスト一覧画面（左列緑タブ、UI/UX設計書 2章、参照画像4枚目）。
 * 先頭に固定で「お気に入り」（[com.github.sahyuya.oyasaiMusic.db.SocialRepository]のfavoritesテーブルを
 * 使う特別な擬似プレイリスト）、続けてプレイリスト一覧（[com.github.sahyuya.oyasaiMusic.db.PlaylistRepository]）、
 * 末尾に「新規作成」ボタンを表示する。
 *
 * 背景装飾は使わない（サヒュヤ氏の指示: 緑板ガラス枠を廃止し、全楽曲一覧等と同じ
 * 5×8フル表示・左上(slot1)から詰めて並べる表示に統一）。
 *
 * クリック動作はUI/UX設計書 5章「お気に入り・プレイリスト (リスト画面)」に準拠:
 *   左クリック=詳細を開く＆自動再生 / Shift+左=共有 / 右クリック=名前変更 / Shift+右=削除(要確認)
 * 「お気に入り」自体は名前変更・削除ができないため、左クリック以外は無効化する。
 *
 * 【不具合修正】プレイリスト名の変更後にGUIへ戻らず閉じてしまう件について:
 * `AnvilTextInputSession` はMenuManagerを介さず直接GUIを閉じるため、その完了コールバック内で
 * 単に画面の中身を再描画([render])するだけでは、既に閉じられてしまったGUIを
 * プレイヤーへ再表示することにはならない。[reload] の最後で必ず
 * `menuManager.open(viewer, this, false)` を呼び、Anvil入力を経由したかどうかに関わらず
 * 確実にGUIが表示された状態に戻るようにしている。
 */
class FavoritesPlaylistsScreen(
    private val plugin: OyasaiMusic,
    private val menuManager: MenuManager,
    viewer: Player,
) : BaseGridMenu(viewer, Component.text("お気に入り♪プレイリスト")) {

    companion object {
        // サヒュヤ氏の指示: 5×8フル(40スロット)、slot1(左上)から詰めて表示する。
        val SLOTS: List<Int> = ContentGrid.SLOTS
        private const val FAVORITES_INDEX = 0 // SLOTS[0] は常に「お気に入り」固定
    }

    private var playlists: List<Playlist> = emptyList()
    private var pendingDeletePlaylistId: Long? = null

    init { reload() }

    override fun refresh() = reload()

    private fun reload() {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, Runnable {
            val list = plugin.playlistRepository.listByOwner(viewer.uniqueId)
            val favoriteCount = plugin.socialRepository.listFavoriteSongIds(viewer.uniqueId).size
            Bukkit.getScheduler().runTask(plugin, Runnable {
                playlists = list
                render(favoriteCount)
                // Anvil入力等を経由してGUIが一旦閉じられていた場合でも確実に再表示する。
                menuManager.open(viewer, this, rememberAsPrevious = false)
            })
        })
    }

    private fun render(favoriteCount: Int = -1) {
        val state = plugin.controllerStateService.stateFor(viewer.uniqueId)
        GuiChrome.render(
            inventory, NavTab.FAVORITES_PLAYLISTS, state, sortLabel = "-",
            viewer = viewer, plugin = plugin, actionModeCategory = ActionModeCategory.PLAYLIST_LIST,
        )

        inventory.setItem(SLOTS[FAVORITES_INDEX], favoritesIcon(favoriteCount))

        playlists.forEachIndexed { i, playlist ->
            val slotIndex = FAVORITES_INDEX + 1 + i
            if (slotIndex < SLOTS.size) inventory.setItem(SLOTS[slotIndex], playlistIcon(playlist))
        }

        val createIndex = FAVORITES_INDEX + 1 + playlists.size
        if (createIndex < SLOTS.size) {
            inventory.setItem(
                SLOTS[createIndex],
                GuiItemBuilder(Material.WRITABLE_BOOK)
                    .name(Component.text("+ 新規プレイリスト作成", NamedTextColor.GREEN))
                    .build(),
            )
        }
    }

    private fun favoritesIcon(favoriteCount: Int) = GuiItemBuilder(Material.NETHER_STAR)
        .name(Component.text("お気に入り", NamedTextColor.LIGHT_PURPLE))
        .lore(
            if (favoriteCount >= 0) Component.text("$favoriteCount 曲", NamedTextColor.GRAY) else Component.text("読み込み中...", NamedTextColor.GRAY),
            Component.text("クリックで開く", NamedTextColor.DARK_GRAY),
        )
        .build()

    private fun playlistIcon(playlist: Playlist): org.bukkit.inventory.ItemStack {
        val confirming = pendingDeletePlaylistId == playlist.id
        val prefix = plugin.config.getString("bedrock.name-prefix", ".") ?: "."
        val lore = mutableListOf<Component>(Component.text("${playlist.songCount} 曲", NamedTextColor.GRAY))
        lore += ActionLoreBuilder.build(viewer, prefix, ActionModeCategory.PLAYLIST_LIST, "開く", "共有", "名前変更", "削除")
        if (confirming) lore += Component.text("もう一度Shift+右クリックで削除確定", NamedTextColor.RED)

        return GuiItemBuilder(Material.CHISELED_BOOKSHELF)
            .name(Component.text(playlist.name, NamedTextColor.YELLOW))
            .lore(lore)
            .glint(confirming)
            .build()
    }

    override fun onClick(event: InventoryClickEvent) {
        val slot = event.rawSlot
        if (NavTabRouter.handle(slot, NavTab.FAVORITES_PLAYLISTS, ActionModeCategory.PLAYLIST_LIST, plugin, menuManager, viewer)) return
        if (plugin.playbackController.handleControllerClick(slot, viewer)) return

        val index = SLOTS.indexOf(slot)
        if (index == -1) return

        if (index == FAVORITES_INDEX) {
            pendingDeletePlaylistId = null
            menuManager.open(viewer, PlaylistDetailScreen.forFavorites(plugin, menuManager, viewer))
            return
        }

        val playlistIndex = index - FAVORITES_INDEX - 1
        val playlist = playlists.getOrNull(playlistIndex)
        if (playlist == null) {
            if (index == FAVORITES_INDEX + 1 + playlists.size) createPlaylist()
            return
        }
        if (playlist.id != pendingDeletePlaylistId) pendingDeletePlaylistId = null

        val prefix = plugin.config.getString("bedrock.name-prefix", ".") ?: "."
        val isBedrock = BedrockUtil.isBedrock(viewer, prefix)
        val action = if (isBedrock) BedrockActionModeService.get(viewer.uniqueId, ActionModeCategory.PLAYLIST_LIST) else when (event.click) {
            ClickType.SHIFT_LEFT -> ActionMode.SECONDARY
            ClickType.RIGHT -> ActionMode.TERTIARY
            ClickType.SHIFT_RIGHT -> ActionMode.QUATERNARY
            else -> ActionMode.PRIMARY
        }
        when (action) {
            ActionMode.PRIMARY -> menuManager.open(viewer, PlaylistDetailScreen.forPlaylist(plugin, menuManager, viewer, playlist))
            ActionMode.SECONDARY -> sharePlaylist(playlist)
            ActionMode.TERTIARY -> renamePlaylist(playlist)
            ActionMode.QUATERNARY -> confirmOrDeletePlaylist(playlist)
        }
    }

    private fun createPlaylist() {
        AnvilTextInputSession.open(plugin, viewer, Component.text("新しいプレイリスト名")) { name ->
            Bukkit.getScheduler().runTaskAsynchronously(plugin, Runnable {
                plugin.playlistRepository.create(viewer.uniqueId, name)
                Bukkit.getScheduler().runTask(plugin, Runnable {
                    viewer.sendMessage("§aプレイリスト「$name」を作成しました。")
                    reload()
                })
            })
        }
    }

    private fun renamePlaylist(playlist: Playlist) {
        AnvilTextInputSession.open(plugin, viewer, Component.text("プレイリスト名を変更"), initialText = playlist.name) { newName ->
            Bukkit.getScheduler().runTaskAsynchronously(plugin, Runnable {
                plugin.playlistRepository.rename(requireNotNull(playlist.id), newName)
                Bukkit.getScheduler().runTask(plugin, Runnable {
                    viewer.sendMessage("§aプレイリスト名を変更しました: $newName")
                    reload()
                })
            })
        }
    }

    private fun confirmOrDeletePlaylist(playlist: Playlist) {
        if (pendingDeletePlaylistId != playlist.id) {
            pendingDeletePlaylistId = playlist.id
            render()
            return
        }
        Bukkit.getScheduler().runTaskAsynchronously(plugin, Runnable {
            plugin.playlistRepository.delete(requireNotNull(playlist.id))
            Bukkit.getScheduler().runTask(plugin, Runnable {
                viewer.sendMessage("§aプレイリストを削除しました: ${playlist.name}")
                pendingDeletePlaylistId = null
                reload()
            })
        })
    }

    /**
     * 「共有」: 指定したオンラインプレイヤーへ、この曲順のままコピーしたプレイリストを
     * 新規作成する形で送る。共有先はオンラインプレイヤーに限定する
     * （オフラインだと即座に通知できずUUID解決の確実性も下がるため）。
     * 失敗パス（プレイヤーが見つからない等）も含め、必ず [reload] でGUIを再表示すること。
     */
    private fun sharePlaylist(playlist: Playlist) {
        AnvilTextInputSession.open(plugin, viewer, Component.text("共有先のプレイヤー名")) { targetName ->
            Bukkit.getScheduler().runTask(plugin, Runnable {
                val target = Bukkit.getPlayerExact(targetName)
                if (target == null) {
                    viewer.sendMessage("§cオンラインのプレイヤーが見つかりません: $targetName")
                    reload()
                    return@Runnable
                }
                if (target.uniqueId == viewer.uniqueId) {
                    viewer.sendMessage("§c自分自身には共有できません。")
                    reload()
                    return@Runnable
                }
                Bukkit.getScheduler().runTaskAsynchronously(plugin, Runnable {
                    val songs = plugin.playlistRepository.listSongs(requireNotNull(playlist.id))
                    val newPlaylistId = plugin.playlistRepository.create(target.uniqueId, playlist.name)
                    songs.forEach { song -> song.id?.let { plugin.playlistRepository.addSong(newPlaylistId, it) } }
                    Bukkit.getScheduler().runTask(plugin, Runnable {
                        viewer.sendMessage("§a${target.name} にプレイリスト「${playlist.name}」(${songs.size}曲)を共有しました。")
                        target.sendMessage("§d${viewer.name} からプレイリスト「${playlist.name}」が共有されました！ §7(お気に入り♪プレイリストに追加されました)")
                        reload()
                    })
                })
            })
        }
    }
}