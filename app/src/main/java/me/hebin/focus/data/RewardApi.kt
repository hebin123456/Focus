package me.hebin.focus.data

/**
 * 奖励兑换接口 —— 预留扩展点。
 *
 * 需求：集齐整套卡片可兑换奖品。当前 Demo 使用 [StubRewardApi] 本地实现，
 * 后续接后端时只需新写一个实现类（HTTP 请求奖品库存、风控、订单等），UI 不用动。
 */
interface RewardApi {

    /** 可兑换的奖品套装列表 */
    fun rewardSets(): List<RewardSet>

    /** 检查用户对某个套装的兑换资格 */
    fun eligibility(setId: String): Eligibility

    /** 发起兑换，结果异步回调 */
    fun exchange(setId: String, onResult: (ExchangeResult) -> Unit)
}

data class RewardSet(
    val id: String,
    val title: String,
    val description: String,
    /** 需要集齐的卡编号数量（用于 UI 展示进度） */
    val requiredCardCount: Int
)

data class Eligibility(
    val setId: String,
    val eligible: Boolean,
    val collected: Int,
    val required: Int,
    val message: String
)

data class ExchangeResult(
    val success: Boolean,
    val orderId: String? = null,
    val message: String
)

/** Demo 本地实现：唯一套装「全套 101 张」，集齐即可兑换 */
class StubRewardApi(private val repo: CollectionRepository) : RewardApi {

    override fun rewardSets(): List<RewardSet> = listOf(
        RewardSet(
            id = "full-set-101",
            title = "全图鉴大礼包",
            description = "集齐全部 101 张萌宠卡（任意稀有度即可），兑换神秘大礼包一份",
            requiredCardCount = CardCatalog.TOTAL
        )
    )

    override fun eligibility(setId: String): Eligibility {
        val collected = repo.collectedCount()
        val required = CardCatalog.TOTAL
        return Eligibility(
            setId = setId,
            eligible = collected >= required,
            collected = collected,
            required = required,
            message = if (collected >= required) "已集齐，可立即兑换！"
                      else "还差 ${required - collected} 张卡片，继续专注吧"
        )
    }

    override fun exchange(setId: String, onResult: (ExchangeResult) -> Unit) {
        val e = eligibility(setId)
        if (!e.eligible) {
            onResult(ExchangeResult(false, null, "尚未集齐：${e.message}"))
            return
        }
        // 真实实现这里应调用后端下单接口
        val orderId = "RX-" + System.currentTimeMillis().toString(36).uppercase()
        onResult(ExchangeResult(true, orderId, "兑换成功！订单号 $orderId（演示数据，接后端后生效）"))
    }
}
