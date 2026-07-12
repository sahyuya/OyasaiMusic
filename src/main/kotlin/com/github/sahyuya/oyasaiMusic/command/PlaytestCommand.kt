package com.github.sahyuya.oyasaiMusic.command

import com.github.sahyuya.oyasaiMusic.audio.PlaybackEngine
import com.github.sahyuya.oyasaiMusic.audio.SongAudioFile
import com.github.sahyuya.oyasaiMusic.db.SongRepository
import com.github.sahyuya.oyasaiMusic.model.Song
import org.bukkit.Bukkit
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin
import java.io.File

/**
 * `/playtest` デバッグコマンド（一時的に本機能としては使わない）。
 * 音源データ再生のテスト・聴覚確認用。
 *
 *   /playtest <楽曲ID>           指定楽曲を再生
 *   /playtest stop               再生中の楽曲を停止
 */
class PlaytestCommand(
    private val plugin: Plugin,
    private val songRepository: SongRepository,
    private val playbackEngine: PlaybackEngine,
    private val audioDirectory: File,
) : CommandExecutor, TabCompleter {

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (sender !is Player) {
            sender.sendMessage("このコマンドはプレイヤーのみ実行できます。")
            return true
        }
        if (!sender.hasPermission("oyasaimusic.use")) {
            sender.sendMessage("§cこのコマンドを実行する権限がありません（デバッグ権限が必要）。")
            return true
        }
        if (args.isEmpty()) {
            sendUsage(sender)
            return true
        }

        when (args[0].lowercase()) {
            "stop" -> handleStop(sender)
            else -> handlePlay(sender, args[0])
        }
        return true
    }

    private fun handlePlay(player: Player, songIdStr: String) {
        val songId = songIdStr.toLongOrNull()
        if (songId == null) {
            player.sendMessage("§c楽曲IDは正の整数で指定してください。")
            return
        }

        Bukkit.getScheduler().runTaskAsynchronously(plugin, Runnable {
            try {
                val song = songRepository.findById(songId)
                if (song == null) {
                    Bukkit.getScheduler().runTask(plugin, Runnable {
                        player.sendMessage("§c楽曲ID $songId は見つかりません。")
                    })
                    return@Runnable
                }

                val audioFile = File(audioDirectory, song.fileName)
                if (!audioFile.exists()) {
                    Bukkit.getScheduler().runTask(plugin, Runnable {
                        player.sendMessage("§c音源ファイルが見つかりません: ${song.fileName}")
                    })
                    return@Runnable
                }

                val audioData = SongAudioFile.read(audioFile)
                Bukkit.getScheduler().runTask(plugin, Runnable {
                    val session = playbackEngine.play(
                        song = song,
                        notes = audioData.notes,
                        recipients = listOf(player),
                        playbackBpm = song.bpm,
                        isAmbientPlayback = false,
                    )
                    player.sendMessage("§a再生開始: §f${song.title}§a（§f${audioData.notes.size}§a音符、${audioData.totalDurationMs}ms）")
                })
            } catch (e: Exception) {
                plugin.logger.warning("再生エラー: ${e.message}")
                Bukkit.getScheduler().runTask(plugin, Runnable {
                    player.sendMessage("§c再生処理中にエラーが発生しました: ${e.message}")
                })
            }
        })
    }

    private fun handleStop(player: Player) {
        player.sendMessage("§e再生停止機能は現在未実装です（セッション管理が必要）。")
    }

    private fun sendUsage(sender: CommandSender) {
        sender.sendMessage(
            listOf(
                "§e--- OyasaiMusic /playtest (デバッグ) ---",
                "§7/playtest <楽曲ID>      §f指定楽曲を再生",
                "§7/playtest stop          §f再生中の楽曲を停止（未実装）",
            ).joinToString("\n")
        )
    }

    override fun onTabComplete(
        sender: CommandSender,
        command: Command,
        alias: String,
        args: Array<out String>,
    ): List<String> = when (args.size) {
        1 -> listOf("stop").filter { it.startsWith(args[0].lowercase()) }
        else -> emptyList()
    }
}