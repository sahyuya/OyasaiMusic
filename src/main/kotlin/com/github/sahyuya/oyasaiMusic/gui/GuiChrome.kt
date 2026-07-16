package com.github.sahyuya.oyasaiMusic.gui

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Material
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.ItemStack
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** 左列ナビゲーションタブ（UI/UX設計書 2章）。値の`slot`は6×9グリッドでの列0の行位置。 */
enum class NavTab(val slot: Int) {
    MY_SONGS(0),             // ① プレイヤースキン頭：自作楽曲一覧
    SEARCH(9),                // ② 赤色ブロック：検索メニュー
    ALL_SONGS(18),             // ③ 黄色ブロック：全楽曲一覧
    FAVORITES_PLAYLISTS(27),    // ④ 緑色ブロック：お気に入り＆プレイリスト
    ACTION_MODE(36),            // ⑤ 水色ブロック：アクションモード切り替え（主に統合版用）
}

/** コンテンツ領域（5×8=40スロット）: 各行の1〜8列目。 */
object ContentGrid {
    val SLOTS: List<Int> = (0..4).flatMap { row -> (1..8).map { col -> row * 9 + col } }

    /**
     * コンテンツ領域を板ガラスで埋める（サヒュヤ氏の指示: 参照画像の背景装飾を再現）。
     * 実際のコンテンツはこの後で個別に上書きする想定。
     *
     * 色の使い分け（参照画像からの解釈、要確認）:
     *   - 検索画面(赤タブ) → RED_STAINED_GLASS_PANE
     *   - お気に入り♪プレイリスト関連(緑タブ) → LIME_STAINED_GLASS_PANE
     *   - それ以外(一覧・詳細・設定等、コンテンツ密度が高い画面) → GRAY_STAINED_GLASS_PANE
     */
    fun fill(inventory: Inventory, material: Material = Material.GRAY_STAINED_GLASS_PANE) {
        val pane = GuiItemBuilder.filler(material)
        SLOTS.forEach { inventory.setItem(it, pane) }
    }
}

/** 下段メディアコントローラーのスロット（UI/UX設計書 3章）。 */
object ControllerSlots {
    const val SORT = 45
    const val PAGE_PREV = 46
    const val PAGE_NEXT = 47
    const val NOW_PLAYING = 48
    const val PLAY_PAUSE = 49
    const val PREV_SONG = 50
    const val NEXT_SONG = 51
    const val LOOP = 52
    const val SHUFFLE = 53
}

/** ループモード（UI/UX設計書 3章：OFF / リスト全体 / 1曲）。 */
enum class LoopMode { OFF, LIST, SINGLE }

/**
 * 全画面共通のコントローラー表示状態（再生中情報・ループ・シャッフル）。
 * 現時点ではGUI側の表示状態のみを保持しており、[com.github.sahyuya.oyasaiMusic.audio.PlaybackEngine]の
 * 実セッションとの自動同期（再生終了時に自動でisPlaying=falseにする等）は今後の接続作業が必要。
 */
data class PlayerControllerState(
    var nowPlayingTitle: String? = null,
    var isPlaying: Boolean = false,
    var loopMode: LoopMode = LoopMode.OFF,
    var shuffle: Boolean = false,
)

/** [PlayerControllerState] をプレイヤーごとに保持する簡易ストア。 */
class PlayerControllerStateService {
    private val states = ConcurrentHashMap<UUID, PlayerControllerState>()
    fun stateFor(playerUuid: UUID): PlayerControllerState = states.getOrPut(playerUuid) { PlayerControllerState() }
}

/**
 * 左列ナビゲーションと下段メディアコントローラーを描画する共通処理（UI/UX設計書 1〜3章）。
 * 各画面は必ずこれを呼び出してから、コンテンツ領域([ContentGrid.SLOTS])を自分で描画すること。
 *
 * アイコンのマテリアルは設計書の文言と参照画像から採用した現時点の想定であり、
 * 見た目の微調整（マテリアル差し替え）はここ一箇所を直せば全画面に反映される
 * （採用根拠は実装者からの質問事項として別途まとめている）。
 */
object GuiChrome {

