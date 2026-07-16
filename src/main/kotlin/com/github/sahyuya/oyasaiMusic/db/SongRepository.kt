package com.github.sahyuya.oyasaiMusic.db

import com.github.sahyuya.oyasaiMusic.model.Song
import com.github.sahyuya.oyasaiMusic.model.SongStatus
import com.github.sahyuya.oyasaiMusic.util.UuidUtil
import java.sql.ResultSet
import java.sql.Statement
import java.util.UUID

/**
 * songs テーブルへのアクセスを担当するリポジトリ。
 * 呼び出しは必ず非同期スレッドから行うこと（[DatabaseManager.transaction] が同期化する）。
 */
class SongRepository(private val db: DatabaseManager) {

    /**
     * 新規楽曲を下書き(status=DRAFT, published=false)として登録する。
     * 録音システム（グリッド型/回路型/動的録音）は録音完了後に必ずこれを呼び出す。
     *
     * @return 採番された楽曲ID
     */
    fun insertDraft(
        authorUuid: UUID,
        title: String,
        bpm: Int,
        recordMaterial: String,
        price: Int,
        fileName: String,
        supportsPositional: Boolean = false,
    ): Long = db.transaction { conn ->
        conn.prepareStatement(
            """
            INSERT INTO songs (author_uuid, title, created_at, bpm, record_material, price, status, likes, views, file_name, supports_positional, published)
            VALUES (?, ?, ?, ?, ?, ?, ?, 0, 0, ?, ?, 0)
            """.trimIndent(),
            Statement.RETURN_GENERATED_KEYS,
        ).use { ps ->
            ps.setBytes(1, UuidUtil.toBytes(authorUuid))
            ps.setString(2, title)
            ps.setLong(3, System.currentTimeMillis() / 1000)
            ps.setInt(4, bpm)
            ps.setString(5, recordMaterial)
            ps.setInt(6, price)
            ps.setInt(7, SongStatus.DRAFT.code)
            ps.setString(8, fileName)
            ps.setInt(9, if (supportsPositional) 1 else 0)
            ps.executeUpdate()
            ps.generatedKeys.use { keys ->
                if (keys.next()) keys.getLong(1) else error("楽曲IDの採番に失敗しました")
            }
        }
    }

    fun findById(id: Long): Song? = db.transaction { conn ->
        conn.prepareStatement("SELECT * FROM songs WHERE id = ?").use { ps ->
            ps.setLong(1, id)
            ps.executeQuery().use { rs -> if (rs.next()) rs.toSong() else null }
        }
    }

    fun findByAuthor(authorUuid: UUID, includeDrafts: Boolean = true): List<Song> = db.transaction { conn ->
        // includeDrafts=false は「他人から見た公開作品一覧」を意味するため、
        // GUIフェーズより審査ステータス(status)ではなく公開フラグ(published)で絞り込む。
        val sql = if (includeDrafts) {
            "SELECT * FROM songs WHERE author_uuid = ? ORDER BY created_at DESC"
        } else {
            "SELECT * FROM songs WHERE author_uuid = ? AND published = 1 ORDER BY created_at DESC"
        }
        conn.prepareStatement(sql).use { ps ->
            ps.setBytes(1, UuidUtil.toBytes(authorUuid))
            ps.executeQuery().use { rs -> rs.toSongList() }
        }
    }

    /** 公開済み(published=true)の楽曲を条件付きで検索する。 */
    fun searchPublished(
        titleLike: String? = null,
        sort: SongSort = SongSort.CREATED_AT_DESC,
        limit: Int = 200,
        offset: Int = 0,
    ): List<Song> = db.transaction { conn ->
        val where = StringBuilder("WHERE published = 1")
        if (titleLike != null) where.append(" AND title LIKE ?")
        val sql = "SELECT * FROM songs $where ORDER BY ${sort.orderBy} LIMIT ? OFFSET ?"
        conn.prepareStatement(sql).use { ps ->
            var idx = 1
            if (titleLike != null) ps.setString(idx++, "%$titleLike%")
            ps.setInt(idx++, limit)
            ps.setInt(idx, offset)
            ps.executeQuery().use { rs -> rs.toSongList() }
        }
    }

