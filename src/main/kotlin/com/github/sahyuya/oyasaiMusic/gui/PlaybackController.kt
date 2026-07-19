package com.github.sahyuya.oyasaiMusic.gui

import com.github.sahyuya.oyasaiMusic.OyasaiMusic
import com.github.sahyuya.oyasaiMusic.audio.SongAudioFile
import com.github.sahyuya.oyasaiMusic.model.Song
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import java.io.File

/**
 * 再生・一時停止・ループ・シャッフル等、下段メディアコントローラーに関する状態変更を
 * 一箇所に集約するコントローラー（GUIフェーズで追加）。
 *
 * 【背景】各画面が個別に `PlaybackEngine.play()` を呼び、[PlayerControllerState] を直接
 * 書き換えていたため、以下のような不具合が発生していた:
 *   - 再生開始/終了で状態は更新されても、その時点で開いているGUIの再描画が呼ばれず、
 *     「再生中のレコードにエンチャント模様が付かない」「終了してもエンチャント模様が取れない」
 *   - 再生中の実際の曲(Song)を保持していなかったため、
 *     「下段の再生中の楽曲詳細を開くボタンが機能しない」
 *   - 一時停止/再開の実装が無く、下段の再生ボタンが常に「未実装」メッセージだった
 * これらを解消するため、再生に関する操作は必ずこのクラスを経由させ、
 * 状態変更のたびに [MenuManager.refreshCurrent] で現在開いているGUIを再描画する。
 */
class PlaybackController(private val plugin: OyasaiMusic, private val menuManager: MenuManager) {

    /**
     * 楽曲を再生する。既に何か再生中であればまず停止してから開始する（多重再生防止）。
     * @param onCompletion 再生完了時に追加で呼びたい処理（プレイリストの連続再生等）。
     *        状態のリセット・GUI再描画は本メソッドが自動的に行うため、ここには含めなくてよい。
     */
    fun play(viewer: Player, song: Song, onCompletion: (() -> Unit)? = null) {
        val songId = song.id
        if (songId == null) {
            viewer.sendMessage("§c保存前の楽曲は再生できません。")
            return
        }
        Bukkit.getScheduler().runTaskAsynchronously(plugin, Runnable {
            val file = File(plugin.audioDirectory, song.fileName)
            if (!file.exists()) {
                Bukkit.getScheduler().runTask(plugin, Runnable { viewer.sendMessage("§c音源ファイルが見つかりません。") })
                return@Runnable
            }
            val audio = SongAudioFile.read(file)
            Bukkit.getScheduler().runTask(plugin, Runnable {
                val state = plugin.controllerStateService.stateFor(viewer.uniqueId)
                // 既に再生中のセッションがあれば止める（多重再生防止）。
                state.activeSession?.let { plugin.playbackEngine.stop(it) }

                val mode = plugin.playbackModeService.resolve(viewer.uniqueId, song)
                val session = plugin.playbackEngine.play(
                    song = song,
                    notes = audio.notes,
                    recipients = listOf(viewer),
                    mode = mode,
                    onListenThresholdReached = { player, s ->
                        plugin.viewCountService.registerView(player, s, isAmbientPlayback = false) {
                            // 視聴回数がDBへ実際に記録できた時点でGUIを再描画し、
                            // 一覧等の「再生数」表示が最新化されるようにする。
                            menuManager.refreshCurrent(player.uniqueId)
                        }
                    },
                    onCompletion = {
                        val s2 = plugin.controllerStateService.stateFor(viewer.uniqueId)
                        s2.isPlaying = false
                        s2.activeSession = null
                        menuManager.refreshCurrent(viewer.uniqueId)
                        onCompletion?.invoke()
                    },
                )
                state.isPlaying = true
                state.nowPlayingSong = song
                state.activeSession = session
                menuManager.refreshCurrent(viewer.uniqueId)
                viewer.sendMessage("§a再生開始: §f${song.title}")
            })
        })
    }

    /**
     * 下段「再生/一時停止」ボタン。
     * 再生中のセッションが無くても、直前に再生していた曲([PlayerControllerState.nowPlayingSong])が
     * 残っていればそれを再生し直す（サヒュヤ氏の指示: 「再生が終了した後、もう一度下段の再生ボタンを
     * 押したら再生できるように」。次の曲を再生するまでは最後に再生した曲を覚えておく）。
     */
    fun togglePlayPause(viewer: Player) {
        val state = plugin.controllerStateService.stateFor(viewer.uniqueId)
        val session = state.activeSession
        if (session == null) {
            val lastSong = state.nowPlayingSong
            if (lastSong != null) {
                play(viewer, lastSong)
            } else {
                viewer.sendMessage("§7再生中の曲がありません。曲を選んで再生してください。")
            }
            return
        }
        if (state.isPlaying) {
            plugin.playbackEngine.pause(session)
            state.isPlaying = false
            viewer.sendMessage("§e一時停止しました。")
        } else {
            plugin.playbackEngine.resume(session)
            state.isPlaying = true
            viewer.sendMessage("§a再生を再開しました。")
        }
        menuManager.refreshCurrent(viewer.uniqueId)
    }

    /** 下段「再生中の曲」ボタン。再生中の曲の楽曲詳細画面を開く。 */
    fun openNowPlayingDetail(viewer: Player) {
        val song = plugin.controllerStateService.stateFor(viewer.uniqueId).nowPlayingSong
        if (song == null) {
            viewer.sendMessage("§7現在再生中の曲はありません。")
            return
        }
        menuManager.open(viewer, SongDetailScreen(plugin, menuManager, viewer, song))
    }

    fun toggleLoop(viewer: Player) {
        val state = plugin.controllerStateService.stateFor(viewer.uniqueId)
        state.loopMode = when (state.loopMode) {
            LoopMode.OFF -> LoopMode.LIST
            LoopMode.LIST -> LoopMode.SINGLE
            LoopMode.SINGLE -> LoopMode.OFF
        }
        menuManager.refreshCurrent(viewer.uniqueId)
    }

    fun toggleShuffle(viewer: Player) {
        val state = plugin.controllerStateService.stateFor(viewer.uniqueId)
        state.shuffle = !state.shuffle
        menuManager.refreshCurrent(viewer.uniqueId)
    }

    /**
     * 下段メディアコントローラーの共通クリック処理。各画面のonClickから呼び出す。
     * PREV_SONG/NEXT_SONGはプレイリスト画面等、文脈が必要な操作のため各画面側で個別に処理すること
     * （ここでは「未対応」として案内するのみ）。
     *
     * @return true = ここで処理した（呼び出し元は追加のswitch分岐が不要）
     */
    fun handleControllerClick(slot: Int, viewer: Player): Boolean {
        when (slot) {
            ControllerSlots.PLAY_PAUSE -> togglePlayPause(viewer)
            ControllerSlots.NOW_PLAYING -> openNowPlayingDetail(viewer)
            ControllerSlots.LOOP -> toggleLoop(viewer)
            ControllerSlots.SHUFFLE -> toggleShuffle(viewer)
            ControllerSlots.PREV_SONG, ControllerSlots.NEXT_SONG ->
                viewer.sendMessage("§7この操作はプレイリスト画面内でのみ利用できます。")
            else -> return false
        }
        return true
    }
}