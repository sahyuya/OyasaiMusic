package com.github.sahyuya.oyasaiMusic.command

import com.github.sahyuya.oyasaiMusic.OyasaiMusic
import com.github.sahyuya.oyasaiMusic.gui.SongDetailScreen
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player

/**
 * `/musicopen <楽曲ID>`: 新曲公開時のチャット通知（UI/UX設計書6章「新曲が公開された際、
 * サーバー全体にチャット通知を行い、クリックで即座にその曲の詳細GUIを開き再生できる。」）から
 * クリックで楽曲詳細画面を開くための内部コマンド。プレイヤーが直接打つことも想定していないため
 * plugin.ymlのusageには載せず、通知メッセージのClickEvent(RUN_COMMAND)からのみ使う想定。
 */
class MusicOpenCommand(private val plugin: OyasaiMusic) : CommandExecutor {
    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        val player = sender as? Player ?: return true
        val songId = args.getOrNull(0)?.toLongOrNull() ?: return true
        val song = plugin.songRepository.findById(songId) ?: run {
            player.sendMessage("§cこの楽曲は見つかりませんでした（削除された可能性があります）。")
            return true
        }
        plugin.menuManager.open(player, SongDetailScreen(plugin, plugin.menuManager, player, song), rememberAsPrevious = false)
        return true
    }
}