    fun updateStatus(id: Long, status: SongStatus) = db.transaction { conn ->
        conn.prepareStatement("UPDATE songs SET status = ? WHERE id = ?").use { ps ->
            ps.setInt(1, status.code)
            ps.setLong(2, id)
            ps.executeUpdate()
        }
    }

    /** GUIフェーズで追加: 公開/非公開の独立フラグを切り替える（楽曲設定画面「公開」ボタン用）。 */
    fun setPublished(id: Long, published: Boolean) = db.transaction { conn ->
        conn.prepareStatement("UPDATE songs SET published = ? WHERE id = ?").use { ps ->
            ps.setInt(1, if (published) 1 else 0)
            ps.setLong(2, id)
            ps.executeUpdate()
        }
    }

    fun updateSettings(
        id: Long,
        title: String? = null,
        bpm: Int? = null,
        recordMaterial: String? = null,
        price: Int? = null,
        referenceUrl: String? = null,
    ) = db.transaction { conn ->
        val fields = mutableListOf<String>()
        val values = mutableListOf<Any?>()
        title?.let { fields += "title = ?"; values += it }
        bpm?.let { fields += "bpm = ?"; values += it }
        recordMaterial?.let { fields += "record_material = ?"; values += it }
        price?.let { fields += "price = ?"; values += it }
        referenceUrl?.let { fields += "reference_url = ?"; values += it }
        if (fields.isEmpty()) return@transaction
        val sql = "UPDATE songs SET ${fields.joinToString(", ")} WHERE id = ?"
        conn.prepareStatement(sql).use { ps ->
            values.forEachIndexed { i, v -> ps.setObject(i + 1, v) }
            ps.setLong(values.size + 1, id)
            ps.executeUpdate()
        }
    }

    /** @return 加算後の総視聴回数（SQLiteのRETURNING句を利用して1クエリで取得） */
    fun incrementViews(id: Long, by: Long = 1): Long = db.transaction { conn ->
        conn.prepareStatement("UPDATE songs SET views = views + ? WHERE id = ? RETURNING views").use { ps ->
            ps.setLong(1, by)
            ps.setLong(2, id)
            ps.executeQuery().use { rs -> if (rs.next()) rs.getLong("views") else 0L }
        }
    }

    fun incrementLikes(id: Long, by: Long = 1) = db.transaction { conn ->
        conn.prepareStatement("UPDATE songs SET likes = likes + ? WHERE id = ?").use { ps ->
            ps.setLong(1, by)
            ps.setLong(2, id)
            ps.executeUpdate()
        }
    }

    fun delete(id: Long) = db.transaction { conn ->
        conn.prepareStatement("DELETE FROM songs WHERE id = ?").use { ps ->
            ps.setLong(1, id)
            ps.executeUpdate()
        }
    }

    private fun ResultSet.toSong(): Song = Song(
        id = getLong("id"),
        authorUuid = UuidUtil.fromBytes(getBytes("author_uuid")),
        title = getString("title"),
        createdAt = getLong("created_at"),
        bpm = getInt("bpm"),
        recordMaterial = getString("record_material"),
        price = getInt("price"),
        referenceUrl = getString("reference_url"),
        status = SongStatus.fromCode(getInt("status")),
        likes = getLong("likes"),
        views = getLong("views"),
        fileName = getString("file_name"),
        supportsPositional = getInt("supports_positional") != 0,
        published = getInt("published") != 0,
    )

    private fun ResultSet.toSongList(): List<Song> {
        val list = mutableListOf<Song>()
        while (next()) list += toSong()
        return list
    }
}

/** 全楽曲一覧・検索等で使うソート順。UI/UX設計書 4章の「動的ソート順」に対応。 */
enum class SongSort(val orderBy: String) {
    CREATED_AT_DESC("created_at DESC"),
    CREATED_AT_ASC("created_at ASC"),
    TITLE_ASC("title ASC"),
    LIKES_DESC("likes DESC"),
    VIEWS_DESC("views DESC"),
}