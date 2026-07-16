package com.github.sahyuya.oyasaiMusic.gui

import com.github.sahyuya.oyasaiMusic.OyasaiMusic
import com.github.sahyuya.oyasaiMusic.model.Song
import com.github.sahyuya.oyasaiMusic.model.SongStatus
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.event.inventory.InventoryClickEvent
import java.io.File

/**
 * ⑦ 楽曲設定画面（作者・OP専用、UI/UX設計書 8章、参照画像7枚目）。
 * 設定可能項目: 公開、題名、BPM、レコードの種類、レコード価格、参考URL、オリジナル審査提出、楽曲削除。
 *
 * 【スロット配置（サヒュヤ氏の指示＋参照画像から確定/推定）】
 *   slot11: 現在のレコードのプレビュー（読み取り専用）
 *   slot12: オリジナル審査提出　※参照画像では扉アイコンに見えたが、8設定項目に合わせてここへ割当（要確認）
 *   slot20: 題名（サイン、Anvil入力）
 *   slot21: BPM（ロケット花火、Anvil数値入力）
 *   slot22: レコードの種類（クリックで循環）
 *   slot23: レコード価格（エメラルド、Anvil数値入力）
 *   slot24: 参考URL（本、Book-and-Quill入力）
 *   slot25: 公開切替（トグル。下記TODO参照）
 *   slot37: 戻る（矢、サヒュヤ氏指定の座標(1,4)＝コンテンツ領域左下）
 *   slot44: 楽曲削除（TNT、2回クリックで確定）
 *
 * 【公開とオリジナル審査の関係（サヒュヤ氏の指示により確定）】
 * 「公開」は`songs.published`という審査ステータス(`status`)とは独立したカラムで管理する。
 * プレイヤーは`published`を自由にON/OFFでき、一覧・検索・ランキング等は`published=true`のみを対象とする。
 * `status`(下書き/仮OK/永続OK/却下)はOPによる「オリジナル審査」の結果として引き続き別管理し、
 * 収益化（視聴ポイント・レコード売上還元）の可否にのみ使用する（[Song.isMonetizationEligible]）。
 */
