package com.github.sahyuya.oyasaiMusic.audio

import com.google.gson.JsonElement
import com.google.gson.JsonParser
import com.sk89q.worldedit.extent.clipboard.Clipboard
import com.sk89q.worldedit.math.BlockVector3
import kotlin.math.roundToInt
import org.bukkit.block.Block
import org.bukkit.block.Sign
import org.bukkit.block.sign.Side
import org.enginehub.linbus.tree.LinCompoundTag
import org.enginehub.linbus.tree.LinListTag
import org.enginehub.linbus.tree.LinStringTag

/**
 * 録音処理（グリッド型・回路型・動的録音）で共通利用する、 「音ブロックの真上(Y+1)にある看板」から Volume(1行目) / Pan(2行目) / Delay(3行目) / 音源(4行目)
 * を 上書き取得するための処理（データ・システム設計書 3章）。
 *
 * 看板の記述例: 1行目: 80 → Volume 80% 2行目: -50 → Pan -50 (左寄り) 3行目: -1/16 → 四分音符を基準に16分音符ぶん早める 4行目:
 * 4.5.1:2 → entity.axolotl.attack の2番パターンへ音色を上書き 数値として解釈できない・行が空の場合はそのフィールドの上書きを行わない(null)。
 */
object SignOverrideProcessor {

  /** 看板4行を一度だけ解析した結果。クリップボードと実ワールドの両経路で同じ意味を使う。 */
  data class Overrides(
      val volume: Int?,
      val pan: Int?,
      val delayMs: Int?,
      val customSound: VanillaSoundCatalog.SoundSelection?,
  )

  private val DELAY_PATTERN = Regex("^([+-]?)(\\d+)(?:\\s*/\\s*(\\d+))?$")

  /** テキスト2行から (volume, pan) の上書き値を解析する。解釈できない場合はnull。 */
  fun parseLines(line1: String?, line2: String?): Pair<Int?, Int?> {
    val volume = line1?.trim()?.removeSuffix("%")?.toIntOrNull()?.coerceIn(0, 100)
    val pan = line2?.trim()?.toIntOrNull()?.coerceIn(-100, 100)
    return volume to pan
  }

  /** 3行目の分数をミリ秒へ変換する。`1/8` は八分音符、`-1/16` は負の十六分音符。 分母を省略した `1` は四分音符1個分として扱う。異常値は無視する。 */
  fun parseDelayMillis(line3: String?, quarterNoteMs: Double): Int? {
    return parseDelayMillisExact(line3, quarterNoteMs)?.roundToInt()
  }

  /** 3行目の遅延を丸めずに返す。複数の遅延や分数拍を組み合わせる録音器では、 最終的に音源へ保存する直前までこの値を保持することで丸め誤差を1回に抑える。 */
  fun parseDelayMillisExact(line3: String?, quarterNoteMs: Double): Double? {
    if (quarterNoteMs <= 0.0 || !quarterNoteMs.isFinite()) return null
    val match = DELAY_PATTERN.matchEntire(line3?.trim().orEmpty()) ?: return null
    val numerator = match.groupValues[2].toLongOrNull() ?: return null
    val denominator = match.groupValues[3].ifEmpty { "1" }.toLongOrNull() ?: return null
    if (denominator <= 0 || numerator > 10_000 || denominator > 10_000) return null
    val sign = if (match.groupValues[1] == "-") -1 else 1
    val millis = sign * quarterNoteMs * numerator.toDouble() / denominator.toDouble()
    return if (millis.isFinite() && millis in Int.MIN_VALUE.toDouble()..Int.MAX_VALUE.toDouble())
        millis
    else null
  }

  /** 実ワールド上のノートブロックを対象に、真上の看板を読み取る。 生演奏録音(/record live)はこちらを使用する。 */
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

  /** 実ワールド上の看板3行目から、指定した四分音符長に対する遅延を取得する。 */
  fun extractDelayFromWorld(noteBlock: Block, quarterNoteMs: Double): Int? {
    val state = noteBlock.getRelative(0, 1, 0).state as? Sign ?: return null
    return try {
      parseDelayMillis(state.getSide(Side.FRONT).getLine(2), quarterNoteMs)
    } catch (_: Exception) {
      null
    }
  }

  /** 実ワールド上の看板4行目から、固定パターンのバニラ音源パスを取得する。 */
  fun extractCustomSoundFromWorld(noteBlock: Block): VanillaSoundCatalog.SoundSelection? {
    val state = noteBlock.getRelative(0, 1, 0).state as? Sign ?: return null
    return try {
      VanillaSoundCatalog.resolveSignLine(state.getSide(Side.FRONT).getLine(3))
    } catch (_: Exception) {
      null
    }
  }

