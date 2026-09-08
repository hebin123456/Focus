package me.hebin.focus.data

import android.content.Context
import org.json.JSONObject

/**
 * 图鉴与统计的本地持久化（SharedPreferences + JSON）。
 * Demo 阶段刻意不引入 Room/DataStore，降低依赖；数据量极小完全够用。
 *
 * 存储结构：
 *  cards       : { "17": { "0": 2, "2": 1 }, ... }   // 卡编号 -> (稀有度序号 -> 拥有数量)
 *  totalMinutes: 累计专注分钟
 *  sessions    : 完成的专注次数
 *  cracked     : 碎裂次数
 *  pending     : 后台完成但用户还没看过结果的掉落（进程被杀后恢复用）
 */
class CollectionRepository private constructor(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("focus_cards", Context.MODE_PRIVATE)

    // ---------- 图鉴 ----------

    /** 指定编号各稀有度的拥有数量 */
    fun ownedCounts(cardId: Int): Map<Rarity, Int> {
        val root = JSONObject(prefs.getString(KEY_CARDS, "{}"))
        if (!root.has(cardId.toString())) return emptyMap()
        val o = root.getJSONObject(cardId.toString())
        val result = mutableMapOf<Rarity, Int>()
        for (r in Rarity.entries) {
            val c = o.optInt(r.ordinal.toString(), 0)
            if (c > 0) result[r] = c
        }
        return result
    }

    fun bestRarity(cardId: Int): Rarity? = ownedCounts(cardId).keys.maxByOrNull { it.ordinal }

    fun isOwned(cardId: Int): Boolean = ownedCounts(cardId).isNotEmpty()

    fun addCard(cardId: Int, rarity: Rarity) {
        val root = JSONObject(prefs.getString(KEY_CARDS, "{}"))
        val key = cardId.toString()
        val o = root.optJSONObject(key) ?: JSONObject()
        o.put(rarity.ordinal.toString(), o.optInt(rarity.ordinal.toString(), 0) + 1)
        root.put(key, o)
        prefs.edit().putString(KEY_CARDS, root.toString()).apply()
    }

    /** 已集齐（至少拥有普卡版本）的编号数 */
    fun collectedCount(): Int = (1..CardCatalog.TOTAL).count { isOwned(it) }

    /** 拥有的卡片总张数（含重复与各稀有度） */
    fun totalCardsOwned(): Int {
        val root = JSONObject(prefs.getString(KEY_CARDS, "{}"))
        var total = 0
        for (k in root.keys()) {
            val o = root.optJSONObject(k) ?: continue
            for (rk in o.keys()) total += o.optInt(rk, 0)
        }
        return total
    }

    fun isFullSet(): Boolean = collectedCount() >= CardCatalog.TOTAL

    // ---------- 统计 ----------

    var totalFocusMinutes: Int
        get() = prefs.getInt(KEY_MINUTES, 0)
        private set(v) = prefs.edit().putInt(KEY_MINUTES, v).apply()

    var finishedSessions: Int
        get() = prefs.getInt(KEY_SESSIONS, 0)
        private set(v) = prefs.edit().putInt(KEY_SESSIONS, v).apply()

    var crackedCount: Int
        get() = prefs.getInt(KEY_CRACKED, 0)
        private set(v) = prefs.edit().putInt(KEY_CRACKED, v).apply()

    fun addFocusMinutes(m: Int) { totalFocusMinutes += m }
    fun addFinishedSession() { finishedSessions += 1 }
    fun addCrack() { crackedCount += 1 }

    // ---------- 后台完成的掉落（防丢） ----------

    fun savePendingDrop(drop: DropEngine.Drop) {
        val o = JSONObject()
            .put("cardId", drop.cardId)
            .put("rarity", drop.rarity.ordinal)
        prefs.edit().putString(KEY_PENDING, o.toString()).apply()
    }

    /** 取走 pending（若无返回 null） */
    fun takePendingDrop(): DropEngine.Drop? {
        val s = prefs.getString(KEY_PENDING, null) ?: return null
        prefs.edit().remove(KEY_PENDING).apply()
        return runCatching {
            val o = JSONObject(s)
            DropEngine.Drop(o.getInt("cardId"), Rarity.fromOrdinalSafe(o.getInt("rarity")))
        }.getOrNull()
    }

    companion object {
        private const val KEY_CARDS = "cards"
        private const val KEY_MINUTES = "totalMinutes"
        private const val KEY_SESSIONS = "sessions"
        private const val KEY_CRACKED = "cracked"
        private const val KEY_PENDING = "pendingDrop"

        @Volatile private var instance: CollectionRepository? = null

        fun get(context: Context): CollectionRepository =
            instance ?: synchronized(this) {
                instance ?: CollectionRepository(context).also { instance = it }
            }
    }
}
