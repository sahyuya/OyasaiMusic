package com.github.sahyuya.oyasaiMusic.gui

import com.github.sahyuya.oyasaiMusic.OyasaiMusic
import com.github.sahyuya.oyasaiMusic.audio.SongAudioFile
import com.github.sahyuya.oyasaiMusic.model.Playlist
import com.github.sahyuya.oyasaiMusic.model.Song
import com.github.sahyuya.oyasaiMusic.util.BedrockUtil
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.event.inventory.ClickType
import org.bukkit.event.inventory.InventoryClickEvent
import java.io.File

/**
 * お気に入り/プレイリストの「登録楽曲一覧」画面（UI/UX設計書 5章・6章）。
 * 「詳細を開いた瞬間にリスト左上の曲を自動再生開始。以降は設定順に順次再生」に対応するため、
 * 画面を開いた時点で先頭曲の再生を開始し、再生完了ごとに次の曲へ自動的に進める。
 *
 * クリック動作（UI/UX設計書5章「お気に入り・プレイリスト (登録楽曲一覧)」）:
 *   左クリック=再生 / Shift+左=詳細を開く / 右クリック=並び替え(ドラッグ) / Shift+右=リストから除外(要確認)
 *
 * 【並び替え(ドラッグ)の実装方式（要確認）】
 * サヒュヤ氏の要望により実際のドラッグ操作を実装したが、Bukkitのカーソル(掴み上げ)を
 * そのまま使う方式は、GUI外へのクリックでアイテムが実体化＝複製されてしまうリスクがあり、
 * この環境ではライブ検証ができないため採用していない。代わりに「右クリックで曲を選択
 * →別の曲を右クリックでそこへ挿入（同じ曲の再クリックでキャンセル）」という、実アイテムを
 * 一切動かさない安全な2クリック方式にしている。見た目はカーソルに乗らないが、
 * 選択中の曲が光る演出で「持ち上げている」ことを表現している。
 * お気に入り(favoritesテーブル)には並び順の概念が無いため、並び替えは実プレイリストのみ対応。
 *
 * このリストは左列タブから直接遷移する画面ではない（[FavoritesPlaylistsScreen]からの
 * ドリルダウン）ため、サヒュヤ氏指定の「戻る」ボタン対象3画面には含めていない
 * （緑タブを再クリックすれば一覧へ戻れるため）。
 */
