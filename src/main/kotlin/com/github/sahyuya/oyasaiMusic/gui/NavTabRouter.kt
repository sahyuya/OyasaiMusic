package com.github.sahyuya.oyasaiMusic.gui

import com.github.sahyuya.oyasaiMusic.OyasaiMusic
import org.bukkit.entity.Player

/**
 * 左列ナビゲーションタブのクリックを一元的に処理するヘルパー。
 *
 * サヒュヤ氏の指示: 「左のタブは押して開いた状態からもう一度押すと、閉じてトップメニューへ戻る」。
 * そのため各画面は自分がどのタブに属するか([ownTab])を申告し、そのタブが再クリックされたら
 * メインメニューへ、それ以外のタブがクリックされたら該当画面へ遷移する。
 * [ownTab]がnullの画面（検索結果・作者作品・楽曲設定など、タブに直接紐づかないドリルダウン画面）は
 * 常に通常の遷移として扱う。
 *
 * @return true = ナビゲーションタブのクリックとして処理した（呼び出し元は追加処理不要）
 */
object NavTabRouter {

    fun handle(slot: Int, ownTab: NavTab?, plugin: OyasaiMusic, menuManager: MenuManager, viewer: Player): Boolean {
        val tab = NavTab.entries.firstOrNull { it.slot == slot } ?: return false

        if (tab == ownTab) {
            menuManager.open(viewer, MainMenuScreen(plugin, menuManager, viewer), rememberAsPrevious = false)
            return true
        }

        when (tab) {
            NavTab.MY_SONGS -> menuManager.open(viewer, MainMenuScreens.mySongs(plugin, menuManager, viewer))
            NavTab.SEARCH -> menuManager.open(viewer, SearchMenuScreen(plugin, menuManager, viewer))
            NavTab.ALL_SONGS -> menuManager.open(viewer, MainMenuScreens.allSongs(plugin, menuManager, viewer))
            NavTab.FAVORITES_PLAYLISTS -> menuManager.open(viewer, FavoritesPlaylistsScreen(plugin, menuManager, viewer))
            NavTab.ACTION_MODE -> {
                val next = BedrockActionMode.cycle(viewer.uniqueId)
                viewer.sendMessage("§bアクションモードを切り替えました: ${next.displayIndex}")
            }
        }
        return true
    }
}