package me.hebin.focus.payment

import android.app.Activity
import android.content.Context

/**
 * 支付 SDK 抽象层：金币充值档位 + 结果回调，统一走 [PaymentManager]。
 *
 * 现状：[PaymentManager] 默认挂 [StubPaymentApi]（未接入任何 SDK），
 * 商店金币卡已露出「充值」入口，选择档位后会提示「支付通道接入中」。
 *
 * 接入真实支付（微信 / 支付宝 / 华为 IAP 等）时：
 *  1. 新建 XxxPaymentApi 实现 [PaymentApi]；
 *  2. 在 Application 里调用 `PaymentManager.setProvider(XxxPaymentApi())`；
 *  3. 档位 [CoinSku.id] 与商户后台商品 ID 对齐即可，业务代码无需改动。
 *  4. 入账统一在 [PaymentManager.purchaseCoins] 里完成（ShopStore.grantCoins），不要在 SDK 回调里自行加币。
 */
interface PaymentApi {
    /** Provider 名称（调试/日志用） */
    val name: String

    /** SDK 初始化（Application 上下文） */
    fun initialize(context: Context)

    /** 支付通道是否可用（SDK 未装 / 未配置时 UI 层提示并隐藏入口） */
    fun isAvailable(): Boolean

    /**
     * 发起支付；结果通过 onResult 回调（保证主线程）。
     * 只负责收钱，成功后由 [PaymentManager] 统一入账。
     */
    fun purchase(activity: Activity, sku: CoinSku, onResult: (PaymentResult) -> Unit)
}

/** 金币档位（对齐商户后台商品 ID） */
data class CoinSku(
    /** 商品 ID，接入后映射到微信/支付宝/IAP 商品 */
    val id: String,
    /** 金币数 */
    val coins: Int,
    /** 展示价（如 ¥6） */
    val priceLabel: String,
    /** 角标文案（「超值」等，可空） */
    val tag: String? = null
)

/** 支付结果 */
sealed class PaymentResult {
    /** 支付成功（已由 PaymentManager 入账） */
    data class Success(val sku: CoinSku) : PaymentResult()

    /** 失败（未支付 / SDK 错误） */
    data class Failed(val reason: String) : PaymentResult()

    /** 用户主动取消 */
    data object Cancelled : PaymentResult()
}
