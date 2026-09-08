package me.hebin.focus.data

/**
 * 卡牌目录：Demo 版共 100 张卡，编号 1..100，卡面暂用数字占位，后续替换美术资源。
 *
 * 不同编号的卡掉落概率不同（编号越大越稀有）：
 *  - 001-060 普卡底
 *  - 061-085 铜卡底
 *  - 086-095 银卡底
 *  - 096-099 金卡底
 *  - 100    钻石卡底
 */
data class CardDef(val id: Int, val baseTier: Rarity)

object CardCatalog {

    const val TOTAL = 100

    val cards: List<CardDef> = (1..TOTAL).map { CardDef(it, baseTierOf(it)) }

    fun byId(id: Int): CardDef? = cards.getOrNull(id - 1)

    fun baseTierOf(id: Int): Rarity = when {
        id <= 60 -> Rarity.COMMON
        id <= 85 -> Rarity.COPPER
        id <= 95 -> Rarity.SILVER
        id <= 99 -> Rarity.GOLD
        id <= 100 -> Rarity.DIAMOND
        else -> Rarity.COMMON
    }

    /** 编号的基础掉落权重：不同编号概率不一样，高稀有底子的编号权重更低 */
    fun baseWeight(id: Int): Double = when (baseTierOf(id)) {
        Rarity.COMMON -> 1.0
        Rarity.COPPER -> 0.34
        Rarity.SILVER -> 0.12
        Rarity.GOLD -> 0.045
        Rarity.DIAMOND -> 0.02
    }

    fun formattedNumber(id: Int): String = id.toString()
}
