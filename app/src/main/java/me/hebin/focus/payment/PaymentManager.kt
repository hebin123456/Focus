package me.hebin.focus.payment

import android.app.Activity
import android.content.Context
import me.hebin.focus.data.ShopStore

/**
 * 支付分发层：
 *  - Provider 可替换（默认 StubPaymentApi，真实 SDK 到位后 setProvider 替换）；
 *  - 入账统一在这里做（成功 → ShopStore.grantCoins），SDK 回调层不碰金币；
 *  - 业务方只调 [purchaseCoins]，不感知具体 SDK。
 */
object PaymentManager {

    private var provider: PaymentApi = StubPaymentApi()
    private var initialized = false

    /** 接入真实 SDK 后替换默认 Stub（在 Application 里调用） */
    fun setProvider(api: PaymentApi) {
        provider = api
        initialized = false
    }

    fun init(context: Context) {
        if (!initialized) {
            provider.initialize(context.applicationContext)
            initialized = true
        }
    }

    /** 支付通道当前是否可用（未接入 / 未配置时 UI 提示「即将开放」） */
    fun isAvailable(context: Context): Boolean {
        init(context)
        return provider.isAvailable()
    }

    /**
     * 购买金币：支付成功自动入账，结果回调主线程。
     */
    fun purchaseCoins(activity: Activity, sku: CoinSku, onResult: (PaymentResult) -> Unit) {
        init(activity)
        provider.purchase(activity, sku) { r ->
            if (r is PaymentResult.Success) {
                ShopStore.get(activity).grantCoins(r.sku.coins)
            }
            onResult(r)
        }
    }

    /** 金币档位清单（UI 展示用；id 对齐商户后台商品） */
    val coinSkus: List<CoinSku> = listOf(
        CoinSku("coins_68", 68, "¥6"),
        CoinSku("coins_188", 188, "¥18", tag = "超值"),
        CoinSku("coins_388", 388, "¥30"),
        CoinSku("coins_688", 688, "¥48", tag = "最划算")
    )
}
