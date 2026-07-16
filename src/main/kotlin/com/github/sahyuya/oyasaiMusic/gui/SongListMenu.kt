package com.github.sahyuya.oyasaiMusic.gui

import com.github.sahyuya.oyasaiMusic.OyasaiMusic
import com.github.sahyuya.oyasaiMusic.audio.SongAudioFile
import com.github.sahyuya.oyasaiMusic.db.SongSort
import com.github.sahyuya.oyasaiMusic.model.Song
import com.github.sahyuya.oyasaiMusic.util.BedrockUtil
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.entity.Player
import org.bukkit.event.inventory.ClickType
import org.bukkit.event.inventory.InventoryClickEvent
import java.io.File

private val SONG_ID_KEY = NamespacedKey("oyasaimusic", "song_id")

/**
 * 楽曲一覧グリッド（UI/UX設計書 1章・4章・5章、参照画像3枚目「全楽曲一覧」）。
 * 「③黄色ブロック(全楽曲一覧)」「①自作楽曲一覧」「作者検索の作品一覧」「題名検索結果」等、
 * 複数の画面で共通利用する汎用リスト実装。実際のデータ取得は[loader]に委譲する。
 *
 * 【実装上の解釈（要確認）】
 * 参照画像で一貫してコンテンツ領域の先頭行(row0, slot1〜8)が空欄だったため、
 * 本実装ではその1行を予備領域として空け、残り4行×8列=32件/ページで一覧を表示している。
 *
 * クリック動作はUI/UX設計書 5章「楽曲一覧、検索結果」の行に準拠:
 *   左クリック=再生 / Shift+左クリック=詳細を開く(※楽曲詳細画面は未実装のため暫定メッセージ) /
 *   右クリック=いいね / Shift+右クリック=お気に入り追加(※プレイリスト選択は未実装)
 * 統合版プレイヤーは[BedrockActionModeService]で選択中のモードを同じ4種にマッピングする。
 */
