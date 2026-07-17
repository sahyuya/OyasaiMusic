package com.github.sahyuya.oyasaiMusic.gui

import com.github.sahyuya.oyasaiMusic.OyasaiMusic
import com.github.sahyuya.oyasaiMusic.db.RankingEntryDto
import com.github.sahyuya.oyasaiMusic.db.RankingMetric
import com.github.sahyuya.oyasaiMusic.db.RankingSnapshot
import com.github.sahyuya.oyasaiMusic.util.HeadTextureUtil
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.inventory.ItemStack

/**
 * ⓪ メインメニュー（UI/UX設計書 2章、参照画像1枚目）。
 * 左列タブを何も選んでいない初期状態、および物理アイテム「音楽プレイヤー」の
 * Shift+右クリックで表示される。
 *
 * ランキング表示（サヒュヤ氏の指示に基づく仕様）:
 *   - 3枠(額縁, slot 21,22,23) = 左から 日間 / 週間 / 総合 ランキング。
 *   - 各枠は独立して現在の指標(いいね/再生数/お気に入り/フォロワー)を持ち、
 *     既定はいいね数順。クリックで自分の枠だけ指標を切替（いいね→再生数→お気に入り→フォロワー→…）。
 *   - 各枠のアイテム名=期間+現在の指標、Loreに1〜7位を列挙する
 *     （楽曲系: "{順位} {mcid} - 「{題名}」 {回数}回" / フォロワー: "{順位} {mcid} - {人数}フォロワー"）。
 *   - 実データは [OyasaiMusic.rankingCacheService] のキャッシュ済みスナップショットをそのまま読む
 *     （日間=毎日0時に前日分、週間=毎週月曜0時に前週分、総合=30分ごとに再集計）。
 */
