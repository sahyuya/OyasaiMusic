package com.github.sahyuya.oyasaiMusic.audio

import com.github.sahyuya.oyasaiMusic.model.NoteEvent
import com.github.sahyuya.oyasaiMusic.model.Song
import com.github.sahyuya.oyasaiMusic.util.BedrockUtil
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * オーディオエンジン（データ・システム設計書 4章）。
 *
 * メインスレッドとは独立した [ScheduledExecutorService] で音符ごとのタイミングを
 * 高精度にスケジュールし、実際の音送信（Bukkit API呼び出し）だけを
 * メインスレッドへ折り返して実行する。
 *
 * デフォルトの再生方式は [PlaybackMode.ENTITY_EMITTER]（Adventure APIの`Sound.Emitter.self()`、
 * 内部的には`ClientboundSoundEntityPacket`）で、音源をプレイヤー自身に追従させることで
 * 移動による音響の乱れを防いでいる。ステレオ定位(Pan)付きの旧方式は [PlaybackMode.POSITIONAL]
 * として「高音質版」のオプション再生に残してある（[SoundDispatcher]参照）。
 *
 * 注意: `Player#playSound` はPaper上で非同期スレッドから呼び出すと
 * `IllegalStateException: Asynchronous play sound!` で例外になることを確認しているため、
 * メインスレッドへのホップ自体は省略できない。その代わり、以下の対策で安定性を高めている。
 *   - 同一ミリ秒の音符（和音）は1回のスケジュール/ホップにまとめ、tick跨ぎによる和音の
 *     ズレを防止する（[play] 内の `groupedByTime` を参照）。
 *   - スレッドプールを4に増強し、密なノートでもスケジューリング自体がボトルネックにならないようにする。
 * ただし、単発ノート同士の間隔についてはBukkitのtick境界(最大約50ms)に伴う揺らぎが
 * プラットフォーム上の制約として残る。より厳密な安定性が必要な場合はNMSレベルの
 * ネットワーク送信タイミング制御が必要になる。
 */
