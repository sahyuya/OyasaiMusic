package com.github.sahyuya.oyasaiMusic.command

import com.github.sahyuya.oyasaiMusic.OyasaiMusic
import com.github.sahyuya.oyasaiMusic.gui.MainMenuScreen
import com.github.sahyuya.oyasaiMusic.gui.SongDetailScreen
import com.github.sahyuya.oyasaiMusic.gui.SongSettingsScreen
import com.github.sahyuya.oyasaiMusic.importing.OyasaiPasteTransferService
import io.papermc.paper.dialog.Dialog
import io.papermc.paper.registry.data.dialog.ActionButton
import io.papermc.paper.registry.data.dialog.DialogBase
import io.papermc.paper.registry.data.dialog.action.DialogAction
import io.papermc.paper.registry.data.dialog.action.DialogActionCallback
import io.papermc.paper.registry.data.dialog.body.DialogBody
import io.papermc.paper.registry.data.dialog.input.DialogInput
import io.papermc.paper.registry.data.dialog.input.TextDialogInput
import io.papermc.paper.registry.data.dialog.type.DialogType
import java.time.Duration
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.event.ClickCallback
import net.kyori.adventure.text.format.NamedTextColor
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
      else -> player.sendMessage("§e/mm [play|open] <楽曲ID> §7または §e/mm import <ファイル名> §7または §e/mm paste")
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
    when (args.firstOrNull()?.lowercase()) {
      null -> openPasteDialog(player)
      "cancel" -> {
        if (plugin.oyasaiPasteTransferService.cancel(player.uniqueId)) {
          player.sendMessage("§eOMMTダイアログ転送を取り消しました。")
        } else {
          player.sendMessage("§7進行中のOMMTダイアログ転送はありません。")
        }
      }
      else -> player.sendMessage("§e/mm paste §7でPaperダイアログを開きます。")
    }
  }

  private fun openPasteDialog(player: Player) {
    val options =
        ClickCallback.Options.builder().uses(1).lifetime(Duration.ofMinutes(10)).build()
    val submit =
        ActionButton.builder(Component.text("OMMTデータを送信", NamedTextColor.GREEN))
            .tooltip(Component.text("貼り付けたデータを検証して取り込みます。"))
            .width(180)
            .action(
                DialogAction.customClick(
                    DialogActionCallback { response, audience ->
                      val target = audience as? Player ?: return@DialogActionCallback
                      if (target.uniqueId != player.uniqueId) return@DialogActionCallback
                      acceptPasteDialog(target, response.getText(PASTE_INPUT_KEY).orEmpty())
                    },
                    options,
                )
            )
            .build()
    val cancel =
        ActionButton.builder(Component.text("閉じる", NamedTextColor.GRAY))
            .tooltip(Component.text("進行中の転送も取り消します。"))
            .width(120)
            .action(
                DialogAction.customClick(
                    DialogActionCallback { _, audience ->
                      val target = audience as? Player ?: return@DialogActionCallback
                      if (target.uniqueId != player.uniqueId) return@DialogActionCallback
                      plugin.oyasaiPasteTransferService.cancel(target.uniqueId)
                      target.sendMessage("§7OMMTダイアログを閉じました。")
                    },
                    ClickCallback.Options.builder()
                        .uses(1)
                        .lifetime(Duration.ofMinutes(10))
                        .build(),
                )
            )
            .build()
    val input =
        DialogInput.text(PASTE_INPUT_KEY, Component.text("OMMTサイトでコピーしたデータa"))
            .width(500)
            .maxLength(OyasaiPasteTransferService.MAX_DIALOG_INPUT_CHARACTERS)
            .multiline(TextDialogInput.MultilineOptions.create(null, null))
            .build()
    val base =
        DialogBase.builder(Component.text("OyasaiMusicMidiTranslator", NamedTextColor.GREEN))
            .externalTitle(Component.text("OMMTデータ取り込み"))
            .canCloseWithEscape(true)
            .pause(false)
            .afterAction(DialogBase.DialogAfterAction.CLOSE)
            .body(
                listOf(
                    DialogBody.plainMessage(
                        Component.text(
                            "サイトの「この1回分をコピー」でコピーした文字列を貼り付けてください。大きな曲では、送信後に次の入力画面が開きます。",
                            NamedTextColor.GRAY,
                        ),
                        500,
                    )
                )
            )
            .inputs(listOf(input))
            .build()
    val dialog = Dialog.create { factory ->
      factory.empty().base(base).type(DialogType.confirmation(submit, cancel))
    }
    player.showDialog(dialog)
  }

  private fun acceptPasteDialog(player: Player, text: String) {
    try {
      when (val result = plugin.oyasaiPasteTransferService.receive(player.uniqueId, text)) {
        is OyasaiPasteTransferService.ReceiveResult.Pending -> {
          player.sendMessage("§aOMMTデータを受信しました: ${result.received}/${result.expected}§7 次のデータを貼り付けてください。")
          Bukkit.getScheduler()
              .runTask(plugin, Runnable { if (player.isOnline) openPasteDialog(player) })
        }
        is OyasaiPasteTransferService.ReceiveResult.Complete -> finishPasteImport(player, result.transfer)
      }
    } catch (error: IllegalArgumentException) {
      player.sendMessage("§cコピペデータを受け取れませんでした: ${error.message ?: "入力が不正です。"}")
      Bukkit.getScheduler().runTask(plugin, Runnable { if (player.isOnline) openPasteDialog(player) })
    }
  }

  private fun finishPasteImport(
      player: Player,
      transfer: OyasaiPasteTransferService.SealedTransfer,
  ) {
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
              listOf("cancel").filter { it.startsWith(args[1], true) }
            } else {
              emptyList()
            }
        else -> emptyList()
      }

  private companion object {
    const val PASTE_INPUT_KEY = "ommt_data"
  }
}
