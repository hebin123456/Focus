package me.hebin.focus.ads

import android.app.Activity
import android.content.Context
import me.hebin.focus.data.ShopStore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 激励广告分发层（对齐 [me.hebin.focus.payment.PaymentManager] 的结构）：
 *  - Provider 可替换（默认 [StubAdApi] 模拟激励视频，真实 SDK 到位后 setProvider 替换）；
 *  - 每日次数上限与发奖统一在这里做（看完 → ShopStore.grantCoins + 当日计数），SDK 层不碰金币；
 *  - 次数按「天」重置：prefs 里存日期键，跨天读取时自动归零，无需定时任务。
 */
object AdRewardManager {

    /** 每日可看次数 */
    const val DAILY_LIMIT = 2

    /** 单次奖励金币 */
    const val REWARD_COINS = 10

    private var provider: AdApi = StubAdApi()
    private var initialized = false

    /** 接入真实 SDK 后替换默认 Stub（在 Application 里调用） */
    fun setProvider(api: AdApi) {
        provider = api
        initialized = false
    }

    fun init(context: Context) {
        if (!initialized) {
            provider.initialize(context.applicationContext)
            initialized = true
        }
    }

    /** 广告通道是否可用（模拟阶段恒为 true） */
    fun isAvailable(context: Context): Boolean {
        init(context)
        return provider.isAvailable()
    }

    // ---------- 每日次数（跨天自动重置） ----------

    /** 今日已看次数 */
    fun watchedToday(context: Context): Int {
        val p = prefs(context)
        return if (p.getString(KEY_DAY, "") == todayKey()) p.getInt(KEY_COUNT, 0) else 0
    }

    /** 今日剩余次数 */
    fun leftToday(context: Context): Int =
        (DAILY_LIMIT - watchedToday(context)).coerceAtLeast(0)

    /**
     * 看激励广告：先检查当日次数 → 拉起广告 → 看完发奖并计数。
     * 结果回调保证主线程。
     */
    fun showRewardedAd(activity: Activity, onResult: (AdResult) -> Unit) {
        init(activity)
        if (!provider.isAvailable()) {
            onResult(AdResult.Failed("广告还没准备好，稍后再试试"))
            return
        }
        if (leftToday(activity) <= 0) {
            onResult(AdResult.Failed("今天 ${DAILY_LIMIT} 次广告已看完，明天再来"))
            return
        }
        provider.show(activity) { r ->
            if (r is AdResult.Rewarded) {
                // 发奖 + 当日计数（同一次 prefs 写入）
                ShopStore.get(activity).grantCoins(REWARD_COINS)
                prefs(activity).edit()
                    .putString(KEY_DAY, todayKey())
                    .putInt(KEY_COUNT, watchedToday(activity) + 1)
                    .apply()
            }
            onResult(r)
        }
    }

    // ---------- 内部 ----------

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences("focus_ads", Context.MODE_PRIVATE)

    private fun todayKey(): String =
        SimpleDateFormat("yyyyMMdd", Locale.CHINA).format(Date())

    private const val KEY_DAY = "day"
    private const val KEY_COUNT = "count"
}