class PlaybackEngine(
    private val plugin: Plugin,
    private val bedrockPrefix: String,
    private val chordLimit: Int,
    private val defaultMode: PlaybackMode = PlaybackMode.ENTITY_EMITTER,
) {

    private val threadCounter = AtomicInteger(1)
    private val executor: ScheduledExecutorService = Executors.newScheduledThreadPool(
        4,
        ThreadFactory { r -> Thread(r, "OyasaiMusic-Playback-${threadCounter.getAndIncrement()}").apply { isDaemon = true } },
    )

    /**
     * 楽曲を指定リスナー群に対して再生する。
     *
     * @param notes 再生する音符列（[com.oyasai.music.audio.format.SongAudioFile.read] の結果等）
     * @param recipients 再生対象プレイヤー（個人プレイヤー再生なら1人、環境BGMなら範囲内の複数人）
     * @param playbackBpm 再生速度の基準となるBPM。song.bpmと異なる場合、ノート間隔を比例縮小/拡大する
     * @param isAmbientPlayback ジュークボックス等の環境音再生かどうか（視聴回数カウント対象外の判定に使用）
     * @param onListenThresholdReached 各リスナーが総演奏時間の80%まで聴き終えた時点で呼ばれる
     * @param onCompletion 再生が最後まで完了した時点で呼ばれる
     * @param mode 再生方式（[PlaybackMode.ENTITY_EMITTER]がデフォルト、[PlaybackMode.POSITIONAL]は
     *             ステレオ定位付きの「高音質版」オプション再生）
     */
    fun play(
        song: Song,
        notes: List<NoteEvent>,
        recipients: Collection<Player>,
        playbackBpm: Int = song.bpm,
        isAmbientPlayback: Boolean = false,
        onListenThresholdReached: ((Player, Song) -> Unit)? = null,
        onCompletion: ((PlaybackSession) -> Unit)? = null,
        mode: PlaybackMode = defaultMode,
    ): PlaybackSession {
        val session = PlaybackSession(song = song, initialRecipients = recipients, isAmbientPlayback = isAmbientPlayback)
        if (notes.isEmpty() || recipients.isEmpty()) {
            return session
        }

        val scale = if (playbackBpm > 0) song.bpm.toDouble() / playbackBpm else 1.0
        val scaledNotes: List<Pair<Int, NoteEvent>> = notes.mapIndexed { index, note ->
            index to note.copy(timeMs = (note.timeMs * scale).toInt())
        }

        val bedrockSurvivingIndices = computeBedrockSurvivingIndices(scaledNotes)
        val totalDurationMs = scaledNotes.maxOfOrNull { (_, n) -> n.timeMs } ?: 0

        // 同一ミリ秒の音符（和音）をまとめて1回のスケジュール/メインスレッドホップで処理する。
        // 音符ごとに個別スケジュールすると、和音を構成する各音が別々のtickへ振り分けられ
        // 和音のタイミングがズレて聞こえることがあったため、この単位でまとめている。
        val groupedByTime: Map<Int, List<Pair<Int, NoteEvent>>> = scaledNotes.groupBy { (_, note) -> note.timeMs }

        for ((timeMs, group) in groupedByTime) {
            val future = executor.schedule(
                Runnable {
                    if (session.isCancelled) return@Runnable
                    Bukkit.getScheduler().runTask(plugin, Runnable {
                        for ((index, note) in group) {
                            dispatch(session, note, bedrock = index in bedrockSurvivingIndices, mode = mode)
                        }
                    })
                },
                timeMs.toLong(),
                TimeUnit.MILLISECONDS,
            )
            session.scheduledTasks.add(future)
        }

        if (onListenThresholdReached != null) {
            val thresholdMs = (totalDurationMs * 0.8).toLong()
            val future = executor.schedule(
                Runnable {
                    if (session.isCancelled) return@Runnable
                    Bukkit.getScheduler().runTask(plugin, Runnable {
                        for (uuid in session.recipients) {
                            val player = Bukkit.getPlayer(uuid) ?: continue
                            if (player.isOnline) onListenThresholdReached(player, song)
                        }
                    })
                },
                thresholdMs,
                TimeUnit.MILLISECONDS,
            )
            session.scheduledTasks.add(future)
        }

        if (onCompletion != null) {
            val future = executor.schedule(
                Runnable {
                    Bukkit.getScheduler().runTask(plugin, Runnable { onCompletion(session) })
                },
                totalDurationMs.toLong() + 50L,
                TimeUnit.MILLISECONDS,
            )
            session.scheduledTasks.add(future)
        }

        return session
    }

    fun stop(session: PlaybackSession) = session.cancel()

    fun shutdown() {
        executor.shutdownNow()
    }

    private fun dispatch(session: PlaybackSession, note: NoteEvent, bedrock: Boolean, mode: PlaybackMode) {
        for (uuid in session.recipients) {
            val player = Bukkit.getPlayer(uuid) ?: continue
            if (!player.isOnline) continue
            val isBedrockPlayer = BedrockUtil.isBedrock(player, bedrockPrefix)
            if (isBedrockPlayer && !bedrock) continue // 和音間引きでこのプレイヤー種別からは間引かれた音
            SoundDispatcher.play(player, note, mode, isBedrock = isBedrockPlayer)
        }
    }

    /**
     * データ設計書 4-2章のBedrock向け和音間引きルール:
     * 同一ミリ秒・同一楽器の音が [chordLimit] 個以上重なっている場合、
     * 最高音(Pitch最大)と最低音(Pitch最小)の2音のみを残す。
     *
     * @return Bedrockプレイヤーに対して再生してよい音符のインデックス集合
     */
    private fun computeBedrockSurvivingIndices(scaledNotes: List<Pair<Int, NoteEvent>>): Set<Int> {
        val groups = scaledNotes.groupBy { (_, note) -> note.timeMs to note.instrument }
        val surviving = HashSet<Int>()
        for (group in groups.values) {
            if (group.size < chordLimit) {
                group.forEach { (index, _) -> surviving.add(index) }
            } else {
                val minEntry = group.minBy { (_, note) -> note.pitch }
                val maxEntry = group.maxBy { (_, note) -> note.pitch }
                surviving.add(minEntry.first)
                surviving.add(maxEntry.first)
            }
        }
        return surviving
    }
}
