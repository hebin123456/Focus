package me.hebin.focus.data

import android.content.Context
import me.hebin.focus.FocusApp
import org.json.JSONArray
import org.json.JSONObject
import kotlin.random.Random

/** 商店道具定义 */
data class ShopItem(
    val id: String,
    val name: String,
    val desc: String,
    val price: Int,
    /** 即时型：购买立即生效，不入背包（时光回溯） */
    val instant: Boolean = false
)

object ItemCatalog {

    /** 道具 ID */
    const val ID_FORGE = "forge_stone"       // 合成石
    const val ID_SPLIT = "split_stone"       // 分解石
    const val ID_SWAP = "swap_stone"         // 转换石
    const val ID_MEGA_SWAP = "mega_swap"     // 超级转换石
    const val ID_SHIELD = "card_shield"      // 卡片护盾
    const val ID_PAUSE = "pause_ticket"      // 暂停券
    const val ID_LUCKY = "lucky_charm"       // 幸运符
    const val ID_DOUBLE = "double_drop"      // 双倍掉落
    const val ID_MAGNET = "coin_magnet"      // 金币磁铁
    const val ID_UPGRADE = "upgrade_stone"   // 升级石
    const val ID_REWIND = "time_rewind"      // 时光回溯

    /** 卡片补给手动刷新花费（补给区 ⟳ 按钮） */
    const val CARD_REFRESH_COST = 30

    /**
     * 道具清单。一次性消耗品；instant 型购买即生效。
     */
    val items: List<ShopItem> = listOf(
        ShopItem(
            ID_FORGE, "合成石",
            "合成时指定目标卡片：3 张同稀有度卡合成 1 张指定的下一级稀有度卡（不越级；3 张钻石可指定任意钻石卡）",
            100
        ),
        ShopItem(
            ID_SPLIT, "分解石",
            "分解时指定产物：1 张卡分解为 3 张指定的下一级稀有度卡片（可指定同一张，普卡不可分解）",
            100
        ),
        ShopItem(
            ID_UPGRADE, "升级石",
            "1 张卡片直接升 1 级稀有度（普→铜→银→金→钻），无需凑 3 张；钻石卡不可用",
            200
        ),
        ShopItem(
            ID_SWAP, "转换石",
            "1 张卡片转换成随机的另一张同品质卡片（必定不同编号）",
            50
        ),
        ShopItem(
            ID_MEGA_SWAP, "超级转换石",
            "1 张卡片转换成指定的同品质卡片",
            100
        ),
        ShopItem(
            ID_SHIELD, "卡片护盾",
            "专注中意外离开 App / 被浮窗遮挡时自动消耗 1 个，卡片保住不碎（主动放弃不消耗）",
            60
        ),
        ShopItem(
            ID_PAUSE, "暂停券",
            "专注中允许暂停 1 次：倒计时冻结 5 分钟，期间短暂离开也不碎裂（每场限用 1 张）",
            80
        ),
        ShopItem(
            ID_LUCKY, "幸运符",
            "下一次专注完成时自动消耗，掉落卡片稀有度提升 1 级（钻石封顶）",
            50
        ),
        ShopItem(
            ID_DOUBLE, "双倍掉落",
            "下一次专注完成时自动消耗，额外再掉 1 张卡",
            90
        ),
        ShopItem(
            ID_MAGNET, "金币磁铁",
            "放入背包，需要时手动使用：激活 1 小时金币获取翻倍（可叠加延长）",
            100
        ),
        ShopItem(
            ID_REWIND, "时光回溯",
            "购买立即生效：找回最近一次碎裂，按当次专注时长补发 1 张掉落（每条碎裂记录只能回溯 1 次）",
            250,
            instant = true
        )
    )

    fun byId(id: String): ShopItem? = items.firstOrNull { it.id == id }
}

/** 卡片补给窗口单张卡（每小时随机刷新，每张限购 1 次） */
data class ShopCardEntry(
    val cardId: Int,
    val rarity: Rarity,
    val price: Int,
    val bought: Boolean
)

/**
 * 金币与背包（SharedPreferences + JSON）。
 *
 * 金币规则：App 在前台时线性累积，每满 1 分钟 +1 金币。
 * 前台毫秒数由 [FocusApp] 跟踪，退出前台 / 前台期间每 30 秒入账一次，防进程被杀丢进度。
 */
class ShopStore private constructor(context: Context) {

    private val appCtx = context.applicationContext
    private val prefs = appCtx.getSharedPreferences("focus_shop", Context.MODE_PRIVATE)

    /** 已入账的前台毫秒（含不足 1 金币的零头，继续滚存） */
    private var bankedMs: Long
        get() = prefs.getLong(KEY_BANKED_MS, 0L)
        set(v) = prefs.edit().putLong(KEY_BANKED_MS, v.coerceAtLeast(0L)).apply()

    // ---------- 金币磁铁（1 小时金币翻倍） ----------

