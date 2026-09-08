package me.hebin.focus.data

import android.content.Context
import org.json.JSONArray
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

    /** 图鉴内存缓存：编号 -> (稀有度 -> 数量)。addCard 时失效重建。 */
    private var cardsCache: HashMap<Int, HashMap<Rarity, Int>>? = null

    private fun cardsMap(): HashMap<Int, HashMap<Rarity, Int>> {
        var m = cardsCache
        if (m == null) {
            m = HashMap()
            val root = JSONObject(prefs.getString(KEY_CARDS, "{}"))
            for (k in root.keys()) {
                val id = k.toIntOrNull() ?: continue
                val o = root.optJSONObject(k) ?: continue
                val inner = HashMap<Rarity, Int>()
                for (r in Rarity.entries) {
                    val c = o.optInt(r.ordinal.toString(), 0)
                    if (c > 0) inner[r] = c
                }
                m[id] = inner
            }
            cardsCache = m
        }
        return m
    }

    // ---------- 图鉴 ----------

    /** 指定编号各稀有度的拥有数量 */
    fun ownedCounts(cardId: Int): Map<Rarity, Int> = cardsMap()[cardId] ?: emptyMap()

    fun bestRarity(cardId: Int): Rarity? = ownedCounts(cardId).keys.maxByOrNull { it.ordinal }

    fun isOwned(cardId: Int): Boolean = ownedCounts(cardId).isNotEmpty()

    fun addCard(cardId: Int, rarity: Rarity) {
        val root = JSONObject(prefs.getString(KEY_CARDS, "{}"))
        val key = cardId.toString()
        val o = root.optJSONObject(key) ?: JSONObject()
        o.put(rarity.ordinal.toString(), o.optInt(rarity.ordinal.toString(), 0) + 1)
        root.put(key, o)
        prefs.edit().putString(KEY_CARDS, root.toString()).apply()
        cardsCache = null
        pushRecent(cardId, rarity)
    }

    /** 最近获得的卡（最新在前，主页展示条用） */
    fun recentCards(limit: Int = 12): List<Pair<Int, Rarity>> {
        val arr = prefs.getString(KEY_RECENT, null) ?: return emptyList()
        return runCatching {
            val a = JSONArray(arr)
            (0 until a.length()).mapNotNull { i ->
                val o = a.optJSONObject(i) ?: return@mapNotNull null
                val id = o.optInt("id", -1)
                val r = o.optInt("r", 0)
                if (id in 1..CardCatalog.TOTAL) id to Rarity.fromOrdinalSafe(r) else null
            }.take(limit)
        }.getOrDefault(emptyList())
    }

    /** 记录最近获得（插入头部，超出 20 条截断） */
    private fun pushRecent(cardId: Int, rarity: Rarity) {
        val arr = runCatching {
            JSONArray(prefs.getString(KEY_RECENT, "[]"))
        }.getOrDefault(JSONArray())
        val fresh = JSONArray()
        fresh.put(JSONObject().put("id", cardId).put("r", rarity.ordinal))
        for (i in 0 until arr.length()) {
            if (fresh.length() >= 20) break
            val o = arr.optJSONObject(i) ?: continue
            // 同一张卡连着获得只留最新一条，展示更多样
            if (o.optInt("id") == cardId && o.optInt("r") == rarity.ordinal) continue
            fresh.put(o)
        }
        prefs.edit().putString(KEY_RECENT, fresh.toString()).apply()
    }

    /** 已集齐（至少拥有普卡版本）的编号数 */
    fun collectedCount(): Int = distinctOwnedCount()

    /** 拥有的卡片总张数（含重复与各稀有度） */
    fun totalCardsOwned(): Int = cardsMap().values.sumOf { it.values.sum() }

    fun isFullSet(): Boolean = distinctOwnedCount() >= CardCatalog.TOTAL

    // ---------- 图鉴统计（成就用） ----------

    /** 拥有的不同编号数（任意稀有度） */
    fun distinctOwnedCount(): Int = (1..CardCatalog.TOTAL).count { !cardsMap()[it].isNullOrEmpty() }

    /** 指定稀有度已集齐的编号数（如：铜卡版本已收 37/100） */
    fun rarityOwnedCount(r: Rarity): Int = (1..CardCatalog.TOTAL).count { (cardsMap()[it]?.get(r) ?: 0) > 0 }

    /** 是否拥有过某个稀有度（用于「首次金卡/钻石卡」成就） */
    fun hasRarity(r: Rarity): Boolean = rarityOwnedCount(r) > 0

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

    // ---------- 每日登录（联网校验时间） ----------

    /** 用户昵称（本地资料） */
    var nickname: String
        get() = prefs.getString(KEY_NICKNAME, "专注者") ?: "专注者"
        set(v) = prefs.edit().putString(KEY_NICKNAME, v.trim().ifEmpty { "专注者" }).apply()

    /** 最近一次领奖的本地时区天序号，-1 = 从未领过 */
    var lastClaimDay: Long
        get() = prefs.getLong(KEY_LAST_CLAIM_DAY, -1L)
        set(v) = prefs.edit().putLong(KEY_LAST_CLAIM_DAY, v).apply()

    /** 当前连续登录天数 */
    var loginStreak: Int
        get() = prefs.getInt(KEY_LOGIN_STREAK, 0)
        set(v) = prefs.edit().putInt(KEY_LOGIN_STREAK, v).apply()

    /** 最近一次可信网络时间(ms)，用于离线推算与防回拨 */
    var lastNetworkTimeMs: Long
        get() = prefs.getLong(KEY_LAST_NET_TIME, 0L)
        set(v) = prefs.edit().putLong(KEY_LAST_NET_TIME, v).apply()

    /** 上述网络时间对应的 SystemClock.elapsedRealtime() */
    var lastElapsedRealtimeMs: Long
        get() = prefs.getLong(KEY_LAST_ELAPSED, 0L)
        set(v) = prefs.edit().putLong(KEY_LAST_ELAPSED, v).apply()

    // ---------- 成就与徽章 ----------

    fun unlockedAchievementIds(): Set<String> =
        prefs.getStringSet(KEY_ACHIEVEMENTS, emptySet()) ?: emptySet()

    fun unlockAchievement(id: String) {
        prefs.edit().putStringSet(KEY_ACHIEVEMENTS, unlockedAchievementIds() + id).apply()
    }

    /** 佩戴中的徽章 id，null = 未佩戴 */
    var equippedBadgeId: String?
        get() = prefs.getString(KEY_EQUIPPED_BADGE, null)
        set(v) {
            val e = prefs.edit()
            if (v != null) e.putString(KEY_EQUIPPED_BADGE, v) else e.remove(KEY_EQUIPPED_BADGE)
            e.apply()
        }

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
        private const val KEY_NICKNAME = "nickname"
        private const val KEY_RECENT = "recentCards"
        private const val KEY_LAST_CLAIM_DAY = "lastClaimDay"
        private const val KEY_LOGIN_STREAK = "loginStreak"
        private const val KEY_LAST_NET_TIME = "lastNetworkTime"
        private const val KEY_LAST_ELAPSED = "lastElapsedRealtime"
        private const val KEY_ACHIEVEMENTS = "achievements"
        private const val KEY_EQUIPPED_BADGE = "equippedBadge"

        @Volatile private var instance: CollectionRepository? = null

        fun get(context: Context): CollectionRepository =
            instance ?: synchronized(this) {
                instance ?: CollectionRepository(context).also { instance = it }
            }
    }
}