class SongSettingsScreen(
    private val plugin: OyasaiMusic,
    private val menuManager: MenuManager,
    viewer: Player,
    initialSong: Song,
) : BaseGridMenu(viewer, Component.text("楽曲設定")) {

    private val previewSlot = 11
    private val submitReviewSlot = 12
    private val titleSlot = 20
    private val bpmSlot = 21
    private val recordTypeSlot = 22
    private val priceSlot = 23
    private val urlSlot = 24
    private val publishSlot = 25
    private val backSlot = 37
    private val deleteSlot = 44

    private var song: Song = initialSong
    private var pendingDeleteConfirm = false

    companion object {
        // vanilla音楽レコードの循環候補。存在しないバージョンでは自動的に読み飛ばす。
        private val RECORD_MATERIAL_CYCLE = listOf(
            "MUSIC_DISC_13", "MUSIC_DISC_CAT", "MUSIC_DISC_BLOCKS", "MUSIC_DISC_CHIRP", "MUSIC_DISC_FAR",
            "MUSIC_DISC_MALL", "MUSIC_DISC_MELLOHI", "MUSIC_DISC_STAL", "MUSIC_DISC_STRAD", "MUSIC_DISC_WARD",
            "MUSIC_DISC_11", "MUSIC_DISC_WAIT", "MUSIC_DISC_PIGSTEP", "MUSIC_DISC_OTHERSIDE", "MUSIC_DISC_5",
            "MUSIC_DISC_RELIC", "MUSIC_DISC_CREATOR", "MUSIC_DISC_CREATOR_MUSIC_BOX", "MUSIC_DISC_PRECIPICE",
            "MUSIC_DISC_TEARS", "MUSIC_DISC_LAVA_CHICKEN",
        )
    }

    init {
        render()
    }

    private fun hasAccess(): Boolean = song.authorUuid == viewer.uniqueId || viewer.hasPermission("oyasaimusic.admin")

    private fun render() {
        val state = plugin.controllerStateService.stateFor(viewer.uniqueId)
        GuiChrome.render(inventory, null, state, sortLabel = "-")
        ContentGrid.fill(inventory, Material.GRAY_STAINED_GLASS_PANE)

        if (!hasAccess()) {
            inventory.setItem(
                previewSlot,
                GuiItemBuilder(Material.BARRIER)
                    .name(Component.text("編集権限がありません", NamedTextColor.RED))
                    .build(),
            )
            inventory.setItem(backSlot, backButton())
            return
        }

        inventory.setItem(previewSlot, previewItem())
        inventory.setItem(submitReviewSlot, submitReviewItem())
        inventory.setItem(titleSlot, GuiItemBuilder(Material.OAK_SIGN).name(Component.text("題名を変更", NamedTextColor.YELLOW))
            .lore(Component.text("現在: ${song.title}", NamedTextColor.GRAY)).build())
        inventory.setItem(bpmSlot, GuiItemBuilder(Material.FIREWORK_ROCKET).name(Component.text("BPM(再生速度)を変更", NamedTextColor.YELLOW))
            .lore(Component.text("現在: ${song.bpm}", NamedTextColor.GRAY)).build())
        inventory.setItem(recordTypeSlot, recordTypeItem())
        inventory.setItem(priceSlot, GuiItemBuilder(Material.EMERALD).name(Component.text("レコード価格を変更", NamedTextColor.YELLOW))
            .lore(Component.text("現在: ${song.price}円", NamedTextColor.GRAY)).build())
        inventory.setItem(urlSlot, GuiItemBuilder(Material.WRITTEN_BOOK).name(Component.text("参考URLを設定", NamedTextColor.YELLOW))
            .lore(Component.text("現在: ${song.referenceUrl ?: "未設定"}", NamedTextColor.GRAY)).build())
        inventory.setItem(publishSlot, publishItem())
        inventory.setItem(backSlot, backButton())
        inventory.setItem(deleteSlot, deleteItem())
    }

    private fun previewItem() = GuiItemBuilder(Material.matchMaterial(song.recordMaterial) ?: Material.MUSIC_DISC_13)
        .name(Component.text(song.title, NamedTextColor.AQUA))
        .lore(
            Component.text("ステータス: ${statusLabel(song.status)}", NamedTextColor.GRAY),
            Component.text("いいね: ${song.likes}  再生数: ${song.views}", NamedTextColor.GRAY),
        )
        .build()

    private fun submitReviewItem() = GuiItemBuilder(Material.PAPER)
        .name(Component.text("オリジナル審査を提出", NamedTextColor.LIGHT_PURPLE))
        .lore(
            Component.text("クリックでOPへ審査依頼を通知します", NamedTextColor.GRAY),
            Component.text("(現状: 審査キューは未実装のためチャット通知のみ)", NamedTextColor.DARK_GRAY),
        )
        .build()

    private fun recordTypeItem() = GuiItemBuilder(Material.matchMaterial(song.recordMaterial) ?: Material.MUSIC_DISC_13)
        .name(Component.text("レコードの種類を変更", NamedTextColor.YELLOW))
        .lore(Component.text("現在: ${song.recordMaterial}", NamedTextColor.GRAY), Component.text("クリックで次の種類へ", NamedTextColor.DARK_GRAY))
        .build()

    private fun publishItem(): org.bukkit.inventory.ItemStack {
        return GuiItemBuilder(if (song.published) Material.LIME_DYE else Material.GRAY_DYE)
            .name(Component.text(if (song.published) "公開中" else "非公開(下書き)", if (song.published) NamedTextColor.GREEN else NamedTextColor.GRAY))
            .lore(Component.text("クリックで切替", NamedTextColor.DARK_GRAY))
            .glint(song.published)
            .build()
    }

    private fun backButton() = GuiItemBuilder(Material.ARROW).name(Component.text("戻る", NamedTextColor.WHITE)).build()

    private fun deleteItem(): org.bukkit.inventory.ItemStack {
        val builder = GuiItemBuilder(Material.TNT)
        return if (pendingDeleteConfirm) {
            builder.name(Component.text("本当に削除しますか？", NamedTextColor.RED))
                .lore(Component.text("もう一度クリックで削除確定", NamedTextColor.RED))
                .glint(true)
                .build()
        } else {
            builder.name(Component.text("楽曲を削除", NamedTextColor.RED))
                .lore(Component.text("クリックで削除確認へ", NamedTextColor.GRAY))
                .build()
        }
    }

    private fun statusLabel(status: SongStatus): String = when (status) {
        SongStatus.DRAFT -> "下書き"
        SongStatus.TEMP_OK -> "仮OK"
        SongStatus.PERMANENT_OK -> "永続OK"
        SongStatus.REJECTED -> "却下"
    }

    override fun onClick(event: InventoryClickEvent) {
        if (!hasAccess()) {
            if (event.rawSlot == backSlot) menuManager.openPrevious(viewer)
            return
        }
        val slot = event.rawSlot
        if (slot != deleteSlot) pendingDeleteConfirm = false
        if (NavTabRouter.handle(slot, null, plugin, menuManager, viewer)) return

        when (slot) {
            backSlot -> menuManager.openPrevious(viewer)
            submitReviewSlot -> submitForReview()
            titleSlot -> editTitle()
            bpmSlot -> editBpm()
            recordTypeSlot -> cycleRecordType()
            priceSlot -> editPrice()
            urlSlot -> editUrl()
            publishSlot -> togglePublish()
            deleteSlot -> handleDeleteClick()
        }
    }

    private fun editTitle() {
        AnvilTextInput.open(plugin, viewer, Component.text("題名を変更"), initialText = song.title) { newTitle ->
            Bukkit.getScheduler().runTaskAsynchronously(plugin, Runnable {
                plugin.songRepository.updateSettings(id = requireNotNull(song.id), title = newTitle)
                Bukkit.getScheduler().runTask(plugin, Runnable { reloadAndReopen("題名を変更しました: $newTitle") })
            })
        }
    }

    private fun editBpm() {
        AnvilTextInput.open(plugin, viewer, Component.text("BPMを変更"), initialText = song.bpm.toString()) { text ->
            val bpm = text.toIntOrNull()
            if (bpm == null || bpm <= 0) {
                Bukkit.getScheduler().runTask(plugin, Runnable {
                    viewer.sendMessage("§cBPMは正の整数で入力してください。")
                    menuManager.open(viewer, this, rememberAsPrevious = false)
                })
                return@open
            }
            Bukkit.getScheduler().runTaskAsynchronously(plugin, Runnable {
                plugin.songRepository.updateSettings(id = requireNotNull(song.id), bpm = bpm)
                Bukkit.getScheduler().runTask(plugin, Runnable { reloadAndReopen("BPMを変更しました: $bpm") })
            })
        }
    }

    private fun editPrice() {
        AnvilTextInput.open(plugin, viewer, Component.text("レコード価格を変更"), initialText = song.price.toString()) { text ->
            val price = text.toIntOrNull()
            if (price == null || price < 0) {
                Bukkit.getScheduler().runTask(plugin, Runnable {
                    viewer.sendMessage("§c価格は0以上の整数で入力してください。")
                    menuManager.open(viewer, this, rememberAsPrevious = false)
                })
                return@open
            }
            Bukkit.getScheduler().runTaskAsynchronously(plugin, Runnable {
                plugin.songRepository.updateSettings(id = requireNotNull(song.id), price = price)
                Bukkit.getScheduler().runTask(plugin, Runnable { reloadAndReopen("価格を変更しました: $price 円") })
            })
        }
    }

    private fun editUrl() {
        BookQuillUrlInput.open(plugin, viewer) { url ->
            Bukkit.getScheduler().runTaskAsynchronously(plugin, Runnable {
                plugin.songRepository.updateSettings(id = requireNotNull(song.id), referenceUrl = url)
                Bukkit.getScheduler().runTask(plugin, Runnable { reloadAndReopen("参考URLを設定しました。") })
            })
        }
    }

    private fun cycleRecordType() {
        val validMaterials = RECORD_MATERIAL_CYCLE.filter { Material.matchMaterial(it) != null }
        if (validMaterials.isEmpty()) return
        val currentIndex = validMaterials.indexOf(song.recordMaterial).takeIf { it >= 0 } ?: -1
        val next = validMaterials[(currentIndex + 1) % validMaterials.size]
        Bukkit.getScheduler().runTaskAsynchronously(plugin, Runnable {
            plugin.songRepository.updateSettings(id = requireNotNull(song.id), recordMaterial = next)
            Bukkit.getScheduler().runTask(plugin, Runnable { reloadAndReopen(null) })
        })
    }

    private fun togglePublish() {
        val newPublished = !song.published
        Bukkit.getScheduler().runTaskAsynchronously(plugin, Runnable {
            plugin.songRepository.setPublished(requireNotNull(song.id), newPublished)
            Bukkit.getScheduler().runTask(plugin, Runnable {
                reloadAndReopen(if (newPublished) "公開しました。" else "非公開(下書き)に戻しました。")
            })
        })
    }

    private fun submitForReview() {
        viewer.sendMessage("§aOPへ審査依頼を送信しました: ${song.title}")
        val notice = "§d[OyasaiMusic] §f${viewer.name} が「${song.title}」の審査を依頼しました。(楽曲ID: ${song.id})"
        Bukkit.getOnlinePlayers().filter { it.hasPermission("oyasaimusic.admin") }.forEach { it.sendMessage(notice) }
        // TODO: 審査キュー(未審査/審査済)を管理するテーブルが無いため、現状はチャット通知のみ。
        // OP審査・履歴管理GUIの実装時に、依頼日時等を保持する専用カラム/テーブルの追加を検討する。
    }

    private fun handleDeleteClick() {
        if (!pendingDeleteConfirm) {
            pendingDeleteConfirm = true
            render()
            return
        }
        val songId = requireNotNull(song.id)
        val fileName = song.fileName
        Bukkit.getScheduler().runTaskAsynchronously(plugin, Runnable {
            plugin.songRepository.delete(songId)
            runCatching { File(plugin.audioDirectory, fileName).delete() }
            Bukkit.getScheduler().runTask(plugin, Runnable {
                viewer.sendMessage("§a楽曲を削除しました: ${song.title}")
                menuManager.openPrevious(viewer)
            })
        })
    }

    /** DB更新後、最新のSongを取得してこの画面を再構築する（インスタンスは使い回さず新規に開き直す）。 */
    private fun reloadAndReopen(message: String?) {
        val songId = requireNotNull(song.id)
        Bukkit.getScheduler().runTaskAsynchronously(plugin, Runnable {
            val refreshed = plugin.songRepository.findById(songId)
            Bukkit.getScheduler().runTask(plugin, Runnable {
                if (message != null) viewer.sendMessage("§a$message")
                if (refreshed != null) {
                    menuManager.open(viewer, SongSettingsScreen(plugin, menuManager, viewer, refreshed), rememberAsPrevious = false)
                }
            })
        })
    }
}