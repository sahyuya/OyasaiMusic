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
    private val positionalModeSlot = 14 // 通常/立体音響再生の選択（追加項目.txt対応。楽曲にPan指定が無い場合は選択不可）
    private val referenceUrlSlot = 20 // クリックで参考リンクをチャット出力
    private val likeSlot = 21         // クリックでいいね
    private val favoriteSlot = 22     // クリックでお気に入り/プレイリスト追加
    private val statsSlot = 23        // 統計表示（非インタラクティブ）
    private val settingsSlot = 24     // 作者/OPのみ: 楽曲設定画面へ（UI/UX設計書表には無いが実用上必要なため追加）
    private val buyRecordSlot = 25    // レコードを購入（サヒュヤ氏の指示で追加。UI/UX設計書7章のレコード販売に対応）
    private val backSlot = 37         // サヒュヤ氏指定: 戻る(矢)

    private var song: Song = initialSong
    private var isFollowing = false
    private var hasLiked = false
    private var currentPlaybackMode: com.github.sahyuya.oyasaiMusic.audio.PlaybackMode = com.github.sahyuya.oyasaiMusic.audio.PlaybackMode.DEFAULT

    init {
        render()
        loadSocialState()
    }

    override fun refresh() = render()

    private fun loadSocialState() {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, Runnable {
            val following = plugin.socialRepository.listFollowingUuids(viewer.uniqueId).contains(song.authorUuid)
            val liked = plugin.socialRepository.hasLiked(viewer.uniqueId, requireNotNull(song.id))
            val mode = plugin.playbackModeService.resolve(viewer.uniqueId, song)
            Bukkit.getScheduler().runTask(plugin, Runnable {
                isFollowing = following
                hasLiked = liked
                currentPlaybackMode = mode
                render()
            })
        })
    }

    private fun render() {
        val state = plugin.controllerStateService.stateFor(viewer.uniqueId)
        GuiChrome.render(inventory, null, state, sortLabel = "-", viewer = viewer, plugin = plugin, actionModeCategory = null)

        inventory.setItem(previewSlot, previewItem(state))
        inventory.setItem(authorHeadSlot, authorHeadItem())
        inventory.setItem(followSlot, followItem())
        inventory.setItem(positionalModeSlot, positionalModeItem())
        inventory.setItem(referenceUrlSlot, referenceUrlItem())
        inventory.setItem(likeSlot, likeItem())
        inventory.setItem(favoriteSlot, GuiItemBuilder(Material.CHISELED_BOOKSHELF)
            .name(Component.text("お気に入り/プレイリストに追加", NamedTextColor.YELLOW)).build())
        inventory.setItem(statsSlot, statsItem())
        inventory.setItem(buyRecordSlot, buyRecordItem())
        if (song.authorUuid == viewer.uniqueId || viewer.hasPermission("oyasaimusic.admin")) {
            inventory.setItem(settingsSlot, GuiItemBuilder(Material.WRITABLE_BOOK)
                .name(Component.text("楽曲設定を開く", NamedTextColor.LIGHT_PURPLE)).build())
        } else {
            inventory.setItem(settingsSlot, null)
        }
        inventory.setItem(backSlot, GuiItemBuilder(Material.ARROW).name(Component.text("戻る", NamedTextColor.WHITE)).build())

        ContentGrid.fillBorderIfEmpty(inventory, Material.GRAY_STAINED_GLASS_PANE)
    }

    private fun previewItem(state: com.github.sahyuya.oyasaiMusic.gui.PlayerControllerState): org.bukkit.inventory.ItemStack {
        val nowPlaying = state.isPlaying && state.nowPlayingSong?.id == song.id
        return GuiItemBuilder(Material.matchMaterial(song.recordMaterial) ?: Material.MUSIC_DISC_13)
            .name(Component.text(song.title, NamedTextColor.AQUA))
            .lore(
                Component.text("いいね: ${song.likes}  再生数: ${song.views}", NamedTextColor.GRAY),
                Component.text("BPM: ${song.bpm}", NamedTextColor.GRAY),
                Component.text("クリックで再生", NamedTextColor.DARK_GRAY),
                *(if (nowPlaying) arrayOf(Component.text("♪ 再生中", NamedTextColor.GREEN)) else emptyArray()),
            )
            .glint(nowPlaying)
            .build()
    }

    /**
     * 「レコードを購入」（UI/UX設計書7章）: レコード素材の実アイテム(PDCで楽曲IDを紐付け)を渡し、
     * 販売価格の8割を作者の未受取残高へ加算する。
     *
     * 【要確認】買い手側からの実際の代金徴収(Vault/TokenManager連携)はまだ配線していないため、
     * 現状は「購入」を押すと代金なしでアイテムが渡り作者への還元だけが発生する暫定実装になっている。
     * 経済プラグイン連携の実装方針が固まり次第、買い手からの引き落とし処理を追加する想定。
     */
    private fun buyRecordItem() = GuiItemBuilder(Material.matchMaterial(song.recordMaterial) ?: Material.MUSIC_DISC_13)
        .name(Component.text("レコードを購入", NamedTextColor.GOLD))
        .lore(
            Component.text("価格: ${song.price}円", NamedTextColor.GRAY),
            Component.text("クリックで購入", NamedTextColor.DARK_GRAY),
            Component.text("(経済プラグイン連携は準備中: 現在は代金がかかりません)", NamedTextColor.DARK_GRAY),
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

    /**
     * 通常(デフォルト)再生 / 立体音響再生の選択（追加項目.txt対応）。
     * 「立体音響再生は楽曲詳細GUIを開いた際に、個々のリスナーが…選べて、その再生方法の選択を
     * 保存する。ただし、その楽曲にPanの指定がない場合は通常再生のみ可能。」に準拠し、
     * [Song.supportsPositional] がfalseの楽曲ではクリックしても切り替わらない。
     */
    private fun positionalModeItem(): org.bukkit.inventory.ItemStack {
        val positional = currentPlaybackMode == com.github.sahyuya.oyasaiMusic.audio.PlaybackMode.POSITIONAL
        if (!song.supportsPositional) {
            return GuiItemBuilder(Material.GRAY_DYE)
                .name(Component.text("通常再生のみ対応", NamedTextColor.DARK_GRAY))
                .lore(Component.text("この楽曲にはPan指定がありません", NamedTextColor.DARK_GRAY))
                .build()
        }
        return GuiItemBuilder(if (positional) Material.ENDER_EYE else Material.ENDER_PEARL)
            .name(Component.text(if (positional) "立体音響再生" else "通常再生", NamedTextColor.LIGHT_PURPLE))
            .lore(
                Component.text("クリックで切替", NamedTextColor.DARK_GRAY),
                Component.text("(この選択は自分専用に保存されます)", NamedTextColor.DARK_GRAY),
            )
            .glint(positional)
            .build()
    }

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
            previewSlot -> playSong()
            authorHeadSlot -> openAuthorProfile()
            followSlot -> toggleFollow()
            positionalModeSlot -> togglePlaybackMode()
            referenceUrlSlot -> outputReferenceUrl()
            likeSlot -> likeSong()
            favoriteSlot -> menuManager.open(viewer, PlaylistSelectionScreen(plugin, menuManager, viewer, song))
            buyRecordSlot -> buyRecord()
            settingsSlot -> if (song.authorUuid == viewer.uniqueId || viewer.hasPermission("oyasaimusic.admin")) {
                menuManager.open(viewer, SongSettingsScreen(plugin, menuManager, viewer, song))
            }
        }
    }

    /** ループ=1曲の場合は再生完了後に同じ曲を再生し直す（サヒュヤ氏の指示「ループはどこでも」対応）。 */
    private fun playSong() {
        plugin.playbackController.play(viewer, song, onCompletion = {
            val state = plugin.controllerStateService.stateFor(viewer.uniqueId)
            if (state.loopMode == com.github.sahyuya.oyasaiMusic.gui.LoopMode.SINGLE) playSong()
        })
    }

    /**
     * レコードを購入する（詳細は [buyRecordItem] のコメント参照）。
     * Vault未連携のため、現状は買い手からの引き落としは行わず、アイテム付与と
     * 作者への還元(価格の8割をpending_moneyへ加算)のみ行う。
     */
    private fun buyRecord() {
        val songId = song.id ?: return
        val material = Material.matchMaterial(song.recordMaterial) ?: Material.MUSIC_DISC_13
        val authorName = Bukkit.getOfflinePlayer(song.authorUuid).name ?: "不明"
        val item = com.github.sahyuya.oyasaiMusic.PhysicalRecordItem.create(plugin, material, songId, song.title, authorName)
        val leftover = viewer.inventory.addItem(item)
        if (leftover.isNotEmpty()) {
            viewer.world.dropItemNaturally(viewer.location, item)
        }

        val authorShare = (song.price * plugin.config.getDouble("economy.record-sale-author-share", 0.8)).toLong()
        Bukkit.getScheduler().runTaskAsynchronously(plugin, Runnable {
            if (song.isMonetizationEligible()) {
                plugin.userRepository.addPending(song.authorUuid, money = authorShare)
            }
        })
        viewer.sendMessage("§aレコードを受け取りました: ${song.title}")
        viewer.sendMessage("§7(経済プラグイン連携は準備中のため、現在は代金がかかりません)")
        viewer.sendMessage("§7Shift+右クリックで環境BGM設定（再生範囲/トリガー/ループ）を変更できます。")
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

    private fun togglePlaybackMode() {
        if (!song.supportsPositional) {
            viewer.sendMessage("§7この楽曲は通常再生のみ対応しています。")
            return
        }
        val next = if (currentPlaybackMode == com.github.sahyuya.oyasaiMusic.audio.PlaybackMode.POSITIONAL) {
            com.github.sahyuya.oyasaiMusic.audio.PlaybackMode.DEFAULT
        } else {
            com.github.sahyuya.oyasaiMusic.audio.PlaybackMode.POSITIONAL
        }
        Bukkit.getScheduler().runTaskAsynchronously(plugin, Runnable {
            plugin.playbackModeService.setPreference(viewer.uniqueId, song, next)
            Bukkit.getScheduler().runTask(plugin, Runnable {
                currentPlaybackMode = next
                viewer.sendMessage("§a再生方式を変更しました: ${if (next == com.github.sahyuya.oyasaiMusic.audio.PlaybackMode.POSITIONAL) "立体音響再生" else "通常再生"}")
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