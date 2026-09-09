package me.hebin.focus.ads

import android.app.Activity
import android.content.Context
import android.view.View

/**
 * 开屏广告分发层（对齐 [AdRewardManager] / [me.hebin.focus.payment.PaymentManager] 的结构）：
 *  - Provider 可替换，默认为 null（无 SDK → 开屏页展示内置品牌图）；
 *  - 只负责「拿广告 View」，倒计时 / 跳过 / 进主页由 SplashActivity 统一控制。
 */
object SplashAdManager {

    @Volatile
    private var provider: SplashAdApi? = null

    private var initialized = false

    /** 接入真实开屏 SDK 后替换（在 Application 里调用一次） */
    fun setProvider(api: SplashAdApi) {
        provider = api
        initialized = false
    }

    fun init(context: Context) {
        val p = provider ?: return
        if (!initialized) {
            p.initialize(context.applicationContext)
            initialized = true
        }
    }

    /** 当前是否挂了开屏 SDK（UI 层可据此隐藏/显示「广告位」角标） */
    fun hasProvider(): Boolean = provider != null

    /**
     * 拉取开屏广告视图：有 SDK 且填充成功返回 View，否则 null（回退品牌图）。
     * 主线程调用；SDK 内部异步预加载应在 initialize 阶段完成。
     */
    fun loadView(activity: Activity): View? {
        init(activity)
        return runCatching { provider?.loadSplashView(activity) }.getOrNull()
    }
}
