package me.hebin.focus.ads

import android.app.Activity
import android.content.Context

/**
 * 激励视频 SDK 抽象层：奖励广告的展示与结果回调，统一走 [AdRewardManager]。
 *
 * 现状：默认挂 [StubAdApi]（本地 3 秒模拟广告，看完即发奖），
 * 商店金币卡已露出「看广告 +10」入口，每日 2 次。
 *
 * 接入真实广告（穿山甲 / 优量汇 GroMore / AdMob 等）时：
 *  1. 新建 XxxAdApi 实现 [AdApi]；
 *  2. 在 Application 里调用 `AdRewardManager.setProvider(XxxAdApi())`；
 *  3. 每日次数上限与单次奖励额度在 [AdRewardManager] 统一管理，SDK 层不要自行入账。
 */
interface AdApi {

    /** Provider 名称（调试/日志用） */
    val name: String

    /** SDK 初始化（Application 上下文） */
    fun initialize(context: Context)

    /** 广告通道是否可用（未填充 / 未配置时 UI 层提示并隐藏入口） */
    fun isAvailable(): Boolean

    /**
     * 展示激励视频；结果通过 onResult 回调（保证主线程）。
     * 只负责「播完没播完」，发奖与每日次数由 [AdRewardManager] 统一处理。
     */
    fun show(activity: Activity, onResult: (AdResult) -> Unit)
}

/** 广告结果 */
sealed class AdResult {
    /** 完整看完（由 AdRewardManager 发奖并计数） */
    data object Rewarded : AdResult()

    /** 失败（无填充 / 今日次数用完等） */
    data class Failed(val reason: String) : AdResult()

    /** 用户中途退出（未看完，不发奖） */
    data object Cancelled : AdResult()
}
