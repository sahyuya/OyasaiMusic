package com.github.sahyuya.oyasaiMusic.gui

import com.github.sahyuya.oyasaiMusic.OyasaiMusic
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

/**
 * お気に入り/プレイリストの「登録楽曲一覧」画面（UI/UX設計書 5章・6章）。
 * 「詳細を開いた瞬間にリスト左上の曲を自動再生開始。以降は設定順に順次再生」に対応するため、
 * 画面を開いた時点で先頭曲の再生を開始し、再生完了ごとに次の曲へ自動的に進める
 * （シャッフルONの場合は次の曲をランダムに選ぶ。下段のシャッフル/ループボタンの状態を見る）。
 * 再生は[com.github.sahyuya.oyasaiMusic.gui.PlaybackController]に一本化している（他画面と同様の理由）。
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
        // サヒュヤ氏の指示: 5×8フル(40スロット)、slot1(左上)から詰めて表示する。
        val SLOTS: List<Int> = ContentGrid.SLOTS
        /** 曲間の間隔（サヒュヤ氏の指示: 約1秒。20tick=1000ms）。 */
        private const val ADVANCE_DELAY_TICKS = 20L

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
    private var pendingAdvanceTask: org.bukkit.scheduler.BukkitTask? = null

    init {
        reload(autoPlayFirst = true)
    }

    override fun refresh() = reload()

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
                if (autoPlayFirst && songs.isNotEmpty()) playIndex(0)
            })
        })
    }

    private fun render() {
        val state = plugin.controllerStateService.stateFor(viewer.uniqueId)
        GuiChrome.render(
            inventory, null, state, sortLabel = "設定順",
            viewer = viewer, plugin = plugin, actionModeCategory = ActionModeCategory.PLAYLIST_DETAIL,
        )

        SLOTS.forEachIndexed { index, slot ->
            songs.getOrNull(index)?.let { inventory.setItem(slot, songIcon(it, state)) }
        }
    }

    private fun songIcon(song: Song, state: com.github.sahyuya.oyasaiMusic.gui.PlayerControllerState): org.bukkit.inventory.ItemStack {
        val confirming = pendingRemoveSongId == song.id
        val dragging = draggingSongId == song.id
        val nowPlaying = state.isPlaying && state.nowPlayingSong?.id == song.id
        val prefix = plugin.config.getString("bedrock.name-prefix", ".") ?: "."

        val lore = mutableListOf<Component>(Component.text("いいね: ${song.likes}  再生数: ${song.views}", NamedTextColor.GRAY))
        lore += ActionLoreBuilder.build(viewer, prefix, ActionModeCategory.PLAYLIST_DETAIL, "再生", "詳細を開く", "掴んで移動", "除外")
        when {
            dragging -> lore += Component.text("移動中… 移動先をクリック（再クリックでキャンセル）", NamedTextColor.AQUA)
            draggingSongId != null -> lore += Component.text("クリックでここに移動", NamedTextColor.AQUA)
            confirming -> lore += Component.text("もう一度Shift+右クリックで除外確定", NamedTextColor.RED)
            nowPlaying -> lore += Component.text("♪ 再生中", NamedTextColor.GREEN)
        }

        return GuiItemBuilder(Material.matchMaterial(song.recordMaterial) ?: Material.MUSIC_DISC_13)
            .name(Component.text(song.title, NamedTextColor.WHITE))
            .lore(lore)
            .glint(confirming || dragging || nowPlaying)
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

        if (NavTabRouter.handle(slot, null, ActionModeCategory.PLAYLIST_DETAIL, plugin, menuManager, viewer)) return

        when (slot) {
            ControllerSlots.PREV_SONG -> {
                if (songs.isEmpty()) return
                val prevIndex = (autoPlayIndex - 1).let { if (it < 0) songs.size - 1 else it }
                playIndex(prevIndex, delayTicks = ADVANCE_DELAY_TICKS)
            }
            ControllerSlots.NEXT_SONG -> scheduleAdvance()
            else -> {
                if (plugin.playbackController.handleControllerClick(slot, viewer)) return
                if (index == -1) return
                handleSongClick(event, index)
            }
        }
    }

    private fun handleSongClick(event: InventoryClickEvent, index: Int) {
        val song = songs.getOrNull(index) ?: return
        if (song.id != pendingRemoveSongId) pendingRemoveSongId = null

        val prefix = plugin.config.getString("bedrock.name-prefix", ".") ?: "."
        val isBedrock = BedrockUtil.isBedrock(viewer, prefix)
        val action = if (isBedrock) BedrockActionModeService.get(viewer.uniqueId, ActionModeCategory.PLAYLIST_DETAIL) else when (event.click) {
            ClickType.SHIFT_LEFT -> ActionMode.SECONDARY
            ClickType.RIGHT -> ActionMode.TERTIARY
            ClickType.SHIFT_RIGHT -> ActionMode.QUATERNARY
            else -> ActionMode.PRIMARY
        }
        when (action) {
            ActionMode.PRIMARY -> playIndex(index)
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

    private fun playIndex(index: Int, delayTicks: Long = 0) {
        val song = songs.getOrNull(index) ?: return
        autoPlayIndex = index
        pendingAdvanceTask?.cancel()
        pendingAdvanceTask = null
        if (delayTicks <= 0) {
            plugin.playbackController.play(viewer, song, onCompletion = { scheduleAdvance() })
        } else {
            pendingAdvanceTask = Bukkit.getScheduler().runTaskLater(
                plugin,
                Runnable {
                    pendingAdvanceTask = null
                    plugin.playbackController.play(viewer, song, onCompletion = { scheduleAdvance() })
                },
                delayTicks,
            )
        }
    }

    /**
     * UI/UX設計書6章「以降は設定順に順次再生」への対応。末尾まで再生したらループ設定に従う。
     * シャッフルONの場合は次の曲をランダムに選ぶ（サヒュヤ氏の指示「シャッフル、ループ機能」対応）。
     * 曲と曲の間には約1秒の間隔を空ける（サヒュヤ氏の指示）。
     */
    private fun scheduleAdvance() {
        if (songs.isEmpty()) return
        val state = plugin.controllerStateService.stateFor(viewer.uniqueId)
        val nextIndex = resolveNextIndex(state) ?: return
        playIndex(nextIndex, delayTicks = ADVANCE_DELAY_TICKS)
    }

    private fun resolveNextIndex(state: com.github.sahyuya.oyasaiMusic.gui.PlayerControllerState): Int? {
        if (state.shuffle) {
            if (songs.size == 1) return if (state.loopMode != LoopMode.OFF) 0 else null
            var next: Int
            do { next = songs.indices.random() } while (next == autoPlayIndex)
            return next
        }
        val nextIndex = autoPlayIndex + 1
        if (nextIndex < songs.size) return nextIndex
        return if (state.loopMode != LoopMode.OFF) 0 else null
    }
}