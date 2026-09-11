package me.hebin.focus.audio

/**
 * 白噪音声景目录（XMSLEEP 式：分类 + 中文标签 + 可多选叠加）。
 *
 * 声音本体全部程序化合成（见 [AmbientSynth]）：零音频资源、不增 APK 体积、天然无缝循环。
 *
 * ⚠ 顺序兼容：OFF / WHITE / RAIN / WAVES / FIRE 必须保持前 5 位不变——
 * 旧版本把 enum.ordinal 存进 SharedPreferences（"ambientSound"），
 * [AmbientPrefs] 迁移逻辑依赖该序号映射回声景。
 */
enum class AmbientCategory(val label: String) {
    NOISE("噪声"),   // 经典噪声色：白 / 粉 / 棕
    WEATHER("天气"), // 雨 / 雷雨 / 风
    NATURE("自然"),  // 海浪 / 溪流 / 森林 / 炉火
    ANIMAL("动物"),  // 夜虫 / 鸟鸣 / 猫咪呼噜
    PLACE("场所"),   // 咖啡馆 / 火车
    THING("物品")    // 时钟 / 风扇 / 键盘
}

enum class AmbientSound(val label: String, val category: AmbientCategory) {

    OFF("关闭", AmbientCategory.NOISE),       // 仅作哨兵：多选模式下「全不选」即关闭
    WHITE("白噪", AmbientCategory.NOISE),     // [legacy] 纯白噪声
    RAIN("雨声", AmbientCategory.WEATHER),    // [legacy]
    WAVES("海浪", AmbientCategory.NATURE),    // [legacy]
    FIRE("炉火", AmbientCategory.NATURE),    // [legacy]

    PINK("粉噪", AmbientCategory.NOISE),      // Paul Kellet 1/f 粉噪，听感比白噪柔和
    BROWN("棕噪", AmbientCategory.NOISE),     // 1/f² 棕噪，低沉轰鸣
    THUNDER("雷雨", AmbientCategory.WEATHER), // 雨幕 + 远处闷雷
    WIND("风", AmbientCategory.WEATHER),     // 阵风起伏的呼啸
    STREAM("溪流", AmbientCategory.NATURE),  // 水流 + 气泡叮咚
    FOREST("森林", AmbientCategory.NATURE),   // 风床 + 树叶沙沙
    NIGHT("夜虫", AmbientCategory.ANIMAL),    // 夏夜蟋蟀鸣叫
    BIRDS("鸟鸣", AmbientCategory.ANIMAL),    // 清晨鸟啼短语
    CAT("猫呼噜", AmbientCategory.ANIMAL),    // 猫咪打呼噜
    CAFE("咖啡馆", AmbientCategory.PLACE),    // 人声嘈杂 + 杯碟碰撞
    TRAIN("火车", AmbientCategory.PLACE),     // 车厢轰鸣 + 轨缝哐当
    CLOCK("时钟", AmbientCategory.THING),     // 滴答钟摆
    FAN("风扇", AmbientCategory.THING),      // 恒定风扇嗡鸣
    KEYBOARD("键盘", AmbientCategory.THING);  // 机械键盘敲击

    companion object {
        /** 可播放声景（跳过哨兵 OFF），按分类分组、类内保持声明序 —— 界面展示顺序 */
        val playable: List<AmbientSound> = entries
            .filter { it != OFF }
            .sortedBy { it.category.ordinal }
    }
}
