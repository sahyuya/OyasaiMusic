package com.github.sahyuya.oyasaiMusic.command

import com.github.sahyuya.oyasaiMusic.OyasaiMusic
import com.github.sahyuya.oyasaiMusic.gui.MainMenuScreen
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player

/**
 * `/musicmenu`（エイリアス `/mm`）: トップメニュー(メインメニュー)GUIを開く。
 * サヒュヤ氏の指示により追加（動作確認をGUIの目視無しでも行えるようにするため）。
 */
class MusicMenuCommand(private val plugin: OyasaiMusic) : CommandExecutor {
    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        val player = sender as? Player
        if (player == null) {
            sender.sendMessage("§cこのコマンドはプレイヤーのみ実行できます。")
            return true
        }
        plugin.menuManager.open(player, MainMenuScreen(plugin, plugin.menuManager, player), rememberAsPrevious = false)
        return true
    }
}