    fun render(inventory: Inventory, activeTab: NavTab?, controllerState: PlayerControllerState, sortLabel: String) {
        renderNav(inventory, activeTab)
        renderController(inventory, controllerState, sortLabel)
    }

    private fun renderNav(inventory: Inventory, activeTab: NavTab?) {
        inventory.setItem(NavTab.MY_SONGS.slot, navItem(Material.PLAYER_HEAD, "自作楽曲一覧", NavTab.MY_SONGS == activeTab))
        inventory.setItem(NavTab.SEARCH.slot, navItem(Material.RED_CONCRETE, "検索", NavTab.SEARCH == activeTab))
        inventory.setItem(NavTab.ALL_SONGS.slot, navItem(Material.YELLOW_CONCRETE, "全楽曲一覧", NavTab.ALL_SONGS == activeTab))
        inventory.setItem(
            NavTab.FAVORITES_PLAYLISTS.slot,
            navItem(Material.LIME_CONCRETE, "お気に入り♪プレイリスト", NavTab.FAVORITES_PLAYLISTS == activeTab),
        )
        inventory.setItem(NavTab.ACTION_MODE.slot, navItem(Material.CYAN_CONCRETE, "アクションモード切替", NavTab.ACTION_MODE == activeTab))
    }

    private fun navItem(material: Material, label: String, active: Boolean): ItemStack =
        GuiItemBuilder(material)
            .name(Component.text(label, if (active) NamedTextColor.YELLOW else NamedTextColor.WHITE))
            .glint(active)
            .build()

    private fun renderController(inventory: Inventory, state: PlayerControllerState, sortLabel: String) {
        inventory.setItem(
            ControllerSlots.SORT,
            GuiItemBuilder(Material.HOPPER)
                .name(Component.text("並び替え", NamedTextColor.GOLD))
                .lore(
                    Component.text("現在: $sortLabel", NamedTextColor.GRAY),
                    Component.text("クリックで切替", NamedTextColor.DARK_GRAY),
                )
                .build(),
        )
        inventory.setItem(ControllerSlots.PAGE_PREV, GuiItemBuilder(Material.RED_DYE).name(Component.text("前のページ", NamedTextColor.RED)).build())
        inventory.setItem(ControllerSlots.PAGE_NEXT, GuiItemBuilder(Material.LIME_DYE).name(Component.text("次のページ", NamedTextColor.GREEN)).build())

        inventory.setItem(
            ControllerSlots.NOW_PLAYING,
            GuiItemBuilder(Material.MUSIC_DISC_13)
                .name(Component.text(state.nowPlayingTitle ?: "再生中の曲はありません", NamedTextColor.AQUA))
                .lore(Component.text("クリックで詳細を開く", NamedTextColor.GRAY))
                .glint(state.isPlaying)
                .build(),
        )
        inventory.setItem(
            ControllerSlots.PLAY_PAUSE,
            GuiItemBuilder(Material.JUKEBOX)
                .name(Component.text(if (state.isPlaying) "一時停止" else "再生", NamedTextColor.GOLD))
                .glint(state.isPlaying)
                .build(),
        )
        inventory.setItem(ControllerSlots.PREV_SONG, GuiItemBuilder(Material.YELLOW_DYE).name(Component.text("前の曲", NamedTextColor.YELLOW)).build())
        inventory.setItem(ControllerSlots.NEXT_SONG, GuiItemBuilder(Material.LIGHT_BLUE_DYE).name(Component.text("次の曲", NamedTextColor.AQUA)).build())

        val loopLabel = when (state.loopMode) {
            LoopMode.OFF -> "OFF"
            LoopMode.LIST -> "リスト全体"
            LoopMode.SINGLE -> "1曲"
        }
        inventory.setItem(
            ControllerSlots.LOOP,
            GuiItemBuilder(Material.LEAD)
                .name(Component.text("ループ: $loopLabel", NamedTextColor.LIGHT_PURPLE))
                .glint(state.loopMode != LoopMode.OFF)
                .build(),
        )
        inventory.setItem(
            ControllerSlots.SHUFFLE,
            GuiItemBuilder(Material.WIND_CHARGE)
                .name(Component.text("シャッフル: ${if (state.shuffle) "ON" else "OFF"}", NamedTextColor.BLUE))
                .glint(state.shuffle)
                .build(),
        )
    }
}