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
    val price: Int
)

object ItemCatalog {

    /** 道具 ID */
    const val ID_FORGE = "forge_stone"       // 合成石
    const val ID_SPLIT = "split_stone"       // 分解石
    const val ID_SWAP = "swap_stone"         // 转换石
    const val ID_MEGA_SWAP = "mega_swap"     // 超级转换石

    /**
     * 道具清单：工坊定向操作道具。
     * 全部为一次性消耗品，在卡片工坊使用。
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
            ID_SWAP, "转换石",
            "1 张卡片转换成随机的另一张同品质卡片（必定不同编号）",
            50
        ),
        ShopItem(
            ID_MEGA_SWAP, "超级转换石",
            "1 张卡片转换成指定的同品质卡片",
            100
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

    fun addMs(ms: Long) {
        if (ms > 0) bankedMs += ms
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

    // ---------- 卡片补给（每小时随机刷新，每张限购 1 次） ----------

    /**
     * 当前小时的补给窗口：跨小时自动重刷一批随机卡（编号 + 稀有度均随机）。
     * 稀有度权重 普38 / 铜30 / 银18 / 金11 / 钻3，钻石可遇不可求。
     */
    fun cardRotation(): List<ShopCardEntry> {
        val bucket = System.currentTimeMillis() / HOUR_MS
        val root = runCatching {
            JSONObject(prefs.getString(KEY_CARD_SHOP, "{}"))
        }.getOrDefault(JSONObject())

        if (root.optLong("bucket", -1) != bucket) {
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
                    JSONObject().put("bucket", bucket).put("entries", entries).toString()
                )
                .apply()
        }

        val arr = root.takeIf { it.optLong("bucket", -1) == bucket }
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

    /** 购买补给卡：扣金币 → 标记已购 → 入图鉴；已购或金币不足返回 false */
    fun buyCard(cardId: Int, rarity: Rarity): Boolean {
        cardRotation() // 确保窗口是当前小时的
        val root = runCatching {
            JSONObject(prefs.getString(KEY_CARD_SHOP, "{}"))
        }.getOrDefault(JSONObject())
        val arr = root.optJSONArray("entries") ?: return false

        var entry: JSONObject? = null
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            if (o.optInt("id") == cardId && Rarity.fromOrdinalSafe(o.optInt("r", 0)) == rarity) {
                entry = o
                break
            }
        }
        val e = entry ?: return false
        if (e.optBoolean("b", false)) return false

        val price = CARD_PRICES[rarity.ordinal]
        if (!trySpend(price)) return false

        e.put("b", true)
        prefs.edit().putString(KEY_CARD_SHOP, root.toString()).apply()
        CollectionRepository.get(appCtx).addCard(cardId, rarity)
        return true
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

        @Volatile private var instance: ShopStore? = null

        fun get(context: Context): ShopStore =
            instance ?: synchronized(this) {
                instance ?: ShopStore(context).also { instance = it }
            }
    }
}
