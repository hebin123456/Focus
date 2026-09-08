package me.hebin.focus.data

import kotlin.random.Random

/**
 * 卡片工坊引擎：分解与合成。
 *
 * 规则（结果随机，防止定向凑卡图鉴）：
 *  - 分解：1 张 R 稀有度卡 → 3 张随机编号的 R-1 稀有度卡；普卡不可分解
 *  - 合成：3 张同稀有度卡（可不同编号）→ 1 张随机编号的高一级稀有度卡；
 *          3 张钻石 → 1 张随机编号的钻石卡（相当于重 roll 压轴卡）
 *  - 随机编号沿用图鉴基础掉落权重（编号越稀有权重越低），与专注掉落分布一致
 */
object CraftEngine {

    /** 分解产物稀有度；普卡返回 null（不可分解） */
    fun decomposeTarget(r: Rarity): Rarity? =
        if (r == Rarity.COMMON) null else Rarity.entries[r.ordinal - 1]

    /** 合成产物稀有度：钻石封顶重 roll，其余升一级 */
    fun synthesizeTarget(r: Rarity): Rarity =
        if (r == Rarity.DIAMOND) Rarity.DIAMOND else Rarity.entries[r.ordinal + 1]

    /** 按图鉴基础权重随机抽编号（与掉落同分布） */
    fun rollRandomCardId(rng: Random = Random.Default): Int {
        var x = rng.nextDouble() * CardCatalog.cards.sumOf { CardCatalog.baseWeight(it.id) }
        CardCatalog.cards.forEach { c ->
            x -= CardCatalog.baseWeight(c.id)
            if (x <= 0) return c.id
        }
        return CardCatalog.TOTAL
    }

    /** 分解：成功返回随机获得的 3 张低一级稀有度卡 */
    fun decompose(repo: CollectionRepository, cardId: Int, rarity: Rarity): List<DropEngine.Drop>? {
        val lower = decomposeTarget(rarity) ?: return null
        if (!repo.consumeCard(cardId, rarity, 1)) return null
        return List(3) { DropEngine.Drop(rollRandomCardId(), lower) }
            .also { it.forEach { repo.addCard(it.cardId, it.rarity) } }
    }

    /** 合成：picks 必须是 3 张同稀有度卡（编号可重复） */
    fun synthesize(repo: CollectionRepository, picks: List<Pair<Int, Rarity>>): DropEngine.Drop? {
        if (picks.size != 3) return null
        val r = picks.first().second
        if (picks.any { it.second != r }) return null
        if (!consumePicks(repo, picks)) return null

        val drop = DropEngine.Drop(rollRandomCardId(), synthesizeTarget(r))
        repo.addCard(drop.cardId, drop.rarity)
        return drop
    }

    // ---------- 道具定向操作（消耗品由调用方扣除） ----------

    /** 校验并扣除 picks（3 张同稀有度），不足返回 false */
    private fun consumePicks(repo: CollectionRepository, picks: List<Pair<Int, Rarity>>): Boolean {
        // 先整体校验持有量，再逐组扣除，避免中途失败产生半成品
        val need = HashMap<Pair<Int, Rarity>, Int>()
        picks.forEach { need[it] = (need[it] ?: 0) + 1 }
        need.forEach { (slot, c) ->
            if ((repo.ownedCounts(slot.first)[slot.second] ?: 0) < c) return false
        }
        need.forEach { (slot, c) -> repo.consumeCard(slot.first, slot.second, c) }
        return true
    }

    /** 分解石定向分解：1 张卡 → 3 张指定编号的低一级稀有度卡（可指定同一张） */
    fun decomposeInto(
        repo: CollectionRepository,
        cardId: Int,
        rarity: Rarity,
        targetId: Int
    ): List<DropEngine.Drop>? {
        val lower = decomposeTarget(rarity) ?: return null
        if (targetId !in 1..CardCatalog.TOTAL) return null
        if (!repo.consumeCard(cardId, rarity, 1)) return null
        repeat(3) { repo.addCard(targetId, lower) }
        return List(3) { DropEngine.Drop(targetId, lower) }
    }

    /** 合成石定向合成：3 张同稀有度卡 → 1 张指定编号的高一级稀有度卡（不越级；钻石→指定钻石） */
    fun synthesizeInto(
        repo: CollectionRepository,
        picks: List<Pair<Int, Rarity>>,
        targetId: Int
    ): DropEngine.Drop? {
        if (picks.size != 3) return null
        val r = picks.first().second
        if (picks.any { it.second != r }) return null
        if (targetId !in 1..CardCatalog.TOTAL) return null
        if (!consumePicks(repo, picks)) return null

        val drop = DropEngine.Drop(targetId, synthesizeTarget(r))
        repo.addCard(drop.cardId, drop.rarity)
        return drop
    }

    /** 转换石随机转换：1 张卡 → 随机同品质的另一张（必定不同编号） */
    fun convertRandom(
        repo: CollectionRepository,
        cardId: Int,
        rarity: Rarity,
        rng: Random = Random.Default
    ): DropEngine.Drop? {
        if (!repo.consumeCard(cardId, rarity, 1)) return null
        val candidates = (1..CardCatalog.TOTAL).filter { it != cardId }
        val newId = candidates[rng.nextInt(candidates.size)]
        repo.addCard(newId, rarity)
        return DropEngine.Drop(newId, rarity)
    }

    /** 超级转换石定向转换：1 张卡 → 指定编号的同品质卡 */
    fun convertInto(
        repo: CollectionRepository,
        cardId: Int,
        rarity: Rarity,
        targetId: Int
    ): DropEngine.Drop? {
        if (targetId == cardId || targetId !in 1..CardCatalog.TOTAL) return null
        if (!repo.consumeCard(cardId, rarity, 1)) return null
        repo.addCard(targetId, rarity)
        return DropEngine.Drop(targetId, rarity)
    }
}
