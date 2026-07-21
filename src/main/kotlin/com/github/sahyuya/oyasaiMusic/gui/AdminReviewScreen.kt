package com.github.sahyuya.oyasaiMusic.gui

import com.github.sahyuya.oyasaiMusic.OyasaiMusic
import com.github.sahyuya.oyasaiMusic.audio.SongAudioFile
import com.github.sahyuya.oyasaiMusic.db.ReviewSort
import com.github.sahyuya.oyasaiMusic.model.Song
import com.github.sahyuya.oyasaiMusic.model.SongStatus
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.event.inventory.ClickType
import org.bukkit.event.inventory.InventoryClickEvent
import java.io.File

/**
 * OP専用：審査・履歴管理GUI（UI/UX設計書 2章・4章・5章・8章）。
 * メインメニュー「③【OP専用】審査・履歴管理GUIへの入り口」から開く。
 *
 * 並び順（UI/UX設計書4章）: 既定=新着順。他に未審査古い順/審査済新着順/古い順。
 *   「未審査古い順」は審査済を後方に分離、「審査済新着順」は未審査を後方に分離して表示。
 *
 * クリック動作（UI/UX設計書5章）:
 *   左クリック=再生 / Shift+左=詳細を開く / 右クリック=許可(要確認) / Shift+右=却下(要確認)
 * 「許可」は 未審査→仮OK→永続OK→未審査… の順に循環させる（要確認: 許可を1段階の操作として
 * まとめている。仮OK/永続OK個別にボタンを分けたい場合は要相談）。
 * 「却下」はShift+右クリックで直接設定する（要確認画面つき）。
 * 審査済のものはエンチャントモヤを付与し、Loreに判定結果を記載する（UI/UX設計書8章）。
 * 後からの判定修正も可能（何度でも許可/却下をクリックし直せる）。
 */
