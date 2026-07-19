package com.github.sahyuya.oyasaiMusic.gui

import com.github.sahyuya.oyasaiMusic.OyasaiMusic
import com.github.sahyuya.oyasaiMusic.model.Playlist
import com.github.sahyuya.oyasaiMusic.model.Song
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.event.inventory.InventoryClickEvent

/**
 * お気に入り♪プレイリスト選択画面（UI/UX設計書、参照画像6枚目）。
 * 楽曲一覧・楽曲詳細から「お気に入り/プレイリストへ追加」しようとした際に開く選択画面
 * （左列緑タブから直接開く[FavoritesPlaylistsScreen]とは別物）。
 *
 * サヒュヤ氏の指示により、コンテンツ領域(1,4)＝slot37に「戻る」ボタン(矢)を設置する。
 * 背景は緑タブに合わせて[Material.LIME_STAINED_GLASS_PANE]。
 */
class PlaylistSelectionScreen(
    private val plugin: OyasaiMusic,
    private val menuManager: MenuManager,
    viewer: Player,
    private val targetSong: Song,
) : BaseGridMenu(viewer, Component.text("お気に入り♪プレイリスト選択")) {

    companion object {
        val SLOTS: List<Int> = (1..4).flatMap { row -> (1..8).map { col -> row * 9 + col } }
        private const val FAVORITES_INDEX = 0
        private const val BACK_SLOT = 37
    }

    private var playlists: List<Playlist> = emptyList()

    init { reload() }

    override fun refresh() = reload()

    private fun reload() {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, Runnable {
            val list = plugin.playlistRepository.listByOwner(viewer.uniqueId)
            Bukkit.getScheduler().runTask(plugin, Runnable {
                playlists = list
                render()
            })
        })
    }

    private fun render() {
        val state = plugin.controllerStateService.stateFor(viewer.uniqueId)
        GuiChrome.render(inventory, null, state, sortLabel = "-", viewer = viewer, actionModeCategory = null)

        inventory.setItem(
            SLOTS[FAVORITES_INDEX],
            GuiItemBuilder(Material.NETHER_STAR)
                .name(Component.text("お気に入りに追加", NamedTextColor.LIGHT_PURPLE))
                .lore(Component.text("「${targetSong.title}」を追加", NamedTextColor.GRAY))
                .build(),
        )

        playlists.forEachIndexed { i, playlist ->
            val slotIndex = FAVORITES_INDEX + 1 + i
            if (slotIndex < SLOTS.size) {
                inventory.setItem(
                    SLOTS[slotIndex],
                    GuiItemBuilder(Material.CHISELED_BOOKSHELF)
                        .name(Component.text(playlist.name, NamedTextColor.YELLOW))
                        .lore(Component.text("${playlist.songCount} 曲", NamedTextColor.GRAY))
                        .build(),
                )
            }
        }

        val createIndex = FAVORITES_INDEX + 1 + playlists.size
        if (createIndex < SLOTS.size) {
            inventory.setItem(
                SLOTS[createIndex],
                GuiItemBuilder(Material.WRITABLE_BOOK)
                    .name(Component.text("+ 新規プレイリストを作成して追加", NamedTextColor.GREEN))
                    .build(),
            )
        }

        inventory.setItem(
            BACK_SLOT,
            GuiItemBuilder(Material.ARROW).name(Component.text("戻る", NamedTextColor.WHITE)).build(),
        )
    }

    override fun onClick(event: InventoryClickEvent) {
        val slot = event.rawSlot
        if (slot == BACK_SLOT) {
            menuManager.openPrevious(viewer)
            return
        }
        if (NavTabRouter.handle(slot, null, null, plugin, menuManager, viewer)) return
        if (plugin.playbackController.handleControllerClick(slot, viewer)) return

        val index = SLOTS.indexOf(slot)
        if (index == -1) return

        if (index == FAVORITES_INDEX) {
            addToFavorites()
            return
        }
        val playlistIndex = index - FAVORITES_INDEX - 1
        val playlist = playlists.getOrNull(playlistIndex)
        if (playlist != null) {
            addToPlaylist(playlist)
        } else if (index == FAVORITES_INDEX + 1 + playlists.size) {
            createPlaylistAndAdd()
        }
    }

    private fun addToFavorites() {
        val songId = targetSong.id ?: return
        Bukkit.getScheduler().runTaskAsynchronously(plugin, Runnable {
            val added = plugin.socialRepository.addFavorite(viewer.uniqueId, songId)
            Bukkit.getScheduler().runTask(plugin, Runnable {
                viewer.sendMessage(if (added) "§aお気に入りに追加しました: ${targetSong.title}" else "§7既にお気に入り済みです。")
                menuManager.openPrevious(viewer)
            })
        })
    }

    private fun addToPlaylist(playlist: Playlist) {
        val songId = targetSong.id ?: return
        Bukkit.getScheduler().runTaskAsynchronously(plugin, Runnable {
            val added = plugin.playlistRepository.addSong(requireNotNull(playlist.id), songId)
            Bukkit.getScheduler().runTask(plugin, Runnable {
                viewer.sendMessage(if (added) "§a「${playlist.name}」に追加しました: ${targetSong.title}" else "§7既に追加済みです。")
                menuManager.openPrevious(viewer)
            })
        })
    }

    private fun createPlaylistAndAdd() {
        AnvilTextInput.open(plugin, viewer, Component.text("新しいプレイリスト名")) { name ->
            Bukkit.getScheduler().runTaskAsynchronously(plugin, Runnable {
                val playlistId = plugin.playlistRepository.create(viewer.uniqueId, name)
                val songId = targetSong.id
                if (songId != null) plugin.playlistRepository.addSong(playlistId, songId)
                Bukkit.getScheduler().runTask(plugin, Runnable {
                    viewer.sendMessage("§aプレイリスト「$name」を作成し、「${targetSong.title}」を追加しました。")
                    menuManager.openPrevious(viewer)
                })
            })
        }
    }
}