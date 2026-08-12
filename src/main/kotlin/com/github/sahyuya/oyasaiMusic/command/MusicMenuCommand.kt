package com.github.sahyuya.oyasaiMusic.command

import com.github.sahyuya.oyasaiMusic.OyasaiMusic
import com.github.sahyuya.oyasaiMusic.gui.MainMenuScreen
import com.github.sahyuya.oyasaiMusic.gui.SongDetailScreen
import com.github.sahyuya.oyasaiMusic.gui.SongSettingsScreen
import org.bukkit.Bukkit
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import org.bukkit.entity.Player

/** `/musicmenu`（エイリアス `/mm`）: トップメニュー(メインメニュー)GUIを開く。 サヒュヤ氏の指示により追加（動作確認をGUIの目視無しでも行えるようにするため）。 */
class MusicMenuCommand(private val plugin: OyasaiMusic) : CommandExecutor, TabCompleter {
  override fun onCommand(
      sender: CommandSender,
      command: Command,
      label: String,
      args: Array<out String>,
  ): Boolean {
    val player = sender as? Player
    if (player == null) {
      sender.sendMessage("§cこのコマンドはプレイヤーのみ実行できます。")
      return true
    }
    when (args.firstOrNull()?.lowercase()) {
      null ->
          plugin.menuManager.open(
              player,
              MainMenuScreen(plugin, plugin.menuManager, player),
              rememberAsPrevious = false,
          )
      "play" ->
          withSong(player, args.getOrNull(1)) { song ->
            plugin.playbackController.play(player, song)
          }
      "open" ->
          withSong(player, args.getOrNull(1)) { song ->
            plugin.menuManager.open(
                player,
                SongDetailScreen(plugin, plugin.menuManager, player, song),
                rememberAsPrevious = false,
            )
            plugin.playbackController.play(player, song)
          }
      "import" -> importOyasaiFile(player, args.getOrNull(1))
      "paste" -> handlePaste(player, args.drop(1))
      else -> player.sendMessage("§e/mm [play|open] <楽曲ID> §7または §e/mm import <ファイル名> §7または §e/mm paste …")
    }
    return true
  }

  private fun importOyasaiFile(player: Player, rawFileName: String?) {
    if (!player.hasPermission("oyasaimusic.import")) {
      player.sendMessage("§cOMMTファイルをインポートする権限がありません。")
      return
    }
    val fileName = rawFileName?.trim().orEmpty()
    if (fileName.isBlank()) {
      player.sendMessage("§e/mm import <ファイル名.oyasai>")
      return
    }
    player.sendMessage("§7$fileName を確認しています。曲が長い場合は完了までお待ちください。")
    val authorUuid = player.uniqueId
    val authorName = player.name
    Bukkit.getScheduler()
        .runTaskAsynchronously(
            plugin,
            Runnable {
              try {
                val result = plugin.oyasaiImportService.importFor(authorUuid, authorName, fileName)
                Bukkit.getScheduler()
                    .runTask(
                        plugin,
                        Runnable {
                          if (!player.isOnline) return@Runnable
                          player.sendMessage(
                              "§aOMMTから${result.noteCount}音をインポートし、非公開の下書きとして保存しました。"
                          )
                          if (result.sourceMoved == false) {
                            player.sendMessage("§e元ファイルをprocessedへ移動できなかったため、同じファイルを再実行しないでください。")
                          }
                          plugin.menuManager.open(
                              player,
                              SongSettingsScreen(plugin, plugin.menuManager, player, result.song),
                              rememberAsPrevious = false,
                          )
                        },
                    )
              } catch (error: Exception) {
                plugin.logger.warning("OMMTインポートに失敗しました ($fileName): ${error.message}")
                Bukkit.getScheduler()
                    .runTask(
                        plugin,
                        Runnable {
                          if (!player.isOnline) return@Runnable
                          player.sendMessage("§cインポートできませんでした: ${error.message ?: "不明なエラー"}")
                        },
                    )
              }
            },
        )
  }

