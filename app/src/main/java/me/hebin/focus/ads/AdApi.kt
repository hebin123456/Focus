package me.hebin.focus.ads

import android.app.Activity
import android.content.Context

/**
 * 广告 SDK 抽象层：统一广告位、结果回调，频控见 [AdManager]。
 *
 * 现状：[AdManager] 默认挂 [StubAdApi]（本地模拟，无任何 SDK 依赖），
 * 专注完成弹窗已接入「看广告补领一张」跑通完整闭环。
 *
 * 接入真实 SDK（穿山甲 GroMore / AdMob / 优量汇等）时：
 *  1. 新建 XxxAdApi 实现 [AdApi]；
 *  2. 在 Application 里调用 `AdManager.setProvider(XxxAdApi())`；
 *  3. 广告位与频控策略集中在 [AdPlacement]，业务代码无需改动。
 * 详见 docs/广告接入指南.md
 */

/** 广告位定义（全屏类；横幅需要 View 容器，先占位） */
enum class AdPlacement(
    val label: String,
    /** 两次展示的最小间隔（分钟） */
    val cooldownMinutes: Int,
    /** 每日展示次数上限 */
    val dailyLimit: Int
) {
    REWARD_EXTRA_CARD("激励视频·补领一张卡", cooldownMinutes = 30, dailyLimit = 5),
    INTERSTITIAL_SESSION_END("插屏·专注结束", cooldownMinutes = 60, dailyLimit = 3),
    SPLASH("开屏", cooldownMinutes = 0, dailyLimit = 1),
    HOME_BANNER("横幅·主页（预留，未启用）", cooldownMinutes = 0, dailyLimit = Int.MAX_VALUE)
}

/** 广告展示结果（回调保证在主线程） */
sealed class AdResult {
    /** 激励视频完整看完，可发奖 */
    data object Rewarded : AdResult()

    /** 中途关闭/跳过，无奖励 */
    data object ClosedEarly : AdResult()

    /** 拉取或展示失败 */
    data class Failed(val reason: String) : AdResult()
}

/** 广告能力接口：真实 SDK 与本地模拟都实现这一套 */
interface AdApi {
    /** Provider 名称（调试/日志用） */
    val name: String

    /** SDK 初始化（Application 上下文） */
    fun initialize(context: Context)

    /** 该广告位是否有 ready 的广告缓存 */
    fun isReady(placement: AdPlacement): Boolean

    /** 预加载 */
    fun load(placement: AdPlacement)

    /** 展示；结果通过 onResult 回调（主线程） */
    fun show(activity: Activity, placement: AdPlacement, onResult: (AdResult) -> Unit)
}
