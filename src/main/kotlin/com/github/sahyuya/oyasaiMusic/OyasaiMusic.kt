package com.github.sahyuya.oyasaiMusic

import com.github.sahyuya.oyasaiMusic.audio.NotePlayListener
import com.github.sahyuya.oyasaiMusic.audio.PlaybackEngine
import com.github.sahyuya.oyasaiMusic.audio.PlaybackMode
import com.github.sahyuya.oyasaiMusic.audio.PlaybackModeService
import com.github.sahyuya.oyasaiMusic.audio.RecordingSessionManager
import com.github.sahyuya.oyasaiMusic.command.GetMusicPlayerCommand
import com.github.sahyuya.oyasaiMusic.command.MusicMenuCommand
import com.github.sahyuya.oyasaiMusic.command.PlaytestCommand
import com.github.sahyuya.oyasaiMusic.command.RecordCommand
import com.github.sahyuya.oyasaiMusic.db.DatabaseManager
import com.github.sahyuya.oyasaiMusic.db.LikeService
import com.github.sahyuya.oyasaiMusic.db.PlaybackPreferenceRepository
import com.github.sahyuya.oyasaiMusic.db.PlaylistRepository
import com.github.sahyuya.oyasaiMusic.db.RankingCacheService
import com.github.sahyuya.oyasaiMusic.db.RankingRepository
import com.github.sahyuya.oyasaiMusic.db.SocialRepository
import com.github.sahyuya.oyasaiMusic.db.SongRepository
import com.github.sahyuya.oyasaiMusic.db.UserRepository
import com.github.sahyuya.oyasaiMusic.db.ViewCountService
import com.github.sahyuya.oyasaiMusic.gui.MenuManager
import com.github.sahyuya.oyasaiMusic.gui.PlayerControllerStateService
import com.github.sahyuya.oyasaiMusic.gui.PlaybackController
import org.bukkit.plugin.java.JavaPlugin
import java.io.File

/**
 * OyasaiMusic プラグインのエントリーポイント。
 *
 * 実装フェーズ方針（サヒュヤ氏との合意）:
 *   1. コア基盤（DB・音源フォーマット・録音/再生エンジン） ← 完了
 *   2. GUI（6×9 SPA構造の各画面） ← 本ファイルは着手フェーズ（GUIフェーズで追加した箇所は
 *      コメントで明示している）
 *
 * このクラスは各コンポーネントの初期化と依存関係の配線のみを担当し、
 * ロジック本体はそれぞれのクラスに委譲する。
 */
class OyasaiMusic : JavaPlugin() {

    lateinit var databaseManager: DatabaseManager
        private set
    lateinit var songRepository: SongRepository
        private set
    lateinit var userRepository: UserRepository
        private set
    lateinit var socialRepository: SocialRepository
        private set
    lateinit var playlistRepository: PlaylistRepository
        private set
    lateinit var playbackPreferenceRepository: PlaybackPreferenceRepository
        private set
    lateinit var playbackModeService: PlaybackModeService
        private set
    lateinit var likeService: LikeService
        private set
    lateinit var viewCountService: ViewCountService
        private set
    lateinit var recordingSessionManager: RecordingSessionManager
        private set
    lateinit var playbackEngine: PlaybackEngine
        private set
    lateinit var audioDirectory: File
        private set

    // ---- GUIフェーズで追加 ----
    lateinit var rankingRepository: RankingRepository
        private set
    lateinit var rankingCacheService: RankingCacheService
        private set
    lateinit var controllerStateService: PlayerControllerStateService
        private set
    lateinit var menuManager: MenuManager
        private set
    lateinit var playbackController: PlaybackController
        private set