  private fun handlePaste(player: Player, args: List<String>) {
    if (!player.hasPermission("oyasaimusic.import")) {
      player.sendMessage("§cOMMTデータをインポートする権限がありません。")
      return
    }
    try {
      when (args.firstOrNull()?.lowercase()) {
        "begin" -> {
          val count = args.getOrNull(1)?.toIntOrNull()
              ?: throw IllegalArgumentException("分割数を指定してください。")
          val checksum = args.getOrNull(2).orEmpty()
          plugin.oyasaiPasteTransferService.begin(player.uniqueId, count, checksum)
          player.sendMessage("§aOMMTコピペ転送を開始しました。§7 0/$count")
        }
        "add" -> {
          val index = args.getOrNull(1)?.toIntOrNull()
              ?: throw IllegalArgumentException("分割番号を指定してください。")
          val chunk = args.getOrNull(2).orEmpty()
          val (received, expected) = plugin.oyasaiPasteTransferService.add(player.uniqueId, index, chunk)
          if (received == expected || received == 1 || received % 100 == 0) {
            player.sendMessage("§7OMMTデータ受信: $received/$expected")
          }
        }
        "finish" -> finishPasteImport(player)
        "cancel" -> {
          if (plugin.oyasaiPasteTransferService.cancel(player.uniqueId)) {
            player.sendMessage("§eOMMTコピペ転送を取り消しました。")
          } else {
            player.sendMessage("§7進行中のOMMTコピペ転送はありません。")
          }
        }
        else -> player.sendMessage("§e/mm paste begin <分割数> <SHA-256> §7→ §e/mm paste add <番号> <データ> §7→ §e/mm paste finish")
      }
    } catch (error: IllegalArgumentException) {
      player.sendMessage("§cコピペデータを受け取れませんでした: ${error.message ?: "入力が不正です。"}")
    }
  }

  private fun finishPasteImport(player: Player) {
    val transfer = plugin.oyasaiPasteTransferService.seal(player.uniqueId)
    val authorUuid = player.uniqueId
    val authorName = player.name
    player.sendMessage("§7受信データを検証しています。曲が長い場合は完了までお待ちください。")
    Bukkit.getScheduler()
        .runTaskAsynchronously(
            plugin,
            Runnable {
              try {
                val bytes = plugin.oyasaiPasteTransferService.decode(transfer)
                val result = plugin.oyasaiImportService.importBytesFor(authorUuid, authorName, bytes)
                Bukkit.getScheduler()
                    .runTask(
                        plugin,
                        Runnable {
                          if (!player.isOnline) return@Runnable
                          player.sendMessage("§aOMMTのコピペデータから${result.noteCount}音をインポートし、非公開の下書きとして保存しました。")
                          plugin.menuManager.open(
                              player,
                              SongSettingsScreen(plugin, plugin.menuManager, player, result.song),
                              rememberAsPrevious = false,
                          )
                        },
                    )
              } catch (error: Exception) {
                plugin.logger.warning("OMMTコピペインポートに失敗しました (${player.name}): ${error.message}")
                Bukkit.getScheduler()
                    .runTask(
                        plugin,
                        Runnable {
                          if (player.isOnline) {
                            player.sendMessage("§cコピペデータをインポートできませんでした: ${error.message ?: "不明なエラー"}")
                          }
                        },
                    )
              }
            },
        )
  }

  private fun withSong(
      player: Player,
      rawId: String?,
      block: (com.github.sahyuya.oyasaiMusic.model.Song) -> Unit,
  ) {
    val id = rawId?.toLongOrNull()
    if (id == null || id <= 0) {
      player.sendMessage("§c楽曲IDは正の整数で指定してください。")
      return
    }
    Bukkit.getScheduler()
        .runTaskAsynchronously(
            plugin,
            Runnable {
              val song = plugin.songRepository.findById(id)
              Bukkit.getScheduler()
                  .runTask(
                      plugin,
                      Runnable {
                        if (song == null) player.sendMessage("§c楽曲ID $id は見つかりません。")
                        else block(song)
                      },
                  )
            },
        )
  }

  override fun onTabComplete(
      sender: CommandSender,
      command: Command,
      alias: String,
      args: Array<out String>,
  ): List<String> =
      when (args.size) {
        1 ->
            buildList {
                  add("play")
                  add("open")
                  if (sender.hasPermission("oyasaimusic.import")) add("import")
                  if (sender.hasPermission("oyasaimusic.import")) add("paste")
                }
                .filter { it.startsWith(args[0], true) }
        2 ->
            if (args[0].equals("import", ignoreCase = true) &&
                sender.hasPermission("oyasaimusic.import")) {
              plugin.oyasaiImportService.availableFiles(args[1])
            } else if (args[0].equals("paste", ignoreCase = true) &&
                sender.hasPermission("oyasaimusic.import")) {
              listOf("begin", "add", "finish", "cancel").filter { it.startsWith(args[1], true) }
            } else {
              emptyList()
            }
        else -> emptyList()
      }
}
