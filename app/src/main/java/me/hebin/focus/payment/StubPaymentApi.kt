package me.hebin.focus.payment

import android.app.Activity
import android.content.Context

/**
 * 本地占位支付：未接入任何 SDK，所有购买直接失败。
 * 用于先把「金币可购买」的产品闭环搭好，真实支付到位后替换 Provider 即可。
 */
class StubPaymentApi : PaymentApi {

    override val name: String = "Stub(未接入)"

    override fun initialize(context: Context) = Unit

    override fun isAvailable(): Boolean = false

    override fun purchase(activity: Activity, sku: CoinSku, onResult: (PaymentResult) -> Unit) {
        activity.runOnUiThread {
            onResult(PaymentResult.Failed("支付通道尚未开放，敬请期待"))
        }
    }
}
