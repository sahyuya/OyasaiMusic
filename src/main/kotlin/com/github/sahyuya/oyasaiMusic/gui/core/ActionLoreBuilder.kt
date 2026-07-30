package com.github.sahyuya.oyasaiMusic.gui

import com.github.sahyuya.oyasaiMusic.util.BedrockUtil
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.entity.Player

/**
 * アイテムのLoreに「クリックすると何が起きるか」を表示するための共通ヘルパー
 * （サヒュヤ氏の指示: 「アクションする際の、全クリックでのアクション内容の表示」への対応）。
 *
 * Java版プレイヤーには4種のクリック(左/Shift+左/右/Shift+右)の対応表を、
 * 統合版プレイヤーには「現在選択中のモードでは何が起きるか」のみを表示する
 * （統合版はタップ操作のみのため、4種の対応表を見せても実行できず混乱するため）。
 */
object ActionLoreBuilder {

    fun build(
        viewer: Player,
        bedrockPrefix: String,
        category: String,
        primary: String,
        secondary: String,
        tertiary: String,
        quaternary: String,
    ): List<Component> {
        val isBedrock = BedrockUtil.isBedrock(viewer, bedrockPrefix)
        return if (isBedrock) {
            val current = BedrockActionModeService.get(viewer.uniqueId, category)
            val label = when (current) {
                ActionMode.PRIMARY -> primary
                ActionMode.SECONDARY -> secondary
                ActionMode.TERTIARY -> tertiary
                ActionMode.QUATERNARY -> quaternary
            }
            listOf(
                Component.text("タップ (${current.displayName}): ", NamedTextColor.DARK_GRAY)
                    .append(Component.text(label, NamedTextColor.AQUA)),
            )
        } else {
            listOf(
                Component.text("左クリック: ", NamedTextColor.GRAY).append(Component.text(primary, NamedTextColor.GREEN))
                    .append(Component.text("  Shift+左クリック: ", NamedTextColor.GRAY)).append(Component.text(secondary, NamedTextColor.AQUA)),
                Component.text("右クリック: ", NamedTextColor.GRAY).append(Component.text(tertiary, NamedTextColor.GOLD))
                    .append(Component.text("  Shift+右クリック: ", NamedTextColor.GRAY)).append(Component.text(quaternary, NamedTextColor.LIGHT_PURPLE)),
            )
        }
    }
}