    /** 磁铁激活截止时刻；未激活返回 0 */
    fun magnetActiveUntil(): Long = prefs.getLong(KEY_MAGNET_UNTIL, 0L)

    fun isMagnetActive(): Boolean = System.currentTimeMillis() < magnetActiveUntil()

    /** 激活 / 叠加延长磁铁（毫秒），返回叠加后的截止时刻 */
    fun extendMagnet(ms: Long): Long {
        val base = maxOf(magnetActiveUntil(), System.currentTimeMillis())
        val until = base + ms
        prefs.edit().putLong(KEY_MAGNET_UNTIL, until).apply()
        return until
    }

    fun addMs(ms: Long) {
        if (ms <= 0) return
        bankedMs += if (isMagnetActive()) ms * 2 else ms
    }

    /** 当前金币数（含前台未入账部分） */
    fun currentCoins(): Int = ((bankedMs + liveMs()) / MS_PER_COIN).toInt()

    /** 距下一枚金币的进度 0..1（前台持续增长） */
    fun nextCoinProgress(): Float {
        val total = bankedMs + liveMs()
        return (total % MS_PER_COIN).toFloat() / MS_PER_COIN
    }

    /** 扣金币（自动把前台零头入账），余额不足返回 false */
    fun trySpend(coins: Int): Boolean {
        if (coins <= 0) return true
        if (currentCoins() < coins) return false
        FocusApp.instance?.bankForegroundMs()
        bankedMs -= coins * MS_PER_COIN
        return true
    }

    /** 充值直充金币（支付成功回调入账，不走前台累积翻倍逻辑） */
    fun grantCoins(coins: Int) {
        if (coins <= 0) return
        FocusApp.instance?.bankForegroundMs()
        bankedMs += coins.toLong() * MS_PER_COIN
    }

    // ---------- 卡片补给（每小时随机刷新，每张限购 1 次） ----------

    /**
     * 当前小时的补给窗口：跨小时自动重刷一批随机卡（编号 + 稀有度均随机）。
     * 稀有度权重 普38 / 铜30 / 银18 / 金11 / 钻3，钻石可遇不可求。
     * 手动刷新（补给区 ⟳ 按钮，花金币）通过 seq 失效当前窗口 → 立即生成新一批。
     */
    fun cardRotation(): List<ShopCardEntry> {
        val bucket = System.currentTimeMillis() / HOUR_MS
        val seq = cardShopSeq()
        val root = runCatching {
            JSONObject(prefs.getString(KEY_CARD_SHOP, "{}"))
        }.getOrDefault(JSONObject())

        if (root.optLong("bucket", -1) != bucket || root.optLong("seq", 0) != seq) {
            val rng = Random(System.currentTimeMillis())
            val ids = (1..CardCatalog.TOTAL).shuffled(rng).take(CARD_WINDOW_SIZE)
            val entries = JSONArray()
            ids.forEach { id ->
                val r = rollCardRarity(rng)
                entries.put(
                    JSONObject()
                        .put("id", id)
                        .put("r", r.ordinal)
                        .put("b", false)
                )
            }
            prefs.edit()
                .putString(
                    KEY_CARD_SHOP,
                    JSONObject()
                        .put("bucket", bucket)
                        .put("seq", seq)
                        .put("entries", entries)
                        .toString()
                )
                .apply()
        }

        val arr = root.takeIf { it.optLong("bucket", -1) == bucket && it.optLong("seq", 0) == seq }
            ?.optJSONArray("entries")
            ?: return cardRotationNow()
        return (0 until arr.length()).mapNotNull { i ->
            arr.optJSONObject(i)?.let { o ->
                ShopCardEntry(
                    o.optInt("id", 1),
                    Rarity.fromOrdinalSafe(o.optInt("r", 0)),
                    CARD_PRICES[Rarity.fromOrdinalSafe(o.optInt("r", 0)).ordinal],
                    o.optBoolean("b", false)
                )
            }
        }
    }

    /** 当前窗口序号（手动刷新 +1 失效旧窗口） */
    private fun cardShopSeq(): Long = prefs.getLong(KEY_CARD_SHOP_SEQ, 0L)

    /** 手动刷新：立即作废当前补给窗口，下次读取时生成新一批 */
    fun refreshCardRotation() {
        prefs.edit().putLong(KEY_CARD_SHOP_SEQ, cardShopSeq() + 1).apply()
    }

