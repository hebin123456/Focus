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
 *  sessionLog  : [{st: 开始毫秒, m: 分钟, d: 深度}, ...] 完成的专注记录（统计图表用，上限 2000 条）
 *  cardLog     : [{ts: 毫秒, id: 编号, r: 稀有度}, ...] 获得卡片记录（"某天集了什么卡"用，上限 2000 条）
 */
class CollectionRepository private constructor(context: Context) {

    private val appCtx = context.applicationContext
    private val prefs = appCtx.getSharedPreferences("focus_cards", Context.MODE_PRIVATE)

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
        pushCardLog(cardId, rarity)
    }

    /** 消耗卡片（分解 / 合成用），数量不足返回 false */
    fun consumeCard(cardId: Int, rarity: Rarity, count: Int = 1): Boolean {
        if (count <= 0) return true
        val root = JSONObject(prefs.getString(KEY_CARDS, "{}"))
        val key = cardId.toString()
        val o = root.optJSONObject(key) ?: return false
        val cur = o.optInt(rarity.ordinal.toString(), 0)
        if (cur < count) return false
        val left = cur - count
        if (left > 0) o.put(rarity.ordinal.toString(), left) else o.remove(rarity.ordinal.toString())
        if (o.length() == 0) root.remove(key) else root.put(key, o)
        prefs.edit().putString(KEY_CARDS, root.toString()).apply()
        cardsCache = null
        return true
    }

    /** 工坊用：所有持有槽位（编号, 稀有度, 数量），按稀有度升序、编号升序 */
    fun ownedSlots(): List<Triple<Int, Rarity, Int>> =
        cardsMap().flatMap { (id, m) -> m.map { (r, c) -> Triple(id, r, c) } }
            .sortedWith(compareBy({ it.second.ordinal }, { it.first }))

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

    /** 完成的深度专注次数（成就用） */
    var deepSessions: Int
        get() = prefs.getInt(KEY_DEEP_SESSIONS, 0)
        private set(v) = prefs.edit().putInt(KEY_DEEP_SESSIONS, v).apply()

    /**
     * 记录一次完成的专注：累加总分钟 + 追加会话日志（统计图表用）。
     * @param startedAt 本次专注开始时刻（用于图表按小时/天聚合）
     * @param deep 是否深度专注
     */
    fun addFocusMinutes(m: Int, startedAt: Long = System.currentTimeMillis(), deep: Boolean = false) {
        totalFocusMinutes += m
        pushSessionLog(m, startedAt, deep)
    }

    fun addFinishedSession() { finishedSessions += 1 }

    // ---------- 专注统计（图表用） ----------

    /** 一条完成的专注记录（统计图表用） */
    data class SessionLog(
        /** 开始时刻(ms) */
        val startedAt: Long,
        /** 专注分钟数 */
        val minutes: Int,
        /** 是否深度专注 */
        val deep: Boolean
    )

    /** 一条获得卡片记录（"某天集了什么卡"用） */
    data class CardLog(
        /** 获得时刻(ms) */
        val ts: Long,
        val cardId: Int,
        val rarity: Rarity
    )

    /** 全部完成的专注记录（时间正序；不含被丢弃的最旧记录） */
    fun sessionLogs(): List<SessionLog> {
        val s = prefs.getString(KEY_SESSION_LOG, null) ?: return emptyList()
        return runCatching {
            val a = JSONArray(s)
            (0 until a.length()).mapNotNull { i ->
                val o = a.optJSONObject(i) ?: return@mapNotNull null
                SessionLog(
                    o.optLong("st", 0L),
                    o.optInt("m", 0),
                    o.optBoolean("d", false)
                )
            }
        }.getOrDefault(emptyList())
    }

    /** 全部获得卡片记录（时间正序） */
    fun cardLogs(): List<CardLog> {
        val s = prefs.getString(KEY_CARD_LOG, null) ?: return emptyList()
        return runCatching {
            val a = JSONArray(s)
            (0 until a.length()).mapNotNull { i ->
                val o = a.optJSONObject(i) ?: return@mapNotNull null
                val id = o.optInt("id", -1)
                if (id in 1..CardCatalog.TOTAL) {
                    CardLog(o.optLong("ts", 0L), id, Rarity.fromOrdinalSafe(o.optInt("r", 0)))
                } else null
            }
        }.getOrDefault(emptyList())
    }

    /** 追加会话日志（时间正序；上限 2000 条，超出丢弃最旧的） */
    private fun pushSessionLog(m: Int, startedAt: Long, deep: Boolean) {
        val arr = runCatching { JSONArray(prefs.getString(KEY_SESSION_LOG, "[]")) }
            .getOrDefault(JSONArray())
        arr.put(JSONObject().put("st", startedAt).put("m", m).put("d", deep))
        while (arr.length() > 2000) arr.remove(0)
        prefs.edit().putString(KEY_SESSION_LOG, arr.toString()).apply()
    }

    /** 追加卡片获得日志（时间正序；上限 2000 条，超出丢弃最旧的） */
    private fun pushCardLog(cardId: Int, rarity: Rarity) {
        val arr = runCatching { JSONArray(prefs.getString(KEY_CARD_LOG, "[]")) }
            .getOrDefault(JSONArray())
        arr.put(
            JSONObject()
                .put("ts", System.currentTimeMillis())
                .put("id", cardId)
                .put("r", rarity.ordinal)
        )
        while (arr.length() > 2000) arr.remove(0)
        prefs.edit().putString(KEY_CARD_LOG, arr.toString()).apply()
    }

    // ---------- 碎裂历史（详情弹窗用，倒序分页读取） ----------

    /** 一条碎裂记录 */
    data class CrackLog(
        val ts: Long,
        val reason: String,
        val minutes: Int,
        val elapsedMs: Long,
        /** 已被时光回溯找回过（每条只能回溯 1 次） */
        val rewound: Boolean = false
    ) {
        /** "坚持了 X 分 Y 秒（完成 Z%）"；minutes<=0 时返回空串 */
        fun statLine(): String = crackStatLine(minutes, elapsedMs)
    }

    fun addCrack(reason: String, minutes: Int, elapsedMs: Long) {
        crackedCount += 1
        val arr = runCatching { JSONArray(prefs.getString(KEY_CRACK_HISTORY, "[]")) }
            .getOrDefault(JSONArray())
        arr.put(
            JSONObject()
                .put("ts", System.currentTimeMillis())
                .put("reason", reason)
                .put("minutes", minutes)
                .put("elapsedMs", elapsedMs)
                .put("rw", false)
        )
        // 上限 500 条，超出丢弃最旧的
        while (arr.length() > 500) arr.remove(0)
        prefs.edit().putString(KEY_CRACK_HISTORY, arr.toString()).apply()
    }

    /** 分页读取碎裂历史（最新在前）；offset 从 0 起 */
    fun crackLogs(limit: Int, offset: Int): List<CrackLog> {
        val s = prefs.getString(KEY_CRACK_HISTORY, null) ?: return emptyList()
        return runCatching {
            val a = JSONArray(s)
            val total = a.length()
            (offset until minOf(offset + limit, total)).mapNotNull { i ->
                // 数组按时间正序存，倒序展示：从尾部往前取
                val o = a.optJSONObject(total - 1 - i) ?: return@mapNotNull null
                CrackLog(
                    o.optLong("ts", 0L),
                    o.optString("reason", ""),
                    o.optInt("minutes", 0),
                    o.optLong("elapsedMs", 0L),
                    o.optBoolean("rw", false)
                )
            }
        }.getOrDefault(emptyList())
    }

    /** 最近一条未回溯的碎裂记录（时光回溯用）；没有返回 null */
    fun latestRewindableCrack(): CrackLog? {
        val s = prefs.getString(KEY_CRACK_HISTORY, null) ?: return null
        return runCatching {
            val a = JSONArray(s)
            for (i in a.length() - 1 downTo 0) {
                val o = a.optJSONObject(i) ?: continue
                if (!o.optBoolean("rw", false)) {
                    return@runCatching CrackLog(
                        o.optLong("ts", 0L),
                        o.optString("reason", ""),
                        o.optInt("minutes", 0),
                        o.optLong("elapsedMs", 0L)
                    )
                }
            }
            null
        }.getOrDefault(null)
    }

    /** 把指定时间的碎裂记录标记为已回溯 */
    fun markCrackRewound(ts: Long) {
        val s = prefs.getString(KEY_CRACK_HISTORY, null) ?: return
        runCatching {
            val a = JSONArray(s)
            for (i in 0 until a.length()) {
                val o = a.optJSONObject(i) ?: continue
                if (o.optLong("ts", 0L) == ts) {
                    o.put("rw", true)
                    break
                }
            }
            prefs.edit().putString(KEY_CRACK_HISTORY, a.toString()).apply()
        }
    }

    /**
     * 时光回溯：找回最近一次未回溯的碎裂，按当次专注时长补 roll 一张卡。
     * 成功返回掉落；没有可回溯的记录返回 null（不扣钱）。
     */
    fun rewindCrack(): DropEngine.Drop? {
        val log = latestRewindableCrack() ?: return null
        val drop = DropEngine.roll(log.minutes)
        addCard(drop.cardId, drop.rarity)
        markCrackRewound(log.ts)
        return drop
    }

    fun addDeepSession() { deepSessions += 1 }

    // ---------- 调试功能（关于弹窗连点触发，BuildConfig.DEBUG_TOOLS 使能） ----------

    /** 清空全部进度：图鉴 / 历史 / 统计 / 成就 / 徽章 / 金币背包（昵称头像保留） */
    fun debugWipe() {
        cardsCache = null
        prefs.edit()
            .remove(KEY_CARDS)
            .remove(KEY_RECENT)
            .remove(KEY_CRACK_HISTORY)
            .remove(KEY_MINUTES)
            .remove(KEY_SESSIONS)
            .remove(KEY_CRACKED)
            .remove(KEY_DEEP_SESSIONS)
            .remove(KEY_ACHIEVEMENTS)
            .remove(KEY_EQUIPPED_BADGE)
            .remove(KEY_PENDING)
            .remove(KEY_PENDING_CRACK)
            .remove(KEY_SESSION_LOG)
            .remove(KEY_CARD_LOG)
            .apply()
        ShopStore.get(appCtx).wipeDebug()
    }

    /** 全收集（101 × 5 稀有度各 10 张，工坊随便测）+ 全成就解锁 + 金币 99999 + 累计专注 6000 分钟 + 碎裂 0 次 + 填充最近收集展示条 */
    fun debugUnlockAll() {
        val root = JSONObject()
        for (id in 1..CardCatalog.TOTAL) {
            val o = JSONObject()
            for (r in Rarity.entries) o.put(r.ordinal.toString(), 10)
            root.put(id.toString(), o)
        }
        // 最近收集：取前 12 张不同编号的卡，稀有度递增分布，保证主页展示条有内容
        val recent = JSONArray()
        for (i in 0 until 12) {
            recent.put(
                JSONObject()
                    .put("id", i + 1)
                    .put("r", Rarity.entries[i % Rarity.entries.size].ordinal)
            )
        }
        cardsCache = null
        prefs.edit()
            .putString(KEY_CARDS, root.toString())
            .putString(KEY_RECENT, recent.toString())
            .putStringSet(KEY_ACHIEVEMENTS, Achievements.defs.map { it.id }.toSet())
            .putInt(KEY_MINUTES, 6000)
            .putInt(KEY_SESSIONS, 120)
            .putInt(KEY_DEEP_SESSIONS, 20)
            .putInt(KEY_CRACKED, 0)
            .apply()
        // 统计图表演示数据：最近 60 天，多数日子 1~3 场专注，三成场次掉卡
        val sessionLog = JSONArray()
        val cardLog = JSONArray()
        val rnd = java.util.Random(42)
        val now = System.currentTimeMillis()
        for (d in 59 downTo 0) {
            val sessions = if (rnd.nextInt(4) == 0) 0 else 1 + rnd.nextInt(3)
            repeat(sessions) {
                val minutes = intArrayOf(10, 25, 45, 60, 90, 120)[rnd.nextInt(6)]
                // 那天的某个过去时刻：now 往前推 d 天，再随机回退 0~15 小时
                val startedAt = now - d * 86_400_000L -
                    rnd.nextInt(15) * 3_600_000L - rnd.nextInt(60) * 60_000L
                sessionLog.put(
                    JSONObject().put("st", startedAt).put("m", minutes)
                        .put("d", rnd.nextInt(5) == 0)
                )
                if (rnd.nextInt(3) == 0) {
                    cardLog.put(
                        JSONObject()
                            .put("ts", startedAt + minutes * 60_000L)
                            .put("id", 1 + rnd.nextInt(CardCatalog.TOTAL))
                            .put("r", rnd.nextInt(3))
                    )
                }
            }
        }
        prefs.edit()
            .putString(KEY_SESSION_LOG, sessionLog.toString())
            .putString(KEY_CARD_LOG, cardLog.toString())
            .apply()
        ShopStore.get(appCtx).debugSetCoins(99_999)
    }

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

    // ---------- 未被告知的碎裂（回来后提示用） ----------

    /** 一次未确认的碎裂：reason + 本次专注的总分钟数 + 已坚持毫秒数 */
    data class PendingCrack(val reason: String, val minutes: Int, val elapsedMs: Long) {
        /** "坚持了 X 分 Y 秒（完成 Z%）"；minutes<=0 时返回空串 */
        fun statLine(): String = crackStatLine(minutes, elapsedMs)
    }

    fun savePendingCrack(pc: PendingCrack) {
        val o = JSONObject()
            .put("reason", pc.reason)
            .put("minutes", pc.minutes)
            .put("elapsedMs", pc.elapsedMs)
        prefs.edit().putString(KEY_PENDING_CRACK, o.toString()).apply()
    }

    fun peekPendingCrack(): PendingCrack? = parsePendingCrack(prefs.getString(KEY_PENDING_CRACK, null))

    /** 取走（用户已知悉后调用；若无返回 null） */
    fun takePendingCrack(): PendingCrack? {
        val pc = peekPendingCrack() ?: return null
        prefs.edit().remove(KEY_PENDING_CRACK).apply()
        return pc
    }

    private fun parsePendingCrack(s: String?): PendingCrack? {
        if (s == null) return null
        return runCatching {
            val o = JSONObject(s)
            PendingCrack(o.getString("reason"), o.optInt("minutes", 0), o.optLong("elapsedMs", 0L))
        }.getOrNull()
    }

    companion object {
        private const val KEY_CARDS = "cards"
        private const val KEY_MINUTES = "totalMinutes"
        private const val KEY_SESSIONS = "sessions"
        private const val KEY_CRACKED = "cracked"
        private const val KEY_CRACK_HISTORY = "crackHistory"
        private const val KEY_DEEP_SESSIONS = "deepSessions"
        private const val KEY_PENDING = "pendingDrop"
        private const val KEY_PENDING_CRACK = "pendingCrack"
        private const val KEY_SESSION_LOG = "sessionLog"
        private const val KEY_CARD_LOG = "cardLog"
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

/** 碎裂统计文案："坚持了 X 分 Y 秒（完成 Z%）" */
fun crackStatLine(minutes: Int, elapsedMs: Long): String {
    if (minutes <= 0) return ""
    val total = minutes * 60_000f
    val pct = if (total > 0f) (elapsedMs / total * 100).toInt().coerceIn(0, 100) else 0
    val m = elapsedMs / 60_000
    val s = (elapsedMs % 60_000) / 1000
    return "坚持了 $m 分 ${s} 秒（完成 $pct%）"
}
