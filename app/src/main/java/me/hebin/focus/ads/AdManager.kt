package me.hebin.focus.ads

import android.app.Activity
import android.content.Context
import android.content.SharedPreferences
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 广告分发与频控：
 *  - Provider 可替换（默认 StubAdApi，真实 SDK 到位后 setProvider 替换）；
 *  - 频控策略集中在 [AdPlacement]（冷却分钟数 + 每日上限），按自然日重置；
 *  - 业务方只调 [isAvailable] / [maybeShow]，不感知具体 SDK。
 */
object AdManager {

    private const val PREFS = "focus_ads"

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

    /** 该广告位当前是否可展示（就绪 + 频控通过） */
    fun isAvailable(context: Context, placement: AdPlacement): Boolean {
        init(context)
        return provider.isReady(placement) && frequencyOk(context, placement)
    }

    /**
     * 展示广告（展示前再校验一次频控）。
     * @return false 表示当前不可展示（冷却中 / 超每日上限 / 未就绪）
     */
    fun maybeShow(
        activity: Activity,
        placement: AdPlacement,
        onResult: (AdResult) -> Unit
    ): Boolean {
        init(activity)
        if (!provider.isReady(placement) || !frequencyOk(activity, placement)) return false
        recordShown(activity, placement)
        provider.show(activity, placement, onResult)
        return true
    }

    // ---------------- 频控（按自然日重置） ----------------

    private fun prefs(ctx: Context): SharedPreferences =
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** 跨天时清空全部计数 */
    private fun ensureDay(ctx: Context): SharedPreferences {
        val sp = prefs(ctx)
        val today = SimpleDateFormat("yyyyMMdd", Locale.getDefault()).format(Date())
        if (sp.getString("day", null) != today) {
            sp.edit().clear().putString("day", today).apply()
        }
        return sp
    }

    private fun frequencyOk(ctx: Context, p: AdPlacement): Boolean {
        val sp = ensureDay(ctx)
        val cooled = System.currentTimeMillis() - sp.getLong("last_${p.name}", 0L) >= p.cooldownMinutes * 60_000L
        val underLimit = sp.getInt("count_${p.name}", 0) < p.dailyLimit
        return cooled && underLimit
    }

    private fun recordShown(ctx: Context, p: AdPlacement) {
        val sp = ensureDay(ctx)
        sp.edit()
            .putLong("last_${p.name}", System.currentTimeMillis())
            .putInt("count_${p.name}", sp.getInt("count_${p.name}", 0) + 1)
            .apply()
    }
}
