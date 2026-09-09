package me.hebin.focus.ads

import android.app.Activity
import android.content.Context
import android.view.View

/**
 * 开屏广告 SDK 抽象层（结构对齐 [AdApi] / [me.hebin.focus.payment.PaymentApi]）。
 *
 * 现状：默认不挂任何 SDK（[SplashAdManager.loadView] 返回 null），
 * 开屏页自动回退到内置品牌图（四宫格萌宠 + slogan）。
 *
 * 接入真实开屏广告（穿山甲 / 优量汇 / AdMob 等）时：
 *  1. 新建 XxxSplashAdApi 实现本接口，loadSplashView 返回 SDK 的开屏 View；
 *  2. 在 Application 里调用 `SplashAdManager.setProvider(XxxSplashAdApi())`；
 *  3. 开屏页会把该 View 铺满广告容器、隐藏品牌占位图，倒计时结束或点跳过后进入主页。
 *  4. 跳过按钮 / 倒计时逻辑由开屏页统一控制，SDK 层不要自己 finish。
 */
interface SplashAdApi {

    /** Provider 名称（调试/日志用） */
    val name: String

    /** SDK 初始化（Application 上下文） */
    fun initialize(context: Context)

    /**
     * 拉取开屏广告视图。
     * @return SDK 的开屏 View；无填充 / 未就绪返回 null（开屏页回退品牌图）
     */
    fun loadSplashView(activity: Activity): View?
}