class AdminReviewScreen(
    private val plugin: OyasaiMusic,
    private val menuManager: MenuManager,
    viewer: Player,
) : BaseGridMenu(viewer, Component.text("審査・履歴管理")) {

    companion object {
        const val PAGE_SIZE = 39 // 戻るボタン(slot37)を除いた枠数
        private const val BACK_SLOT = 37
        val SLOTS: List<Int> = ContentGrid.SLOTS.filter { it != BACK_SLOT }
        private val AVAILABLE_SORTS = listOf(ReviewSort.NEWEST, ReviewSort.OLDEST, ReviewSort.UNREVIEWED_OLDEST_FIRST, ReviewSort.REVIEWED_NEWEST_FIRST)
    }

    private var sortIndex = 0
    private var page = 0
    private var pageSongs: List<Song> = emptyList()
    private var pendingRejectId: Long? = null

    init {
        if (hasAccess()) reload() else renderNoAccess()
    }

    override fun refresh() = if (hasAccess()) reload() else renderNoAccess()

    private fun hasAccess(): Boolean = viewer.hasPermission("oyasaimusic.admin")

    private fun renderNoAccess() {
        val state = plugin.controllerStateService.stateFor(viewer.uniqueId)
        GuiChrome.render(inventory, null, state, sortLabel = "-", viewer = viewer, plugin = plugin, actionModeCategory = null)
        inventory.setItem(11, GuiItemBuilder(Material.BARRIER).name(Component.text("権限がありません", NamedTextColor.RED)).build())
        inventory.setItem(BACK_SLOT, backButton())
    }

    private fun currentSort() = AVAILABLE_SORTS[sortIndex]

    private fun reload() {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, Runnable {
            val songs = plugin.songRepository.listForReview(currentSort(), PAGE_SIZE, page * PAGE_SIZE)
            Bukkit.getScheduler().runTask(plugin, Runnable {
                pageSongs = songs
                render()
            })
        })
    }

    private fun render() {
        val state = plugin.controllerStateService.stateFor(viewer.uniqueId)
        GuiChrome.render(inventory, null, state, sortLabel = sortLabel(currentSort()), viewer = viewer, plugin = plugin, actionModeCategory = null)

        SLOTS.forEachIndexed { index, slot ->
            inventory.setItem(slot, pageSongs.getOrNull(index)?.let(::songIcon))
        }
        inventory.setItem(BACK_SLOT, backButton())
    }

    private fun backButton() = GuiItemBuilder(Material.ARROW).name(Component.text("戻る", NamedTextColor.WHITE)).build()

    private fun sortLabel(sort: ReviewSort): String = when (sort) {
        ReviewSort.NEWEST -> "新着順"
        ReviewSort.OLDEST -> "古い順"
        ReviewSort.UNREVIEWED_OLDEST_FIRST -> "未審査古い順"
        ReviewSort.REVIEWED_NEWEST_FIRST -> "審査済新着順"
    }

    private fun statusLabel(status: SongStatus): String = when (status) {
        SongStatus.DRAFT -> "未審査"
        SongStatus.TEMP_OK -> "仮OK"
        SongStatus.PERMANENT_OK -> "永続OK"
        SongStatus.REJECTED -> "却下"
    }

    private fun songIcon(song: Song): org.bukkit.inventory.ItemStack {
        val reviewed = song.status != SongStatus.DRAFT
        val confirming = pendingRejectId == song.id
        val authorName = Bukkit.getOfflinePlayer(song.authorUuid).name ?: "不明"
        val lore = mutableListOf(
            Component.text("作者: $authorName", NamedTextColor.GRAY),
            Component.text("公開: ${if (song.published) "公開中" else "非公開"}", NamedTextColor.GRAY),
            Component.text("判定: ${statusLabel(song.status)}", if (reviewed) NamedTextColor.GREEN else NamedTextColor.YELLOW),
            Component.text("左:再生 Shift+左:詳細", NamedTextColor.DARK_GRAY),
            Component.text("右:許可(循環) Shift+右:却下", NamedTextColor.DARK_GRAY),
        )
        if (confirming) lore += Component.text("もう一度Shift+右クリックで却下確定", NamedTextColor.RED)

        return GuiItemBuilder(Material.matchMaterial(song.recordMaterial) ?: Material.MUSIC_DISC_13)
            .name(Component.text(song.title, NamedTextColor.WHITE))
            .lore(lore)
            .glint(reviewed)
            .build()
    }

    override fun onClick(event: InventoryClickEvent) {
        val slot = event.rawSlot
        if (slot == BACK_SLOT) {
            menuManager.openPrevious(viewer)
            return
        }
        if (!hasAccess()) return
        if (NavTabRouter.handle(slot, null, null, plugin, menuManager, viewer)) return
        if (plugin.playbackController.handleControllerClick(slot, viewer)) return

        when (slot) {
            ControllerSlots.SORT -> {
                sortIndex = (sortIndex + 1) % AVAILABLE_SORTS.size
                page = 0
                reload()
            }
            ControllerSlots.PAGE_PREV -> if (page > 0) { page--; reload() }
            ControllerSlots.PAGE_NEXT -> if (pageSongs.size == PAGE_SIZE) { page++; reload() }
            else -> {
                val index = SLOTS.indexOf(slot)
                if (index == -1) return
                val song = pageSongs.getOrNull(index) ?: return
                if (song.id != pendingRejectId) pendingRejectId = null
                handleSongClick(event, song)
            }
        }
    }

    private fun handleSongClick(event: InventoryClickEvent, song: Song) {
        when (event.click) {
            ClickType.SHIFT_LEFT -> menuManager.open(viewer, SongDetailScreen(plugin, menuManager, viewer, song))
            ClickType.RIGHT -> cycleApproval(song)
            ClickType.SHIFT_RIGHT -> confirmOrReject(song)
            else -> playSong(song)
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
                plugin.playbackController.play(viewer, song)
                // 試聴のみ目的のため視聴回数・報酬は計上しない（onListenThresholdReachedを渡さない）。
            })
        })
    }

    private fun cycleApproval(song: Song) {
        val next = when (song.status) {
            SongStatus.DRAFT -> SongStatus.TEMP_OK
            SongStatus.TEMP_OK -> SongStatus.PERMANENT_OK
            SongStatus.PERMANENT_OK -> SongStatus.DRAFT
            SongStatus.REJECTED -> SongStatus.TEMP_OK
        }
        applyStatus(song, next)
    }

    private fun confirmOrReject(song: Song) {
        if (pendingRejectId != song.id) {
            pendingRejectId = song.id
            render()
            return
        }
        pendingRejectId = null
        applyStatus(song, SongStatus.REJECTED)
    }

    private fun applyStatus(song: Song, status: SongStatus) {
        val songId = song.id ?: return
        Bukkit.getScheduler().runTaskAsynchronously(plugin, Runnable {
            plugin.songRepository.updateStatus(songId, status)
            Bukkit.getScheduler().runTask(plugin, Runnable {
                viewer.sendMessage("§a判定を更新しました: ${song.title} → ${statusLabel(status)}")
                val author = Bukkit.getPlayer(song.authorUuid)
                author?.sendMessage("§d[OyasaiMusic] §f「${song.title}」の審査結果: ${statusLabel(status)}")
                reload()
            })
        })
    }
}