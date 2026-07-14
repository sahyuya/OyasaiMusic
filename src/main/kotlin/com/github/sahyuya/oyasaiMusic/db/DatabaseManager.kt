package com.github.sahyuya.oyasaiMusic.db

import org.bukkit.plugin.Plugin
import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import java.sql.Statement

/**
 * SQLite(WALモード)への接続とスキーマ作成を担当するクラス。
 *
 * データ・システム設計書 1章に準拠しつつ、UI/UX設計書に登場する
 * 「プレイリスト（複数）」機能を実現するために `playlists` / `playlist_songs`
 * テーブルを追加している（元のデータ設計書には favorites テーブルのみが
 * 定義されており、複数プレイリストを表現するテーブルが無かったため）。
 * また、`songs.supports_positional`（楽曲にPan指定があるか）と、
 * `playback_preferences`（リスナーごとの再生方式=デフォルト/立体音響の選択）も追加している
 * （追加項目.txt の「立体音響再生は…個々のリスナーが…選べて、その再生方法の選択を保存する」
 * に対応するため）。
 *
 * SQLite JDBCの単一Connectionはスレッドセーフではないため、
 * 全てのアクセスは [transaction] / [query] を介して同期的に行うこと。
 * 呼び出し側は必ず非同期スレッド（BukkitSchedulerのasync等）から呼び出すこと。
 */
class DatabaseManager(private val plugin: Plugin, databaseFileName: String) {

    private val dbFile: File = File(plugin.dataFolder, databaseFileName)
    private lateinit var connection: Connection
    private val lock = Any()

    fun connect() {
        plugin.dataFolder.mkdirs()
        Class.forName("org.sqlite.JDBC")
        connection = DriverManager.getConnection("jdbc:sqlite:${dbFile.absolutePath}")
        connection.createStatement().use { st: Statement ->
            st.execute("PRAGMA journal_mode=WAL;")
            st.execute("PRAGMA synchronous=NORMAL;")
            st.execute("PRAGMA foreign_keys=ON;")
        }
        createSchema()
    }

    fun close() {
        if (::connection.isInitialized && !connection.isClosed) {
            connection.close()
        }
    }

    /**
     * DB操作を同期化して実行する。呼び出し元は非同期スレッドであることを前提とする。
     */
    fun <T> transaction(block: (Connection) -> T): T {
        synchronized(lock) {
            return block(connection)
        }
    }

