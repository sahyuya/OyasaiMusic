package com.github.sahyuya.oyasaiMusic.gui

import com.github.sahyuya.oyasaiMusic.OyasaiMusic
import com.github.sahyuya.oyasaiMusic.db.SongSort
import com.github.sahyuya.oyasaiMusic.model.Song
import org.bukkit.entity.Player
import java.util.UUID

/**
 * [SongListMenu] の共通構築ヘルパー。同じ「楽曲一覧グリッド」画面を
 * 自作楽曲一覧・全楽曲一覧・作者ごとの作品一覧・題名検索結果で使い回すための工場関数群。
 */
object MainMenuScreens {

    fun mySongs(plugin: OyasaiMusic, menuManager: MenuManager, viewer: Player): SongListMenu =
        SongListMenu(
            plugin, menuManager, viewer,
            title = "自作楽曲一覧",
            availableSorts = listOf(SongSort.CREATED_AT_DESC, SongSort.TITLE_ASC),
            initialSort = SongSort.CREATED_AT_DESC,
            ownTab = NavTab.MY_SONGS,
        ) { sort, limit, offset ->
            plugin.songRepository.findByAuthor(viewer.uniqueId, includeDrafts = true)
                .sortedWith(sortComparator(sort))
                .drop(offset).take(limit)
        }

    fun allSongs(plugin: OyasaiMusic, menuManager: MenuManager, viewer: Player): SongListMenu =
        SongListMenu(
            plugin, menuManager, viewer,
            title = "全楽曲一覧",
            availableSorts = listOf(SongSort.CREATED_AT_DESC, SongSort.TITLE_ASC, SongSort.LIKES_DESC, SongSort.VIEWS_DESC),
            initialSort = SongSort.CREATED_AT_DESC,
            ownTab = NavTab.ALL_SONGS,
        ) { sort, limit, offset -> mergeOwnDrafts(plugin, viewer, offset, limit, titleFilter = null) { o, l ->
            plugin.songRepository.searchPublished(sort = sort, limit = l, offset = o)
        } }

    fun authorWorks(plugin: OyasaiMusic, menuManager: MenuManager, viewer: Player, authorUuid: UUID, authorName: String): SongListMenu =
        SongListMenu(
            plugin, menuManager, viewer,
            title = "$authorName の作品",
            availableSorts = listOf(SongSort.CREATED_AT_DESC, SongSort.TITLE_ASC),
            initialSort = SongSort.CREATED_AT_DESC,
        ) { sort, limit, offset ->
            plugin.songRepository.findByAuthor(authorUuid, includeDrafts = false)
                .sortedWith(sortComparator(sort))
                .drop(offset).take(limit)
        }

    private fun sortComparator(sort: SongSort): Comparator<Song> = when (sort) {
        SongSort.TITLE_ASC -> compareBy { it.title }
        SongSort.LIKES_DESC -> compareByDescending { it.likes }
        SongSort.VIEWS_DESC -> compareByDescending { it.views }
        else -> compareByDescending { it.createdAt }
    }

    /**
     * 全楽曲一覧・検索結果等、通常は公開楽曲(published=true)のみを表示する画面に、
     * 閲覧者自身の下書き楽曲を混ぜて表示するためのヘルパー
     * （サヒュヤ氏の指示: 録音直後の自分の楽曲を「レコードの欠片」として一覧上でも見えるようにする。
     * アイコン描画は [SongListMenu.songIcon] 側で `!song.published` を見て自動的に分岐する）。
     *
     * ページネーションの都合上、下書きは1ページ目(offset=0)の先頭にのみ挿入する。
     * 下書き件数はページサイズを超えないのが通常のため、2ページ目以降のoffsetズレは
     * 実用上ごく小さい許容範囲としている（要確認: 下書きが大量にある場合は再設計が必要）。
     */
    fun mergeOwnDrafts(
        plugin: OyasaiMusic,
        viewer: Player,
        offset: Int,
        limit: Int,
        titleFilter: String?,
        publishedLoader: (offset: Int, limit: Int) -> List<Song>,
    ): List<Song> {
        if (offset != 0) return publishedLoader(offset, limit)

        val myDrafts = plugin.songRepository.findByAuthor(viewer.uniqueId, includeDrafts = true)
            .filter { !it.published }
            .let { drafts -> if (titleFilter != null) drafts.filter { it.title.contains(titleFilter, ignoreCase = true) } else drafts }
            .sortedByDescending { it.createdAt }

        val remaining = (limit - myDrafts.size).coerceAtLeast(0)
        val published = if (remaining > 0) publishedLoader(0, remaining) else emptyList()
        return (myDrafts + published).take(limit)
    }
}