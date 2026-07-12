package com.github.sahyuya.oyasaiMusic.audio

import com.sk89q.worldedit.extent.clipboard.Clipboard
import com.sk89q.worldedit.math.BlockVector3
import org.bukkit.block.Block
import org.bukkit.block.Sign
import org.bukkit.block.sign.Side

/**
 * 録音処理（グリッド型・回路型・動的録音）で共通利用する、
 * 「音ブロックの真上(Y+1)にある看板」から Volume(1行目) / Pan(2行目) を
 * 上書き取得するための処理（データ・システム設計書 3章）。
 *
 * 看板の記述例:
 *   1行目: 80        → Volume 80%
 *   2行目: -50        → Pan -50 (左寄り)
 * 数値として解釈できない・行が空の場合はそのフィールドの上書きを行わない(null)。
 */
object SignOverrideProcessor {

    /** テキスト2行から (volume, pan) の上書き値を解析する。解釈できない場合はnull。 */
    fun parseLines(line1: String?, line2: String?): Pair<Int?, Int?> {
        val volume = line1?.trim()?.removeSuffix("%")?.toIntOrNull()?.coerceIn(0, 100)
        val pan = line2?.trim()?.toIntOrNull()?.coerceIn(-100, 100)
        return volume to pan
    }

    /**
     * 実ワールド上のノートブロックを対象に、真上の看板を読み取る。
     * 動的録音(/record start)はこちらを使用する。
     */
    fun extractFromWorld(noteBlock: Block): Pair<Int?, Int?> {
        val above = noteBlock.getRelative(0, 1, 0)
        val state = above.state
        if (state !is Sign) return null to null
        return try {
            val front = state.getSide(Side.FRONT)
            parseLines(front.getLine(0), front.getLine(1))
        } catch (_: Exception) {
            null to null
        }
    }

    private val TEXT_FIELD_PATTERN = Regex("\"text\"\\s*:\\s*\"(.*?)\"")

    /**
     * FAWEクリップボード上のノートブロックを対象に、真上の看板を読み取る（グリッド型・回路型録音用）。
     *
     * 注意: WorldEdit 7.3+ のNBTライブラリ(LinBus)の内部API（コンパウンドタグのキー取得方法等）は
     * コンパイル環境（本サンドボックスにFAWE本体を導入できないため）で確認できていない。
     * そのため厳密なタグ探索は行わず、看板を含むブロックのNBTを [Any.toString] した文字列全体から
     * テキストコンポーネント（`{"text":"100"}` 等）を正規表現で拾う、フォーマットに依存しにくい
     * 簡易実装としている。看板には装飾を付けず、数値のみを書く運用を推奨する。
     * 取得に失敗した場合は上書き無し(null, null)を返し、録音自体は継続させる。
     */
    fun extractFromClipboard(clipboard: Clipboard, noteBlockPos: BlockVector3): Pair<Int?, Int?> {
        return try {
            val above = noteBlockPos.add(0, 1, 0)
            if (!clipboard.region.contains(above)) return null to null
            val baseBlock = clipboard.getFullBlock(above)
            val nbt = baseBlock.nbt ?: return null to null

            val matches = TEXT_FIELD_PATTERN.findAll(nbt.toString()).map { it.groupValues[1] }.toList()
            val line1 = matches.getOrNull(0)
            val line2 = matches.getOrNull(1)
            parseLines(line1, line2)
        } catch (_: Exception) {
            // NBT構造が想定外だった場合は上書き無しとして扱う（録音自体は継続させる）
            null to null
        }
    }
}