    /** 读当前存储的窗口（仅在 bucket 一致时由 cardRotation 调用） */
    private fun cardRotationNow(): List<ShopCardEntry> {
        val root = runCatching {
            JSONObject(prefs.getString(KEY_CARD_SHOP, "{}"))
        }.getOrDefault(JSONObject())
        val arr = root.optJSONArray("entries") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            arr.optJSONObject(i)?.let { o ->
                val r = Rarity.fromOrdinalSafe(o.optInt("r", 0))
                ShopCardEntry(o.optInt("id", 1), r, CARD_PRICES[r.ordinal], o.optBoolean("b", false))
            }
        }
    }

    /** 下一轮刷新时刻（整点边界毫秒） */
    fun cardRotationNextAt(): Long =
        (System.currentTimeMillis() / HOUR_MS + 1) * HOUR_MS

    /** 补给卡购买结果 */
    enum class BuyCardResult { OK, WINDOW_REFRESHED, ALREADY_BOUGHT, NO_COINS }

    /** 购买补给卡：扣金币 → 标记已购 → 入图鉴 */
    fun buyCard(cardId: Int, rarity: Rarity): BuyCardResult {
        cardRotation() // 确保窗口是当前小时的
        val root = runCatching {
            JSONObject(prefs.getString(KEY_CARD_SHOP, "{}"))
        }.getOrDefault(JSONObject())
        val arr = root.optJSONArray("entries") ?: return BuyCardResult.WINDOW_REFRESHED

        var entry: JSONObject? = null
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            if (o.optInt("id") == cardId && Rarity.fromOrdinalSafe(o.optInt("r", 0)) == rarity) {
                entry = o
                break
            }
        }
        val e = entry ?: return BuyCardResult.WINDOW_REFRESHED
        if (e.optBoolean("b", false)) return BuyCardResult.ALREADY_BOUGHT

        val price = CARD_PRICES[rarity.ordinal]
        if (!trySpend(price)) return BuyCardResult.NO_COINS

        e.put("b", true)
        prefs.edit().putString(KEY_CARD_SHOP, root.toString()).apply()
        CollectionRepository.get(appCtx).addCard(cardId, rarity)
        return BuyCardResult.OK
    }

    private fun rollCardRarity(rng: Random): Rarity {
        var x = rng.nextInt(CARD_RARITY_WEIGHTS.sum())
        CARD_RARITY_WEIGHTS.forEachIndexed { i, w ->
            if (x < w) return Rarity.entries[i]
            x -= w
        }
        return Rarity.COMMON
    }

    // ---------- 背包 ----------

    fun itemCount(id: String): Int = invJson().optInt(id, 0)

    /** 背包内容（仅保留有数量的道具） */
    fun inventory(): Map<String, Int> {
        val o = invJson()
        return o.keys().asSequence().associateWith { o.optInt(it, 0) }.filterValues { it > 0 }
    }

    fun addToInventory(id: String, n: Int = 1) {
        val o = invJson()
        o.put(id, o.optInt(id, 0) + n)
        prefs.edit().putString(KEY_INVENTORY, o.toString()).apply()
    }

    /** 消耗道具（工坊定向操作用），数量不足返回 false */
    fun consumeItem(id: String, n: Int = 1): Boolean {
        if (n <= 0) return true
        val o = invJson()
        val cur = o.optInt(id, 0)
        if (cur < n) return false
        val left = cur - n
        if (left > 0) o.put(id, left) else o.remove(id)
        prefs.edit().putString(KEY_INVENTORY, o.toString()).apply()
        return true
    }

    /** 购买：扣金币 → 入背包 */
    fun buy(item: ShopItem): Boolean {
        if (!trySpend(item.price)) return false
        addToInventory(item.id)
        return true
    }

    private fun invJson(): JSONObject = runCatching {
        JSONObject(prefs.getString(KEY_INVENTORY, "{}"))
    }.getOrDefault(JSONObject())

    /** 调试用：金币与背包全部清零（关于弹窗连点触发） */
    fun wipeDebug() {
        prefs.edit().clear().apply()
    }

    /** 调试用：直接设置金币数（换算成 bankedMs 存入） */
    fun debugSetCoins(coins: Int) {
        FocusApp.instance?.bankForegroundMs()
        bankedMs = coins.toLong() * MS_PER_COIN
    }

    private fun liveMs(): Long = FocusApp.instance?.liveForegroundMs() ?: 0L

    companion object {
        /** 前台每满 1 分钟 +1 金币 */
        const val MS_PER_COIN = 60_000L

        /** 补给窗口：每小时刷 5 张 */
        const val CARD_WINDOW_SIZE = 5
        private const val HOUR_MS = 3_600_000L

        /** 补给卡稀有度权重（普/铜/银/金/钻） */
        private val CARD_RARITY_WEIGHTS = intArrayOf(38, 30, 18, 11, 3)

        /** 补给卡售价（普/铜/银/金/钻） */
        private val CARD_PRICES = intArrayOf(20, 40, 80, 150, 300)

        private const val KEY_BANKED_MS = "bankedMs"
        private const val KEY_INVENTORY = "inventory"
        private const val KEY_CARD_SHOP = "cardShop"
        private const val KEY_CARD_SHOP_SEQ = "cardShopSeq"
        private const val KEY_MAGNET_UNTIL = "magnetUntil"

        @Volatile private var instance: ShopStore? = null

        fun get(context: Context): ShopStore =
            instance ?: synchronized(this) {
                instance ?: ShopStore(context).also { instance = it }
            }
    }
}