class SongListMenu(
    private val plugin: OyasaiMusic,
    private val menuManager: MenuManager,
    viewer: Player,
    title: String,
    private val availableSorts: List<SongSort>,
    initialSort: SongSort,
    /** このリストが左列タブのどれに属するか（自作楽曲一覧=MY_SONGS、全楽曲一覧=ALL_SONGS）。
     *  検索結果・作者作品一覧のようにタブに直接紐づかない場合はnull。
     *  同じタブを再クリックした際にメインメニューへ戻る挙動([NavTabRouter])に使う。 */
    private val ownTab: NavTab? = null,
    private val loader: (sort: SongSort, limit: Int, offset: Int) -> List<Song>,
) : BaseGridMenu(viewer, Component.text(title)) {

    companion object {
        const val PAGE_SIZE = 32
        val LIST_SLOTS: List<Int> = (1..4).flatMap { row -> (1..8).map { col -> row * 9 + col } }
    }

    private var sortIndex = availableSorts.indexOf(initialSort).coerceAtLeast(0)
    private var page = 0
    private var pageSongs: List<Song> = emptyList()

    init {
        reload()
    }

    private fun currentSort(): SongSort = availableSorts[sortIndex]

    private fun reload() {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, Runnable {
            val songs = loader(currentSort(), PAGE_SIZE, page * PAGE_SIZE)
            Bukkit.getScheduler().runTask(plugin, Runnable {
                pageSongs = songs
                render()
            })
        })
    }

    private fun render() {
        val state = plugin.controllerStateService.stateFor(viewer.uniqueId)
        GuiChrome.render(inventory, ownTab, state, sortLabel = sortDisplayName(currentSort()))
        ContentGrid.fill(inventory, Material.GRAY_STAINED_GLASS_PANE)

        LIST_SLOTS.forEachIndexed { index, slot ->
            pageSongs.getOrNull(index)?.let { inventory.setItem(slot, songIcon(it)) }
        }
    }

    private fun songIcon(song: Song) = GuiItemBuilder(materialFor(song.recordMaterial))
        .name(Component.text(song.title, NamedTextColor.WHITE))
        .lore(
            Component.text("いいね: ${song.likes}  再生数: ${song.views}", NamedTextColor.GRAY),
            Component.text("左クリック:再生 Shift+左:詳細", NamedTextColor.DARK_GRAY),
            Component.text("右クリック:いいね Shift+右:お気に入り", NamedTextColor.DARK_GRAY),
        )
        .tag(SONG_ID_KEY, (song.id ?: -1).toString())
        .build()

    private fun materialFor(recordMaterial: String): Material = Material.matchMaterial(recordMaterial) ?: Material.MUSIC_DISC_13

    private fun sortDisplayName(sort: SongSort): String = when (sort) {
        SongSort.CREATED_AT_DESC -> "作成日(新しい順)"
        SongSort.CREATED_AT_ASC -> "作成日(古い順)"
        SongSort.TITLE_ASC -> "題名(昇順)"
        SongSort.LIKES_DESC -> "総いいね順"
        SongSort.VIEWS_DESC -> "総再生回数順"
    }

    override fun onClick(event: InventoryClickEvent) {
        val slot = event.rawSlot
        if (NavTabRouter.handle(slot, ownTab, plugin, menuManager, viewer)) return
        when (slot) {
            ControllerSlots.SORT -> {
                sortIndex = (sortIndex + 1) % availableSorts.size
                page = 0
                reload()
            }
            ControllerSlots.PAGE_PREV -> if (page > 0) { page--; reload() }
            ControllerSlots.PAGE_NEXT -> if (pageSongs.size == PAGE_SIZE) { page++; reload() }
            ControllerSlots.NOW_PLAYING -> {} // TODO: 楽曲詳細画面の実装後、再生中の曲の詳細へ遷移させる
            ControllerSlots.PLAY_PAUSE, ControllerSlots.PREV_SONG, ControllerSlots.NEXT_SONG ->
                viewer.sendMessage("§7この操作は現在プレイリスト連携の実装待ちです。")
            ControllerSlots.LOOP -> toggleLoop()
            ControllerSlots.SHUFFLE -> toggleShuffle()
            else -> if (slot in LIST_SLOTS) handleSongClick(event, slot)
        }
    }

    private fun handleSongClick(event: InventoryClickEvent, slot: Int) {
        val index = LIST_SLOTS.indexOf(slot)
        val song = pageSongs.getOrNull(index) ?: return
        val prefix = plugin.config.getString("bedrock.name-prefix", ".") ?: "."
        val isBedrock = BedrockUtil.isBedrock(viewer, prefix)
        val action = resolveAction(event, isBedrock)
        when (action) {
            ActionMode.PRIMARY -> playSong(song)
            ActionMode.SECONDARY -> openDetailsOrSettings(song)
            ActionMode.TERTIARY -> likeSong(song)
            ActionMode.QUATERNARY -> favoriteSong(song)
        }
    }

    /**
     * 本来はここで「楽曲詳細画面」を開くべきだが未実装のため、暫定的に
     * 作者本人またはOPであれば楽曲設定画面(SongSettingsScreen)へ直接遷移させる
     * （楽曲詳細画面の実装後、そちらの「設定」ボタン経由に差し替える想定）。
     */
    private fun openDetailsOrSettings(song: Song) {
        if (song.authorUuid == viewer.uniqueId || viewer.hasPermission("oyasaimusic.admin")) {
            menuManager.open(viewer, SongSettingsScreen(plugin, menuManager, viewer, song))
        } else {
            viewer.sendMessage("§e楽曲詳細画面は近日実装予定です。（${song.title}）")
        }
    }

    private fun resolveAction(event: InventoryClickEvent, isBedrock: Boolean): ActionMode {
        if (isBedrock) return BedrockActionMode.get(viewer.uniqueId)
        return when (event.click) {
            ClickType.SHIFT_LEFT -> ActionMode.SECONDARY
            ClickType.RIGHT -> ActionMode.TERTIARY
            ClickType.SHIFT_RIGHT -> ActionMode.QUATERNARY
            else -> ActionMode.PRIMARY
        }
    }

    private fun playSong(song: Song) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, Runnable {
            val file = File(plugin.audioDirectory, song.fileName)
            if (!file.exists()) {
                Bukkit.getScheduler().runTask(plugin, Runnable { viewer.sendMessage("§c音源ファイルが見つかりません。") })
                return@Runnable
            }
            val audio = SongAudioFile.read(file)
            Bukkit.getScheduler().runTask(plugin, Runnable {
                val mode = plugin.playbackModeService.resolve(viewer.uniqueId, song)
                plugin.playbackEngine.play(
                    song = song,
                    notes = audio.notes,
                    recipients = listOf(viewer),
                    mode = mode,
                    onListenThresholdReached = { player, s -> plugin.viewCountService.registerView(player, s, isAmbientPlayback = false) },
                    onCompletion = { plugin.controllerStateService.stateFor(viewer.uniqueId).isPlaying = false },
                )
                val state = plugin.controllerStateService.stateFor(viewer.uniqueId)
                state.isPlaying = true
                state.nowPlayingTitle = song.title
                render()
                viewer.sendMessage("§a再生開始: §f${song.title}")
            })
        })
    }

    private fun likeSong(song: Song) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, Runnable {
            val added = plugin.likeService.like(viewer.uniqueId, song)
            Bukkit.getScheduler().runTask(plugin, Runnable {
                viewer.sendMessage(if (added) "§aいいねしました: ${song.title}" else "§7既にいいね済みです。")
                if (added) reload()
            })
        })
    }

    private fun favoriteSong(song: Song) {
        menuManager.open(viewer, PlaylistSelectionScreen(plugin, menuManager, viewer, song))
    }

    private fun toggleLoop() {
        val state = plugin.controllerStateService.stateFor(viewer.uniqueId)
        state.loopMode = when (state.loopMode) {
            LoopMode.OFF -> LoopMode.LIST
            LoopMode.LIST -> LoopMode.SINGLE
            LoopMode.SINGLE -> LoopMode.OFF
        }
        render()
    }

    private fun toggleShuffle() {
        val state = plugin.controllerStateService.stateFor(viewer.uniqueId)
        state.shuffle = !state.shuffle
        render()
    }
}