    private fun createSchema() {
        transaction { conn ->
            conn.createStatement().use { st ->
                st.executeUpdate(
                    """
                    CREATE TABLE IF NOT EXISTS songs (
                        id              INTEGER PRIMARY KEY AUTOINCREMENT,
                        author_uuid     BLOB(16) NOT NULL,
                        title           TEXT NOT NULL,
                        created_at      INTEGER NOT NULL,
                        bpm             INTEGER NOT NULL,
                        record_material TEXT NOT NULL,
                        price           INTEGER NOT NULL DEFAULT 1000,
                        reference_url   TEXT,
                        status          INTEGER NOT NULL DEFAULT 0,
                        likes           INTEGER NOT NULL DEFAULT 0,
                        views           INTEGER NOT NULL DEFAULT 0,
                        file_name       TEXT NOT NULL,
                        supports_positional INTEGER NOT NULL DEFAULT 0
                    );
                    """.trimIndent()
                )
                st.executeUpdate("CREATE INDEX IF NOT EXISTS idx_songs_author ON songs(author_uuid);")
                st.executeUpdate("CREATE INDEX IF NOT EXISTS idx_songs_status ON songs(status);")
                st.executeUpdate("CREATE INDEX IF NOT EXISTS idx_songs_created_at ON songs(created_at);")

                st.executeUpdate(
                    """
                    CREATE TABLE IF NOT EXISTS users (
                        uuid            BLOB(16) PRIMARY KEY,
                        pending_money   INTEGER NOT NULL DEFAULT 0,
                        pending_points  INTEGER NOT NULL DEFAULT 0
                    );
                    """.trimIndent()
                )

                st.executeUpdate(
                    """
                    CREATE TABLE IF NOT EXISTS song_likes (
                        id          INTEGER PRIMARY KEY AUTOINCREMENT,
                        user_uuid   BLOB(16) NOT NULL,
                        song_id     INTEGER NOT NULL REFERENCES songs(id) ON DELETE CASCADE,
                        created_at  INTEGER NOT NULL,
                        UNIQUE(user_uuid, song_id)
                    );
                    """.trimIndent()
                )
                st.executeUpdate("CREATE INDEX IF NOT EXISTS idx_song_likes_song ON song_likes(song_id);")

                st.executeUpdate(
                    """
                    CREATE TABLE IF NOT EXISTS favorites (
                        id          INTEGER PRIMARY KEY AUTOINCREMENT,
                        user_uuid   BLOB(16) NOT NULL,
                        song_id     INTEGER NOT NULL REFERENCES songs(id) ON DELETE CASCADE,
                        created_at  INTEGER NOT NULL,
                        UNIQUE(user_uuid, song_id)
                    );
                    """.trimIndent()
                )
                st.executeUpdate("CREATE INDEX IF NOT EXISTS idx_favorites_song ON favorites(song_id);")

                st.executeUpdate(
                    """
                    CREATE TABLE IF NOT EXISTS follows (
                        id              INTEGER PRIMARY KEY AUTOINCREMENT,
                        follower_uuid   BLOB(16) NOT NULL,
                        target_uuid     BLOB(16) NOT NULL,
                        created_at      INTEGER NOT NULL,
                        UNIQUE(follower_uuid, target_uuid)
                    );
                    """.trimIndent()
                )
                st.executeUpdate("CREATE INDEX IF NOT EXISTS idx_follows_target ON follows(target_uuid);")
                st.executeUpdate("CREATE INDEX IF NOT EXISTS idx_follows_follower ON follows(follower_uuid);")

                st.executeUpdate(
                    """
                    CREATE TABLE IF NOT EXISTS view_history (
                        id          INTEGER PRIMARY KEY AUTOINCREMENT,
                        user_uuid   BLOB(16) NOT NULL,
                        song_id     INTEGER NOT NULL REFERENCES songs(id) ON DELETE CASCADE,
                        timestamp   INTEGER NOT NULL
                    );
                    """.trimIndent()
                )
                st.executeUpdate(
                    "CREATE INDEX IF NOT EXISTS idx_view_history_lookup ON view_history(user_uuid, song_id, timestamp);"
                )

                // --- ここから: UI/UX設計書のプレイリスト機能のために追加したテーブル ---
                st.executeUpdate(
                    """
                    CREATE TABLE IF NOT EXISTS playlists (
                        id          INTEGER PRIMARY KEY AUTOINCREMENT,
                        owner_uuid  BLOB(16) NOT NULL,
                        name        TEXT NOT NULL,
                        created_at  INTEGER NOT NULL
                    );
                    """.trimIndent()
                )
                st.executeUpdate("CREATE INDEX IF NOT EXISTS idx_playlists_owner ON playlists(owner_uuid);")

                st.executeUpdate(
                    """
                    CREATE TABLE IF NOT EXISTS playlist_songs (
                        id          INTEGER PRIMARY KEY AUTOINCREMENT,
                        playlist_id INTEGER NOT NULL REFERENCES playlists(id) ON DELETE CASCADE,
                        song_id     INTEGER NOT NULL REFERENCES songs(id) ON DELETE CASCADE,
                        position    INTEGER NOT NULL,
                        added_at    INTEGER NOT NULL,
                        UNIQUE(playlist_id, song_id)
                    );
                    """.trimIndent()
                )
                st.executeUpdate(
                    "CREATE INDEX IF NOT EXISTS idx_playlist_songs_playlist ON playlist_songs(playlist_id, position);"
                )

                // --- ここから: 追加項目.txt の「リスナーごとの再生方式選択」機能のために追加したテーブル ---
                st.executeUpdate(
                    """
                    CREATE TABLE IF NOT EXISTS playback_preferences (
                        user_uuid   BLOB(16) NOT NULL,
                        song_id     INTEGER NOT NULL REFERENCES songs(id) ON DELETE CASCADE,
                        mode        INTEGER NOT NULL,
                        PRIMARY KEY (user_uuid, song_id)
                    );
                    """.trimIndent()
                )
            }
        }
    }
}
