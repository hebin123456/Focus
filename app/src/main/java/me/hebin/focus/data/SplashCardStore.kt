package me.hebin.focus.data

import android.content.Context

/**
 * 开屏品牌卡存储：把某张已收集的卡设为开屏页的品牌图。
 * 与桌面图标（activity-alias）无关，仅存本地偏好，随时可换 / 恢复默认。
 */
object SplashCardStore {

    private const val PREFS = "splash_card"
    private const val KEY_CARD = "card"   // "编号|稀有度序号"

    /** 当前设置的开屏卡；null = 未设置（开屏展示默认品牌图） */
    fun get(context: Context): Pair<Int, Rarity>? {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_CARD, null) ?: return null
        val parts = raw.split("|")
        if (parts.size != 2) return null
        val id = parts[0].toIntOrNull() ?: return null
        val r = parts[1].toIntOrNull() ?: return null
        if (id !in 1..CardCatalog.TOTAL || r !in Rarity.entries.indices) return null
        return id to Rarity.entries[r]
    }

    fun set(context: Context, cardId: Int, rarity: Rarity) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_CARD, "$cardId|${rarity.ordinal}").apply()
    }

    /** 恢复默认开屏（四宫格品牌图） */
    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().remove(KEY_CARD).apply()
    }
}
