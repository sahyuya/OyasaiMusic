package com.github.sahyuya.oyasaiMusic.gui

import com.github.sahyuya.oyasaiMusic.OyasaiMusic
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

private val SONG_ID_KEY = NamespacedKey("oyasaimusic", "song_id")

/**
 * 楽曲一覧グリッド（UI/UX設計書 1章・4章・5章、参照画像3枚目「全楽曲一覧」）。
 * 「③黄色ブロック(全楽曲一覧)」「①自作楽曲一覧」「作者検索の作品一覧」「題名検索結果」等、
 * 複数の画面で共通利用する汎用リスト実装。実際のデータ取得は[loader]に委譲する。
 *
 * クリック動作はUI/UX設計書 5章「楽曲一覧、検索結果」の行に準拠:
 *   左クリック=再生 / Shift+左クリック=詳細を開く / 右クリック=いいね / Shift+右クリック=お気に入り追加
 * 統合版プレイヤーは[ActionModeCategory.SONG_LIST]カテゴリで選択中のモードを同じ4種にマッピングする
 * （サヒュヤ氏の指示によりアクションモードは画面カテゴリごとに独立して記憶する）。
 *
 * 再生は[PlaybackController]に一本化している（各画面が個別にPlaybackEngineを呼ぶと、
 * 再生状態の再描画漏れ等の不具合の温床になっていたため）。
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

    override fun refresh() = reload()

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
        GuiChrome.render(inventory, ownTab, state, sortLabel = sortDisplayName(currentSort()), viewer = viewer, plugin = plugin, actionModeCategory = ActionModeCategory.SONG_LIST)

        LIST_SLOTS.forEachIndexed { index, slot ->
            inventory.setItem(slot, pageSongs.getOrNull(index)?.let { songIcon(it, state) })
        }
    }

    private fun songIcon(song: Song, state: com.github.sahyuya.oyasaiMusic.gui.PlayerControllerState): org.bukkit.inventory.ItemStack {
        val prefix = plugin.config.getString("bedrock.name-prefix", ".") ?: "."
        val nowPlaying = state.isPlaying && state.nowPlayingSong?.id == song.id

        // UI/UX設計書8章「未公開（下書き）状態: …「レコードの破片」として…」に対応。
        // 公開済みでない楽曲(自分の作成中の楽曲)は、実際のレコード種類ではなく
        // レコードの欠片(DISC_FRAGMENT_5)で視覚的に区別する（サヒュヤ氏の指示で追加）。
        if (!song.published) {
            val lore = mutableListOf<Component>(Component.text("非公開（自分だけに表示）", NamedTextColor.DARK_GRAY))
            lore += ActionLoreBuilder.build(viewer, prefix, ActionModeCategory.SONG_LIST, "試聴", "設定を開く", "-", "-")
            if (nowPlaying) lore += Component.text("♪ 再生中", NamedTextColor.GREEN)
            return GuiItemBuilder(Material.DISC_FRAGMENT_5)
                .name(Component.text("[下書き] ${song.title}", NamedTextColor.GRAY))
                .lore(lore)
                .glint(nowPlaying)
                .tag(SONG_ID_KEY, (song.id ?: -1).toString())
                .build()
        }

        val lore = mutableListOf<Component>(Component.text("いいね: ${song.likes}  再生数: ${song.views}", NamedTextColor.GRAY))
        lore += ActionLoreBuilder.build(viewer, prefix, ActionModeCategory.SONG_LIST, "再生", "詳細", "いいね", "お気に入り追加")
        if (nowPlaying) lore += Component.text("♪ 再生中", NamedTextColor.GREEN)
        return GuiItemBuilder(materialFor(song.recordMaterial))
            .name(Component.text(song.title, NamedTextColor.WHITE))
            .lore(lore)
            .glint(nowPlaying)
            .tag(SONG_ID_KEY, (song.id ?: -1).toString())
            .build()
    }

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
        if (NavTabRouter.handle(slot, ownTab, ActionModeCategory.SONG_LIST, plugin, menuManager, viewer)) return
        if (plugin.playbackController.handleControllerClick(slot, viewer)) return
        when (slot) {
            ControllerSlots.SORT -> {
                sortIndex = (sortIndex + 1) % availableSorts.size
                page = 0
                reload()
            }
            ControllerSlots.PAGE_PREV -> if (page > 0) { page--; reload() }
            ControllerSlots.PAGE_NEXT -> if (pageSongs.size == PAGE_SIZE) { page++; reload() }
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
            ActionMode.SECONDARY -> {
                // 下書き楽曲は「詳細」ではなく直接「設定」を開く方が実用的なため分岐する。
                if (!song.published && (song.authorUuid == viewer.uniqueId || viewer.hasPermission("oyasaimusic.admin"))) {
                    menuManager.open(viewer, SongSettingsScreen(plugin, menuManager, viewer, song))
                } else {
                    menuManager.open(viewer, SongDetailScreen(plugin, menuManager, viewer, song))
                }
            }
            ActionMode.TERTIARY -> likeSong(song)
            ActionMode.QUATERNARY -> favoriteSong(song)
        }
    }

    private fun resolveAction(event: InventoryClickEvent, isBedrock: Boolean): ActionMode {
        if (isBedrock) return BedrockActionModeService.get(viewer.uniqueId, ActionModeCategory.SONG_LIST)
        return when (event.click) {
            ClickType.SHIFT_LEFT -> ActionMode.SECONDARY
            ClickType.RIGHT -> ActionMode.TERTIARY
            ClickType.SHIFT_RIGHT -> ActionMode.QUATERNARY
            else -> ActionMode.PRIMARY
        }
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

    /**
     * サヒュヤ氏の指示「シャッフル、ループはプレイリスト外でもどこでも使えるように」への対応
     * （UI/UX設計書6章「全楽曲一覧/検索結果: 1曲で停止。ただしシャッフルON時はリスト内から
     * ランダムに自動再生を継続。」に準拠）。
     *   - シャッフルOFF・ループOFF: 1曲で停止（従来通り）
     *   - シャッフルON: 再生完了ごとに、現在表示中のページ内(公開楽曲のみ)からランダムな1曲へ進む
     *   - シャッフルOFF・ループ=1曲: 同じ曲を繰り返す
     * ページ内からのランダム選出という簡易実装のため、他ページの楽曲までは対象にならない
     * （要確認: 全件対象にする場合はDB側のランダム抽出クエリが別途必要）。
     */
    private fun playSong(song: Song) {
        plugin.playbackController.play(viewer, song, onCompletion = { handleAutoAdvance(song) })
    }

    private fun handleAutoAdvance(justFinished: Song) {
        val state = plugin.controllerStateService.stateFor(viewer.uniqueId)
        when {
            state.loopMode == LoopMode.SINGLE -> playSong(justFinished)
            state.shuffle -> {
                val candidates = pageSongs.filter { it.published && it.id != justFinished.id }
                    .ifEmpty { pageSongs.filter { it.published } }
                val next = candidates.randomOrNull() ?: return
                playSong(next)
            }
            else -> {} // シャッフルOFF・ループOFF(またはLIST): 単曲再生のみ（設計書6章）
        }
    }
}