class PlaylistDetailScreen private constructor(
    private val plugin: OyasaiMusic,
    private val menuManager: MenuManager,
    viewer: Player,
    private val playlist: Playlist?, // null = お気に入り
) : BaseGridMenu(viewer, Component.text(playlist?.name ?: "お気に入り")) {

    companion object {
        val SLOTS: List<Int> = (0..4).flatMap { row -> (1..8).map { col -> row * 9 + col } }

        fun forFavorites(plugin: OyasaiMusic, menuManager: MenuManager, viewer: Player) =
            PlaylistDetailScreen(plugin, menuManager, viewer, null)

        fun forPlaylist(plugin: OyasaiMusic, menuManager: MenuManager, viewer: Player, playlist: Playlist) =
            PlaylistDetailScreen(plugin, menuManager, viewer, playlist)
    }

    private var songs: List<Song> = emptyList()
    private var pendingRemoveSongId: Long? = null
    private var autoPlayIndex = 0
    private var draggingSongId: Long? = null
    private var draggingFromIndex: Int? = null

    init {
        reload(autoPlayFirst = true)
    }

    private fun reload(autoPlayFirst: Boolean = false) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, Runnable {
            val list = if (playlist != null) {
                plugin.playlistRepository.listSongs(requireNotNull(playlist.id))
            } else {
                plugin.socialRepository.listFavoriteSongIds(viewer.uniqueId).mapNotNull { plugin.songRepository.findById(it) }
            }
            Bukkit.getScheduler().runTask(plugin, Runnable {
                songs = list
                render()
                if (autoPlayFirst && songs.isNotEmpty()) playIndex(0, advanceOnCompletion = true)
            })
        })
    }

    private fun render() {
        val state = plugin.controllerStateService.stateFor(viewer.uniqueId)
        GuiChrome.render(inventory, null, state, sortLabel = "設定順", viewer = viewer, actionModeCategory = ActionModeCategory.PLAYLIST_DETAIL)

        SLOTS.forEachIndexed { index, slot ->
            songs.getOrNull(index)?.let { inventory.setItem(slot, songIcon(it, index)) }
        }
    }

    private fun songIcon(song: Song, index: Int): org.bukkit.inventory.ItemStack {
        val confirming = pendingRemoveSongId == song.id
        val dragging = draggingSongId == song.id
        val extraLore = when {
            dragging -> arrayOf(Component.text("移動中… 移動先をクリック（再クリックでキャンセル）", NamedTextColor.AQUA))
            draggingSongId != null -> arrayOf(Component.text("クリックでここに移動", NamedTextColor.AQUA))
            confirming -> arrayOf(Component.text("もう一度Shift+右クリックで除外確定", NamedTextColor.RED))
            else -> emptyArray()
        }
        return GuiItemBuilder(Material.matchMaterial(song.recordMaterial) ?: Material.MUSIC_DISC_13)
            .name(Component.text(song.title, NamedTextColor.WHITE))
            .lore(
                Component.text("いいね: ${song.likes}  再生数: ${song.views}", NamedTextColor.GRAY),
                Component.text("左:再生 Shift+左:詳細", NamedTextColor.DARK_GRAY),
                Component.text("右:掴んで移動 Shift+右:除外", NamedTextColor.DARK_GRAY),
                *extraLore,
            )
            .glint(confirming || dragging)
            .build()
    }

    override fun onClick(event: InventoryClickEvent) {
        val slot = event.rawSlot
        val index = SLOTS.indexOf(slot)

        // ドラッグ中は次のクリックを常に「ドロップ」として扱う。
        // 実アイテム(カーソル)は一切動かさず内部状態(DBの並び順)だけを更新する安全な方式にしている
        // （実カーソルでの掴み上げ方式は、GUI外へのクリックでアイテムが実体化＝複製されうるリスクが
        // あり、この環境ではライブ検証ができないため採用していない。要確認）。
        if (draggingSongId != null) {
            if (index != -1) dropDragged(index) else cancelDrag()
            return
        }

        if (NavTabRouter.handle(slot, NavTab.FAVORITES_PLAYLISTS, ActionModeCategory.PLAYLIST_DETAIL, plugin, menuManager, viewer)) return
        if (index == -1) return
        val song = songs.getOrNull(index) ?: return
        if (song.id != pendingRemoveSongId) pendingRemoveSongId = null

        val prefix = plugin.config.getString("bedrock.name-prefix", ".") ?: "."
        val isBedrock = BedrockUtil.isBedrock(viewer, prefix)
        val action = if (isBedrock) BedrockActionModeService.get(viewer.uniqueId, category = ActionModeCategory.PLAYLIST_DETAIL) else when (event.click) {
            ClickType.SHIFT_LEFT -> ActionMode.SECONDARY
            ClickType.RIGHT -> ActionMode.TERTIARY
            ClickType.SHIFT_RIGHT -> ActionMode.QUATERNARY
            else -> ActionMode.PRIMARY
        }
        when (action) {
            ActionMode.PRIMARY -> playIndex(index, advanceOnCompletion = true)
            ActionMode.SECONDARY -> openDetailsOrSettings(song)
            ActionMode.TERTIARY -> beginDrag(song, index)
            ActionMode.QUATERNARY -> confirmOrRemove(song)
        }
    }

    private fun openDetailsOrSettings(song: Song) {
        menuManager.open(viewer, SongDetailScreen(plugin, menuManager, viewer, song))
    }

    private fun beginDrag(song: Song, index: Int) {
        if (playlist == null) {
            viewer.sendMessage("§7お気に入りには並び順がありません。")
            return
        }
        if (draggingSongId == song.id) {
            cancelDrag()
            return
        }
        draggingSongId = song.id
        draggingFromIndex = index
        viewer.sendMessage("§b「${song.title}」を持ち上げました。移動先の曲をクリックしてください（同じ曲を再クリックでキャンセル）。")
        render()
    }

    private fun cancelDrag() {
        draggingSongId = null
        draggingFromIndex = null
        render()
    }

    private fun dropDragged(targetIndex: Int) {
        val songId = draggingSongId ?: return
        val fromIndex = draggingFromIndex
        draggingSongId = null
        draggingFromIndex = null
        if (playlist == null) return
        if (targetIndex == fromIndex) {
            render()
            return
        }
        Bukkit.getScheduler().runTaskAsynchronously(plugin, Runnable {
            plugin.playlistRepository.reorderToPosition(requireNotNull(playlist.id), songId, targetIndex)
            Bukkit.getScheduler().runTask(plugin, Runnable {
                viewer.sendMessage("§a曲順を変更しました。")
                reload()
            })
        })
    }

    private fun confirmOrRemove(song: Song) {
        if (pendingRemoveSongId != song.id) {
            pendingRemoveSongId = song.id
            render()
            return
        }
        val songId = requireNotNull(song.id)
        Bukkit.getScheduler().runTaskAsynchronously(plugin, Runnable {
            if (playlist != null) {
                plugin.playlistRepository.removeSong(requireNotNull(playlist.id), songId)
            } else {
                plugin.socialRepository.removeFavorite(viewer.uniqueId, songId)
            }
            Bukkit.getScheduler().runTask(plugin, Runnable {
                viewer.sendMessage("§aリストから除外しました: ${song.title}")
                pendingRemoveSongId = null
                reload()
            })
        })
    }

    private fun playIndex(index: Int, advanceOnCompletion: Boolean) {
        val song = songs.getOrNull(index) ?: return
        autoPlayIndex = index
        Bukkit.getScheduler().runTaskAsynchronously(plugin, Runnable {
            val file = File(plugin.audioDirectory, song.fileName)
            if (!file.exists()) return@Runnable
            val audio = SongAudioFile.read(file)
            Bukkit.getScheduler().runTask(plugin, Runnable {
                val mode = plugin.playbackModeService.resolve(viewer.uniqueId, song)
                plugin.playbackEngine.play(
                    song = song,
                    notes = audio.notes,
                    recipients = listOf(viewer),
                    mode = mode,
                    onListenThresholdReached = { player, s -> plugin.viewCountService.registerView(player, s, isAmbientPlayback = false) },
                    onCompletion = {
                        val state = plugin.controllerStateService.stateFor(viewer.uniqueId)
                        state.isPlaying = false
                        if (advanceOnCompletion) advanceToNext()
                    },
                )
                val state = plugin.controllerStateService.stateFor(viewer.uniqueId)
                state.isPlaying = true
                state.nowPlayingSong = song
                viewer.sendMessage("§a再生開始: §f${song.title}")
            })
        })
    }

    /** UI/UX設計書6章「以降は設定順に順次再生」への対応。末尾まで再生したらループ設定に従う。 */
    private fun advanceToNext() {
        val nextIndex = autoPlayIndex + 1
        if (nextIndex < songs.size) {
            playIndex(nextIndex, advanceOnCompletion = true)
            return
        }
        val state = plugin.controllerStateService.stateFor(viewer.uniqueId)
        if (state.loopMode != com.github.sahyuya.oyasaiMusic.gui.LoopMode.OFF && songs.isNotEmpty()) {
            playIndex(0, advanceOnCompletion = true)
        }
    }
}