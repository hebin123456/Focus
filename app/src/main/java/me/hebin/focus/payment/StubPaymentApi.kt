package me.hebin.focus.payment

import android.app.Activity
import android.content.Context
import androidx.appcompat.app.AlertDialog

/**
 * 本地模拟支付：未接入任何 SDK，弹「模拟收银台」对话框，点确认必定成功。
 * 用于先把「金币可购买」的产品闭环跑通（档位 → 支付 → 入账 → 消费），
 * 真实支付到位后实现 [PaymentApi] 并在 Application 里 setProvider 替换即可。
 */
class StubPaymentApi : PaymentApi {

    override val name: String = "Stub(模拟支付)"

    override fun initialize(context: Context) = Unit

    /** 模拟阶段视为可用：商店直接展示「选择档位完成支付」 */
    override fun isAvailable(): Boolean = true

    override fun purchase(activity: Activity, sku: CoinSku, onResult: (PaymentResult) -> Unit) {
        AlertDialog.Builder(activity)
            .setTitle("确认支付")
            .setMessage(
                "（模拟收银台，正式版将接入微信 / 支付宝）\n\n" +
                    "支付 ${sku.priceLabel} 获得 ${sku.coins} 金币？"
            )
            .setPositiveButton("确认支付") { d, _ ->
                d.dismiss()
                // 模拟支付：必定成功
                onResult(PaymentResult.Success(sku))
            }
            .setNegativeButton("取消") { d, _ ->
                d.dismiss()
                onResult(PaymentResult.Cancelled)
            }
            .setOnCancelListener {
                // 收银台外点 / 返回键取消
                onResult(PaymentResult.Cancelled)
            }
            .show()
    }
}
