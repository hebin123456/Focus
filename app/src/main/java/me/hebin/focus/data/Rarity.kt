package me.hebin.focus.data

/**
 * 卡片稀有度：同一编号的卡片拥有全部 5 个稀有度版本。
 * 序号即等级，用于掉落升级判定。
 */
enum class Rarity(
    val label: String,
    /** 主色（边框、标签） */
    val color: Int,
    /** 卡面渐变起始色 */
    val bgTop: Int,
    /** 卡面渐变结束色 */
    val bgBottom: Int
) {
    COMMON("普卡", 0xFF8B93A7.toInt(), 0xFF1B2130.toInt(), 0xFF141926.toInt()),
    COPPER("铜卡", 0xFFC97A45.toInt(), 0xFF2A2118.toInt(), 0xFF1B1611.toInt()),
    SILVER("银卡", 0xFFC9D4E3.toInt(), 0xFF232B38.toInt(), 0xFF161B24.toInt()),
    GOLD("金卡", 0xFFF5C542.toInt(), 0xFF2E2612.toInt(), 0xFF1C180C.toInt()),
    DIAMOND("钻石卡", 0xFF67E8F9.toInt(), 0xFF122A33.toInt(), 0xFF0C1B22.toInt());

    companion object {
        fun fromOrdinalSafe(o: Int): Rarity = entries.getOrElse(o) { COMMON }
    }
}