class MainMenuScreen(
    private val plugin: OyasaiMusic,
    private val menuManager: MenuManager,
    viewer: Player,
) : BaseGridMenu(viewer, Component.text("メインメニュー")) {

    private enum class RankingColumn(val slot: Int, val label: String) {
        DAILY(21, "日間"),
        WEEKLY(22, "週間"),
        TOTAL(23, "総合"),
    }

    // 各列は独立して現在の指標を保持する（既定=いいね数）。
    private val columnMetric = mutableMapOf(
        RankingColumn.DAILY to RankingMetric.LIKES,
        RankingColumn.WEEKLY to RankingMetric.LIKES,
        RankingColumn.TOTAL to RankingMetric.LIKES,
    )

    private val claimSlot = 24
    private val adminEntrySlot = 26

    init {
        render()
    }

    private fun render() {
        val state = plugin.controllerStateService.stateFor(viewer.uniqueId)
        GuiChrome.render(inventory, null, state, sortLabel = "-")
        renderRankingRow()
        ContentGrid.fillBorderIfEmpty(inventory, Material.WHITE_STAINED_GLASS_PANE)
    }

    private fun renderRankingRow() {
        for (column in RankingColumn.entries) {
            val snapshot = snapshotFor(column)
            val metric = columnMetric.getValue(column)
            inventory.setItem(column.slot, rankingItem(column, metric, snapshot))
        }

        inventory.setItem(
            claimSlot,
            GuiItemBuilder(Material.BUNDLE)
                .name(Component.text("未受け取り報酬を受け取る", NamedTextColor.GREEN))
                .lore(Component.text("クリックで一括受取", NamedTextColor.GRAY))
                .build(),
        )

        if (viewer.hasPermission("oyasaimusic.admin")) {
            inventory.setItem(
                adminEntrySlot,
                GuiItemBuilder(Material.WRITABLE_BOOK)
                    .name(Component.text("審査・履歴管理", NamedTextColor.LIGHT_PURPLE))
                    .lore(Component.text("【OP専用】クリックで開く", NamedTextColor.GRAY))
                    .build(),
            )
        } else {
            inventory.setItem(adminEntrySlot, null)
        }
    }

    private fun snapshotFor(column: RankingColumn): RankingSnapshot? = when (column) {
        RankingColumn.DAILY -> plugin.rankingCacheService.dailySnapshot
        RankingColumn.WEEKLY -> plugin.rankingCacheService.weeklySnapshot
        RankingColumn.TOTAL -> plugin.rankingCacheService.totalSnapshot
    }

    private fun rankingItem(column: RankingColumn, metric: RankingMetric, snapshot: RankingSnapshot?): ItemStack {
        val metricLabel = when (metric) {
            RankingMetric.LIKES -> "いいね数"
            RankingMetric.VIEWS -> "再生数"
            RankingMetric.FAVORITES -> "お気に入り数"
            RankingMetric.FOLLOWERS -> "フォロワー数"
        }
        val entries: List<RankingEntryDto> = snapshot?.entries?.get(metric).orEmpty()

        val lore = mutableListOf(
            Component.text("指標: $metricLabel", NamedTextColor.GRAY),
        )
        if (snapshot != null) {
            lore += Component.text("(${snapshot.periodLabel} 時点)", NamedTextColor.DARK_GRAY)
        }
        if (entries.isEmpty()) {
            lore += Component.text("データがありません", NamedTextColor.DARK_GRAY)
        } else {
            entries.forEach { entry ->
                val line = if (metric == RankingMetric.FOLLOWERS) {
                    "${entry.rank} ${entry.authorName} - ${entry.score}フォロワー"
                } else {
                    "${entry.rank} ${entry.authorName} - 「${entry.title}」 ${entry.score}回"
                }
                lore += Component.text(line, NamedTextColor.WHITE)
            }
        }
        lore += Component.text("クリックで指標を切替", NamedTextColor.DARK_GRAY)

        val name = Component.text("${column.label}ランキング ($metricLabel)", NamedTextColor.GOLD)

        // フォロワーランキングは1位作者のプレイヤーヘッドを、それ以外は汎用ディスクをアイコンにする。
        // NOTE: RankingEntryDtoにレコード素材(recordMaterial)を持たせていないため、
        // 楽曲系ランキングのアイコンは1位の実際のレコード種類ではなく汎用ディスクで代替している
        // （見た目を実際のレコード種類に合わせたい場合はDTOの拡張が必要）。
        if (metric == RankingMetric.FOLLOWERS) {
            val topAuthorUuid = entries.firstOrNull()?.let { runCatching { java.util.UUID.fromString(it.authorUuid) }.getOrNull() }
            val head = if (topAuthorUuid != null) {
                HeadTextureUtil.placeholderHead(topAuthorUuid, entries.first().authorName)
            } else {
                ItemStack(Material.PLAYER_HEAD)
            }
            head.editMeta { meta ->
                meta.displayName(name)
                meta.lore(lore)
            }
            return head
        }

        return GuiItemBuilder(if (entries.isEmpty()) Material.ITEM_FRAME else Material.MUSIC_DISC_13)
            .name(name)
            .lore(lore)
            .build()
    }

    override fun onClick(event: InventoryClickEvent) {
        val slot = event.rawSlot
        val column = RankingColumn.entries.firstOrNull { it.slot == slot }
        if (column != null) {
            cycleMetric(column)
            return
        }
        if (NavTabRouter.handle(slot, null, plugin, menuManager, viewer)) return
        when (slot) {
            claimSlot -> claimRewards()
            adminEntrySlot -> if (viewer.hasPermission("oyasaimusic.admin")) {
                viewer.sendMessage("§e審査・履歴管理GUIは近日実装予定です。")
            }
            ControllerSlots.LOOP -> toggleLoop()
            ControllerSlots.SHUFFLE -> toggleShuffle()
        }
    }

    private fun cycleMetric(column: RankingColumn) {
        val values = RankingMetric.entries
        val current = columnMetric.getValue(column)
        columnMetric[column] = values[(values.indexOf(current) + 1) % values.size]
        render()
    }

    private fun claimRewards() {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, Runnable {
            val claimed = plugin.userRepository.claim(viewer.uniqueId)
            Bukkit.getScheduler().runTask(plugin, Runnable {
                if (claimed.pendingMoney == 0L && claimed.pendingPoints == 0L) {
                    viewer.sendMessage("§7受け取れる報酬はありません。")
                } else {
                    viewer.sendMessage("§a受け取りました: §f${claimed.pendingMoney}円 / ${claimed.pendingPoints}pt")
                    // TODO: Vault/TokenManager連携（UI/UX設計書7章）はGUIフェーズの別タスクとして接続する。
                }
            })
        })
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