    override fun onEnable() {
        // --- FAWE必須依存チェック（plugin.ymlのdependでも保証されるが、明示的なメッセージを出すため二重チェック） ---
        if (server.pluginManager.getPlugin("FastAsyncWorldEdit") == null) {
            logger.severe("FastAsyncWorldEdit(FAWE)が見つかりません。OyasaiMusicはFAWEを必須依存としています。")
            server.pluginManager.disablePlugin(this)
            return
        }

        saveDefaultConfig()
        reloadConfig()

        audioDirectory = File(dataFolder, config.getString("storage.audio-directory", "audio") ?: "audio")
        audioDirectory.mkdirs()

        // --- DB初期化 ---
        databaseManager = DatabaseManager(this, config.getString("storage.database-file", "database.db") ?: "database.db")
        databaseManager.connect()
        songRepository = SongRepository(databaseManager)
        userRepository = UserRepository(databaseManager)
        socialRepository = SocialRepository(databaseManager)
        playlistRepository = PlaylistRepository(databaseManager) // GUIフェーズで追加
        playbackPreferenceRepository = PlaybackPreferenceRepository(databaseManager)
        playbackModeService = PlaybackModeService(playbackPreferenceRepository)
        rankingRepository = RankingRepository(databaseManager) // GUIフェーズで追加

        // --- サービス層 ---
        likeService = LikeService(
            songRepository = songRepository,
            socialRepository = socialRepository,
            userRepository = userRepository,
            likeRewardMoney = config.getLong("economy.like-reward-money", 1000),
            likeRewardPoints = config.getLong("economy.like-reward-points", 2),
        )
        viewCountService = ViewCountService(
            plugin = this,
            songRepository = songRepository,
            userRepository = userRepository,
            socialRepository = socialRepository,
            hourLimit = config.getInt("playback.view-limit-per-hour", 3),
            dayLimit = config.getInt("playback.view-limit-per-day", 10),
            viewsPerPoint = config.getInt("playback.views-per-point", 10),
        )

        // ============ GUIフェーズで追加: 録音システムより前に用意する必要がある ============
        // （RecordCommandが「録音完了後に楽曲設定画面を自動で開く」ためmenuManagerを必要とするため）
        controllerStateService = PlayerControllerStateService()
        rankingCacheService = RankingCacheService(this, rankingRepository)
        rankingCacheService.start()
        menuManager = MenuManager(this)
        server.pluginManager.registerEvents(menuManager, this)
        playbackController = PlaybackController(this, menuManager) // GUIフェーズで追加
        server.pluginManager.registerEvents(PhysicalMusicPlayerItem(this, menuManager), this)
        // ============================================================================

        // --- 録音システム ---
        recordingSessionManager = RecordingSessionManager()
        server.pluginManager.registerEvents(
            NotePlayListener(
                sessionManager = recordingSessionManager,
                maxRadius = config.getDouble("recording.dynamic-record-radius", 32.0),
                fullVolumeRadius = config.getDouble("recording.dynamic-record-full-volume-radius", 2.0),
                minVolumeFloor = 20,
            ),
            this,
        )

        getCommand("record")?.let { cmd ->
            val executor = RecordCommand(
                plugin = this,
                songRepository = songRepository,
                sessionManager = recordingSessionManager,
                audioDirectory = audioDirectory,
                defaultRecordMaterial = config.getString("recording.default-record-material", "MUSIC_DISC_13") ?: "MUSIC_DISC_13",
                defaultPrice = config.getInt("recording.default-price", 1000),
                menuManager = menuManager, // GUIフェーズで追加: 録音完了後に楽曲設定画面を自動で開く
            )
            cmd.setExecutor(executor)
            cmd.tabCompleter = executor
        } ?: logger.warning("recordコマンドの登録に失敗しました（plugin.ymlを確認してください）。")

        val defaultMode = when (config.getString("playback.default-mode", "default")?.lowercase()) {
            "positional" -> PlaybackMode.POSITIONAL
            else -> PlaybackMode.DEFAULT
        }
        playbackEngine = PlaybackEngine(
            plugin = this,
            bedrockPrefix = config.getString("bedrock.name-prefix", ".") ?: ".",
            chordLimit = config.getInt("bedrock.chord-limit", 3),
            defaultMode = defaultMode,
        )

        getCommand("playtest")?.let { cmd ->
            val executor = PlaytestCommand(
                plugin = this,
                songRepository = songRepository,
                playbackEngine = playbackEngine,
                audioDirectory = audioDirectory,
            )
            cmd.setExecutor(executor)
            cmd.tabCompleter = executor
        } ?: logger.warning("playtestコマンドの登録に失敗しました（plugin.ymlを確認してください）。")

        // ============ ここから GUIフェーズで追加（コマンド登録） ============
        getCommand("musicmenu")?.let { cmd ->
            cmd.setExecutor(MusicMenuCommand(this))
        } ?: logger.warning("musicmenuコマンドの登録に失敗しました（plugin.ymlを確認してください）。")

        getCommand("getmusicplayer")?.let { cmd ->
            val executor = GetMusicPlayerCommand()
            cmd.setExecutor(executor)
            cmd.tabCompleter = executor
        } ?: logger.warning("getmusicplayerコマンドの登録に失敗しました（plugin.ymlを確認してください）。")
        // ============ GUIフェーズ追加ここまで ============

        logger.info("OyasaiMusic (GUIフェーズ着手) を有効化しました。")
    }

    override fun onDisable() {
        if (::playbackEngine.isInitialized) playbackEngine.shutdown()
        if (::databaseManager.isInitialized) databaseManager.close()
        logger.info("OyasaiMusicを無効化しました。")
    }
}