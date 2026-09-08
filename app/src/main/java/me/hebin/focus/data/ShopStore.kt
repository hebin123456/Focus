package me.hebin.focus.data

import android.content.Context
import me.hebin.focus.FocusApp
import org.json.JSONObject

/** 商店道具定义（内容待设计，先空着） */
data class ShopItem(
    val id: String,
    val name: String,
    val desc: String,
    val price: Int
)

object ItemCatalog {

    /**
     * 道具清单：暂为空。
     * 确定道具后在这里补充即可，商店 / 背包 / 购买流程均已就绪。
     */
    val items: List<ShopItem> = emptyList()

    fun byId(id: String): ShopItem? = items.firstOrNull { it.id == id }
}

/**
 * 金币与背包（SharedPreferences + JSON）。
 *
 * 金币规则：App 在前台时线性累积，每满 1 分钟 +1 金币。
 * 前台毫秒数由 [FocusApp] 跟踪，退出前台 / 前台期间每 30 秒入账一次，防进程被杀丢进度。
 */
class ShopStore private constructor(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("focus_shop", Context.MODE_PRIVATE)

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

    /** 购买：扣金币 → 入背包 */
    fun buy(item: ShopItem): Boolean {
        if (!trySpend(item.price)) return false
        addToInventory(item.id)
        return true
    }

    private fun invJson(): JSONObject = runCatching {
        JSONObject(prefs.getString(KEY_INVENTORY, "{}"))
    }.getOrDefault(JSONObject())

    private fun liveMs(): Long = FocusApp.instance?.liveForegroundMs() ?: 0L

    companion object {
        /** 前台每满 1 分钟 +1 金币 */
        const val MS_PER_COIN = 60_000L

        private const val KEY_BANKED_MS = "bankedMs"
        private const val KEY_INVENTORY = "inventory"

        @Volatile private var instance: ShopStore? = null

        fun get(context: Context): ShopStore =
            instance ?: synchronized(this) {
                instance ?: ShopStore(context).also { instance = it }
            }
    }
}