  /**
   * FAWEクリップボード内のノートブロック直上(Y+1)にある看板NBTを読み取る。
   *
   * Sponge Schematic v3から読み込まれたブロックエンティティと、通常の`//copy`で得た看板の両方を扱う。
   * FAWEがv3の`Data`を展開する場合と保持する場合があるため、NBT直下と`Data`内のどちらも確認する。
   * 戻り値がnullなら、呼び出し側は既存互換として実ワールド上の看板を参照できる。
   */
  fun extractFromClipboard(
      clipboard: Clipboard,
      noteBlockPos: BlockVector3,
      quarterNoteMs: Double,
  ): Overrides? {
    return try {
      val signBlock = clipboard.getFullBlock(noteBlockPos.add(0, 1, 0))
      if (!signBlock.blockType.id().endsWith("_sign")) return null
      val nbt = signBlock.nbtReference?.value ?: return null
      val lines = extractSignLines(nbt) ?: return null
      val (volume, pan) = parseLines(lines[0], lines[1])
      Overrides(
          volume = volume,
          pan = pan,
          delayMs = parseDelayMillis(lines[2], quarterNoteMs),
          customSound = VanillaSoundCatalog.resolveSignLine(lines[3]),
      )
    } catch (_: Exception) {
      null
    }
  }

  private fun extractSignLines(root: LinCompoundTag): List<String>? {
    val data = (root.value()["Data"] as? LinCompoundTag) ?: root
    val front = data.value()["front_text"] as? LinCompoundTag
    val messages = front?.value()?.get("messages") as? LinListTag<*>
    if (messages != null) {
      val lines =
          messages.value().take(4).map { tag ->
            val raw = (tag as? LinStringTag)?.value().orEmpty()
            plainSignText(raw)
          }.toMutableList()
      while (lines.size < 4) lines += ""
      return lines
    }

    val legacyKeys = (1..4).map { "Text$it" }
    if (legacyKeys.none { data.value().containsKey(it) }) return null
    return legacyKeys.map { key ->
      val raw = (data.value()[key] as? LinStringTag)?.value().orEmpty()
      plainSignText(raw)
    }
  }

  /** MojangのJSONテキストコンポーネントから、看板に表示される素の文字列を取り出す。 */
  private fun plainSignText(raw: String): String {
    if (raw.isBlank()) return ""
    return try {
      val parsed = JsonParser.parseString(raw)
      buildString { appendJsonText(parsed, this) }
    } catch (_: Exception) {
      raw
    }
  }

  private fun appendJsonText(element: JsonElement?, output: StringBuilder) {
    if (element == null || element.isJsonNull) return
    when {
      element.isJsonPrimitive -> {
        val primitive = element.asJsonPrimitive
        if (primitive.isString) output.append(primitive.asString)
      }
      element.isJsonArray -> element.asJsonArray.forEach { appendJsonText(it, output) }
      element.isJsonObject -> {
        val component = element.asJsonObject
        appendJsonText(component.get("text"), output)
        component.get("extra")?.let { appendJsonText(it, output) }
      }
    }
  }

  /**
   * グリッド型・回路型録音用のメイン経路。
   *
   * [extractFromClipboard]で看板NBTを取得できなかった古いクリップボードや、実ワールドを直接対象とする
   * 既存ワークフローの互換用フォールバックとして使用する。
   */
  fun extractFromWorldPos(world: org.bukkit.World, noteBlockPos: BlockVector3): Pair<Int?, Int?> {
    return try {
      val block = world.getBlockAt(noteBlockPos.x(), noteBlockPos.y(), noteBlockPos.z())
      extractFromWorld(block)
    } catch (_: Exception) {
      null to null
    }
  }

  fun extractDelayFromWorldPos(
      world: org.bukkit.World,
      noteBlockPos: BlockVector3,
      quarterNoteMs: Double,
  ): Int? =
      try {
        extractDelayFromWorld(
            world.getBlockAt(noteBlockPos.x(), noteBlockPos.y(), noteBlockPos.z()),
            quarterNoteMs,
        )
      } catch (_: Exception) {
        null
      }

  /** 回路シミュレーション用の丸め前の看板遅延。 */
  fun extractDelayMillisExactFromWorldPos(
      world: org.bukkit.World,
      noteBlockPos: BlockVector3,
      quarterNoteMs: Double,
  ): Double? =
      try {
        val state =
            world
                .getBlockAt(noteBlockPos.x(), noteBlockPos.y(), noteBlockPos.z())
                .getRelative(0, 1, 0)
                .state as? Sign ?: return null
        parseDelayMillisExact(state.getSide(Side.FRONT).getLine(2), quarterNoteMs)
      } catch (_: Exception) {
        null
      }

  fun extractCustomSoundFromWorldPos(
      world: org.bukkit.World,
      noteBlockPos: BlockVector3,
  ): VanillaSoundCatalog.SoundSelection? =
      try {
        extractCustomSoundFromWorld(
            world.getBlockAt(noteBlockPos.x(), noteBlockPos.y(), noteBlockPos.z())
        )
      } catch (_: Exception) {
        null
      }
}
