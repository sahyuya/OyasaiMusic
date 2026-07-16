package com.github.sahyuya.oyasaiMusic.gui

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType

/**
 * GUI表示用ItemStackを組み立てるビルダー。
 * 全アイテムに共通で「イタリック無効」を適用する（バニラ既定のイタリックはGUIでは見づらいため）。
 */
class GuiItemBuilder(private val material: Material) {

    private var amount: Int = 1
    private var name: Component? = null
    private val lore = mutableListOf<Component>()
    private var glint: Boolean = false
    private var customModelData: Int? = null
    private val tags = mutableMapOf<NamespacedKey, String>()

    fun amount(amount: Int) = apply { this.amount = amount }
    fun name(component: Component) = apply { this.name = component.decoration(TextDecoration.ITALIC, false) }
    fun lore(vararg lines: Component) = apply { lines.forEach { lore += it.decoration(TextDecoration.ITALIC, false) } }
    fun lore(lines: List<Component>) = apply { lines.forEach { lore += it.decoration(TextDecoration.ITALIC, false) } }
    fun glint(glint: Boolean = true) = apply { this.glint = glint }
    fun customModelData(id: Int?) = apply { this.customModelData = id }
    fun tag(key: NamespacedKey, value: String) = apply { tags[key] = value }

    fun build(): ItemStack {
        val item = ItemStack(material, amount)
        item.editMeta { meta ->
            name?.let { meta.displayName(it) }
            if (lore.isNotEmpty()) meta.lore(lore)
            // Paper 1.20.5+: 偽エンチャント無しで光沢のみ付与できるAPI。
            meta.setEnchantmentGlintOverride(if (glint) true else null)
            customModelData?.let { meta.setCustomModelData(it) }
            val pdc = meta.persistentDataContainer
            tags.forEach { (key, value) -> pdc.set(key, PersistentDataType.STRING, value) }
        }
        return item
    }

    companion object {
        /** 余白埋め（灰色ガラス板等）用の空アイテムを作る。現状は未使用だが今後の調整用に用意。 */
        fun filler(material: Material = Material.GRAY_STAINED_GLASS_PANE): ItemStack =
            GuiItemBuilder(material).name(Component.empty()).build()
    }
}