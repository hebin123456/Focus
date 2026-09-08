package me.hebin.focus.data

/**
 * 卡牌目录：共 101 张萌宠卡（哺乳 / 鸟类 / 海洋 / 爬行两栖 / 昆虫软体），
 * 卡面美术位于 assets/cards/NNN.webp（512px 透明底，cut_square 紧裁版）。
 *
 * 编号稀有度底子（编号越大越稀有，掉落权重越低）：
 *  - 001-060 普卡底（60）
 *  - 061-085 铜卡底（25）
 *  - 086-095 银卡底（10）
 *  - 096-100 金卡底（5）
 *  - 101    钻石底（1，压轴的大黄蜂）
 */
data class CardDef(
    val id: Int,
    val baseTier: Rarity,
    val name: String,      // 中文名
    val species: String,   // 英文种名（与切图文件名一致）
    val category: String   // 图鉴分类
)

object CardCatalog {

    const val TOTAL = 101

    const val CAT_MAMMAL = "哺乳"
    const val CAT_BIRD = "鸟类"
    const val CAT_OCEAN = "海洋"
    const val CAT_REPTILE = "爬行两栖"
    const val CAT_INSECT = "昆虫软体"

    /** id -> (中文名, 英文种名)；顺序即编号 1..101 */
    private val SPECIES = listOf(
        "rabbit" to "小兔", "squirrel" to "松鼠", "monkey" to "猴子", "tiger" to "老虎",
        "bear" to "小熊", "elephant" to "大象", "lion" to "狮子", "pony" to "小马",
        "piglet" to "小猪", "calf" to "小牛", "lamb" to "小羊", "alpaca" to "羊驼",
        "deer" to "小鹿", "fox" to "狐狸", "hedgehog" to "刺猬", "sloth" to "树懒",
        "koala" to "考拉", "panda" to "熊猫", "puppy" to "小狗", "kitten" to "小猫",
        "hamster" to "仓鼠",
        "penguin" to "企鹅", "owl" to "猫头鹰", "parrot" to "鹦鹉", "duckling" to "小鸭",
        "chick" to "小鸡", "swan" to "天鹅", "flamingo" to "火烈鸟", "peacock" to "孔雀",
        "hummingbird" to "蜂鸟", "toucan" to "巨嘴鸟", "eagle" to "老鹰", "pigeon" to "鸽子",
        "ostrich" to "鸵鸟", "pelican" to "鹈鹕", "seagull" to "海鸥", "sparrow" to "麻雀",
        "swallow" to "燕子", "crane" to "仙鹤", "egret" to "白鹭", "cuckoo" to "布谷鸟",
        "dolphin" to "海豚", "whale" to "鲸鱼", "jellyfish" to "水母", "octopus" to "章鱼",
        "seaturtle" to "海龟", "clownfish" to "小丑鱼", "pufferfish" to "河豚", "seahorse" to "海马",
        "starfish" to "海星", "crab" to "螃蟹", "lobster" to "龙虾", "shrimp" to "小虾",
        "shark" to "鲨鱼", "seal" to "海豹", "sealion" to "海狮", "seaotter" to "海獭",
        "squid" to "鱿鱼", "scallop" to "扇贝", "hermit" to "寄居蟹", "mantaray" to "蝠鲼",
        "frog" to "青蛙", "babyturtle" to "小龟", "lizard" to "蜥蜴", "chameleon" to "变色龙",
        "gecko" to "壁虎", "snake" to "蛇", "crocodile" to "鳄鱼", "salamander" to "蝾螈",
        "treefrog" to "树蛙", "toad" to "蟾蜍", "tortoise" to "陆龟", "beardeddragon" to "鬃狮蜥",
        "frilledlizard" to "伞蜥", "hornedfrog" to "角蛙", "iguana" to "鬣蜥", "axolotl" to "六角恐龙",
        "marineiguana" to "海鬣蜥", "crestedgecko" to "睫角守宫", "hognosesnake" to "猪鼻蛇",
        "paintedturtle" to "锦龟",
        "babybee" to "小蜜蜂", "butterfly" to "蝴蝶", "ladybug" to "瓢虫", "snail" to "蜗牛",
        "caterpillar" to "毛毛虫", "ant" to "蚂蚁", "dragonfly" to "蜻蜓", "firefly" to "萤火虫",
        "rhinocerosbeetle" to "独角仙", "mantis" to "螳螂", "spider" to "蜘蛛", "stickinsect" to "竹节虫",
        "cicada" to "蝉", "grasshopper" to "蚱蜢", "cricket" to "蟋蟀", "earthworm" to "蚯蚓",
        "pillbug" to "鼠妇", "weevil" to "象鼻虫", "longhornbeetle" to "天牛", "bumblebee" to "大黄蜂"
    )

    private fun categoryOf(id: Int): String = when (id) {
        in 1..21 -> CAT_MAMMAL
        in 22..41 -> CAT_BIRD
        in 42..61 -> CAT_OCEAN
        in 62..81 -> CAT_REPTILE
        else -> CAT_INSECT
    }

    val cards: List<CardDef> = (1..TOTAL).map { id ->
        val (species, name) = SPECIES[id - 1]
        CardDef(id, baseTierOf(id), name, species, categoryOf(id))
    }

    fun byId(id: Int): CardDef? = cards.getOrNull(id - 1)

    fun nameOf(id: Int): String = byId(id)?.name ?: "未知"

    fun categoryOfId(id: Int): String = byId(id)?.category ?: ""

    /** 展示名：No.018 熊猫 */
    fun displayName(id: Int): String = "No.%03d %s".format(id, nameOf(id))

    fun baseTierOf(id: Int): Rarity = when {
        id <= 60 -> Rarity.COMMON
        id <= 85 -> Rarity.COPPER
        id <= 95 -> Rarity.SILVER
        id <= 100 -> Rarity.GOLD
        id <= 101 -> Rarity.DIAMOND
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

    fun formattedNumber(id: Int): String = "No.%03d".format(id)
}
