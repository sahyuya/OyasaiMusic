package com.github.sahyuya.oyasaiMusic.gui

import com.github.sahyuya.oyasaiMusic.OyasaiMusic
import com.github.sahyuya.oyasaiMusic.util.HeadTextureUtil
import com.github.sahyuya.oyasaiMusic.model.Song
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.event.ClickEvent
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.event.inventory.InventoryClickEvent

/**
 * ⑤ 楽曲詳細画面（UI/UX設計書 6章、参照画像5枚目）。
 *
 * UI/UX設計書6章の機能定義:
 *   ホバー情報: 題名、作者、各種統計、参考リンク。
 *   クリックアクション: 参考リンクをチャット出力（クリックで飛べる）、作者プロフィールへ遷移、
 *                      いいね、お気に入り追加、フォロー。
 *
 * 【アイコン配置について（要確認）】
 * この画面のみ、実装時点で参照画像の細部（各アイコンの素材・正確な位置）を
 * 高い確信度で再確認できなかったため、設計書に明記された5つのクリックアクションと
 * 「戻る」ボタン(サヒュヤ氏指定: slot37)を優先し、アイコンは機能から逆算した妥当な案を
 * 割り当てている。実際の参照画像とズレがあれば教えてほしい。
 *
 * 背景は灰色板ガラス（外枠のみ、空きスロットに限る）。
 */
