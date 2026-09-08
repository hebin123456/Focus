package me.hebin.focus.data

import kotlin.random.Random

/**
 * 掉落引擎：决定一次专注结束后掉哪张卡、什么稀有度。
 *
 * 规则（数值均为 Demo 阶段，可随时调整）：
 *  1. 先按权重随机抽卡编号。专注时间越长，高稀有底子编号的权重被放大得越多，
 *     即「专注时间越长，越容易出高编号卡」。
 *  2. 再从该卡的底子稀有度出发做升级判定。每次升级概率
 *     p = 0.05 + 0.45 * (minutes / 120)，可连续升级（普→铜→银→金→钻）。
 */
object DropEngine {

    data class Drop(val cardId: Int, val rarity: Rarity)

    /** 时间加成：分钟数 → 高稀有编号的权重倍率 */
    private fun tierBoost(tierOrdinal: Int, minutes: Int): Double =
        1.0 + tierOrdinal * (minutes / 120.0) * 3.2

    /** 单步升级概率 */
    fun upgradeChance(minutes: Int): Double = 0.05 + 0.45 * (minutes / 120.0)

    fun roll(minutes: Int, rng: Random = Random.Default): Drop {
        // ---- 第一阶段：抽编号 ----
        val weights = CardCatalog.cards.map { c ->
            CardCatalog.baseWeight(c.id) * tierBoost(c.baseTier.ordinal, minutes)
        }
        val card = weightedPick(CardCatalog.cards, weights, rng) ?: CardCatalog.cards.first()

        // ---- 第二阶段：稀有度升级 ----
        var tier = card.baseTier.ordinal
        val p = upgradeChance(minutes)
        while (tier < Rarity.DIAMOND.ordinal && rng.nextDouble() < p) tier++

        return Drop(card.id, Rarity.fromOrdinalSafe(tier))
    }

    /**
     * 每日登录奖励：把连续登录天数映射为「等效专注分钟」复用同一套掉落规则，
     * 连续登录越久，卡越好。第 1 天≈10 分钟档，第 23 天起封顶 120 分钟档。
     */
    fun rollForLogin(streak: Int, rng: Random = Random.Default): Drop {
        val equivalentMinutes = (10 + (streak - 1).coerceAtLeast(0) * 5).coerceIn(10, 120)
        return roll(equivalentMinutes, rng)
    }

    private fun <T> weightedPick(items: List<T>, weights: List<Double>, rng: Random): T? {
        val total = weights.sum()
        if (total <= 0.0 || items.isEmpty()) return null
        var r = rng.nextDouble() * total
        for (i in items.indices) {
            r -= weights[i]
            if (r <= 0) return items[i]
        }
        return items.last()
    }
}