class SongDetailScreen(
    private val plugin: OyasaiMusic,
    private val menuManager: MenuManager,
    viewer: Player,
    initialSong: Song,
) : BaseGridMenu(viewer, Component.text("楽曲詳細")) {

    private val previewSlot = 11   // クリックで再生
    private val authorHeadSlot = 12 // クリックで作者プロフィール(作品一覧)へ
    private val followSlot = 13     // クリックでフォロー切替
    private val referenceUrlSlot = 20 // クリックで参考リンクをチャット出力
    private val likeSlot = 21         // クリックでいいね
    private val favoriteSlot = 22     // クリックでお気に入り/プレイリスト追加
    private val statsSlot = 23        // 統計表示（非インタラクティブ）
    private val settingsSlot = 24     // 作者/OPのみ: 楽曲設定画面へ（UI/UX設計書表には無いが実用上必要なため追加）
    private val backSlot = 37         // サヒュヤ氏指定: 戻る(矢)

    private var song: Song = initialSong
    private var isFollowing = false
    private var hasLiked = false

    init {
        render()
        loadSocialState()
    }

    override fun refresh() = render()

    private fun loadSocialState() {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, Runnable {
            val following = plugin.socialRepository.listFollowingUuids(viewer.uniqueId).contains(song.authorUuid)
            val liked = plugin.socialRepository.hasLiked(viewer.uniqueId, requireNotNull(song.id))
            Bukkit.getScheduler().runTask(plugin, Runnable {
                isFollowing = following
                hasLiked = liked
                render()
            })
        })
    }

    private fun render() {
        val state = plugin.controllerStateService.stateFor(viewer.uniqueId)
        GuiChrome.render(inventory, null, state, sortLabel = "-", viewer = viewer, actionModeCategory = null)

        inventory.setItem(previewSlot, previewItem())
        inventory.setItem(authorHeadSlot, authorHeadItem())
        inventory.setItem(followSlot, followItem())
        inventory.setItem(referenceUrlSlot, referenceUrlItem())
        inventory.setItem(likeSlot, likeItem())
        inventory.setItem(favoriteSlot, GuiItemBuilder(Material.CHISELED_BOOKSHELF)
            .name(Component.text("お気に入り/プレイリストに追加", NamedTextColor.YELLOW)).build())
        inventory.setItem(statsSlot, statsItem())
        if (song.authorUuid == viewer.uniqueId || viewer.hasPermission("oyasaimusic.admin")) {
            inventory.setItem(settingsSlot, GuiItemBuilder(Material.WRITABLE_BOOK)
                .name(Component.text("楽曲設定を開く", NamedTextColor.LIGHT_PURPLE)).build())
        } else {
            inventory.setItem(settingsSlot, null)
        }
        inventory.setItem(backSlot, GuiItemBuilder(Material.ARROW).name(Component.text("戻る", NamedTextColor.WHITE)).build())

        ContentGrid.fillBorderIfEmpty(inventory, Material.GRAY_STAINED_GLASS_PANE)
    }

    private fun previewItem() = GuiItemBuilder(Material.matchMaterial(song.recordMaterial) ?: Material.MUSIC_DISC_13)
        .name(Component.text(song.title, NamedTextColor.AQUA))
        .lore(
            Component.text("いいね: ${song.likes}  再生数: ${song.views}", NamedTextColor.GRAY),
            Component.text("BPM: ${song.bpm}", NamedTextColor.GRAY),
            Component.text("クリックで再生", NamedTextColor.DARK_GRAY),
        )
        .build()

    private fun authorHeadItem(): org.bukkit.inventory.ItemStack {
        val name = Bukkit.getOfflinePlayer(song.authorUuid).name ?: "不明"
        val item = HeadTextureUtil.placeholderHead(song.authorUuid, name)
        item.editMeta { meta ->
            meta.displayName(Component.text("作者: $name", NamedTextColor.GOLD))
            meta.lore(listOf(Component.text("クリックで作品一覧へ", NamedTextColor.GRAY)))
        }
        return item
    }

    private fun followItem() = GuiItemBuilder(Material.PLAYER_HEAD)
        .name(Component.text(if (isFollowing) "フォロー中" else "フォローする", if (isFollowing) NamedTextColor.GREEN else NamedTextColor.YELLOW))
        .lore(Component.text("クリックで切替", NamedTextColor.DARK_GRAY))
        .glint(isFollowing)
        .build()

    private fun referenceUrlItem() = GuiItemBuilder(Material.MAP)
        .name(Component.text("参考リンク", NamedTextColor.YELLOW))
        .lore(
            Component.text(song.referenceUrl ?: "未設定", NamedTextColor.GRAY),
            Component.text("クリックでチャットへ出力", NamedTextColor.DARK_GRAY),
        )
        .build()

    private fun likeItem() = GuiItemBuilder(Material.RED_DYE)
        .name(Component.text(if (hasLiked) "いいね済み" else "いいね", if (hasLiked) NamedTextColor.GREEN else NamedTextColor.YELLOW))
        .lore(Component.text("総いいね数: ${song.likes}", NamedTextColor.GRAY))
        .glint(hasLiked)
        .build()

    private fun statsItem() = GuiItemBuilder(Material.EMERALD)
        .name(Component.text("統計", NamedTextColor.GREEN))
        .lore(
            Component.text("いいね: ${song.likes}", NamedTextColor.GRAY),
            Component.text("再生数: ${song.views}", NamedTextColor.GRAY),
            Component.text("価格: ${song.price}円", NamedTextColor.GRAY),
        )
        .build()

    override fun onClick(event: InventoryClickEvent) {
        val slot = event.rawSlot
        if (slot == backSlot) {
            menuManager.openPrevious(viewer)
            return
        }
        if (NavTabRouter.handle(slot, null, null, plugin, menuManager, viewer)) return
        if (plugin.playbackController.handleControllerClick(slot, viewer)) return

        when (slot) {
            previewSlot -> plugin.playbackController.play(viewer, song)
            authorHeadSlot -> openAuthorProfile()
            followSlot -> toggleFollow()
            referenceUrlSlot -> outputReferenceUrl()
            likeSlot -> likeSong()
            favoriteSlot -> menuManager.open(viewer, PlaylistSelectionScreen(plugin, menuManager, viewer, song))
            settingsSlot -> if (song.authorUuid == viewer.uniqueId || viewer.hasPermission("oyasaimusic.admin")) {
                menuManager.open(viewer, SongSettingsScreen(plugin, menuManager, viewer, song))
            }
        }
    }

    private fun openAuthorProfile() {
        val name = Bukkit.getOfflinePlayer(song.authorUuid).name ?: "不明"
        menuManager.open(viewer, MainMenuScreens.authorWorks(plugin, menuManager, viewer, song.authorUuid, name))
    }

    private fun toggleFollow() {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, Runnable {
            if (isFollowing) {
                plugin.socialRepository.unfollow(viewer.uniqueId, song.authorUuid)
            } else {
                plugin.socialRepository.follow(viewer.uniqueId, song.authorUuid)
            }
            Bukkit.getScheduler().runTask(plugin, Runnable {
                isFollowing = !isFollowing
                viewer.sendMessage(if (isFollowing) "§aフォローしました。" else "§7フォローを解除しました。")
                render()
            })
        })
    }

    private fun outputReferenceUrl() {
        val url = song.referenceUrl
        if (url == null) {
            viewer.sendMessage("§7この楽曲には参考URLが設定されていません。")
            return
        }
        val message = Component.text("参考リンク: ", NamedTextColor.GOLD)
            .append(
                Component.text(url, NamedTextColor.AQUA)
                    .clickEvent(ClickEvent.openUrl(url)),
            )
        viewer.sendMessage(message)
    }

    private fun likeSong() {
        if (hasLiked) {
            viewer.sendMessage("§7既にいいね済みです。")
            return
        }
        Bukkit.getScheduler().runTaskAsynchronously(plugin, Runnable {
            val added = plugin.likeService.like(viewer.uniqueId, song)
            val refreshed = if (added) plugin.songRepository.findById(requireNotNull(song.id)) else null
            Bukkit.getScheduler().runTask(plugin, Runnable {
                if (added) {
                    hasLiked = true
                    if (refreshed != null) song = refreshed
                    viewer.sendMessage("§aいいねしました: ${song.title}")
                    render()
                } else {
                    viewer.sendMessage("§7既にいいね済みです。")
                }
            })
        })
    }
}