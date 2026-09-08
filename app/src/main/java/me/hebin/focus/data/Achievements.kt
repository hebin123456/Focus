package me.hebin.focus.data

/**
 * 成就系统：22 个成就、按类别分组，每个成就有对应点数；
 * 点数达标解锁四级徽章（青铜/白银/黄金/钻石），徽章可佩戴展示在主页。
 */
object Achievements {

    const val CAT_LOGIN = "每日登录"
    const val CAT_COLL = "图鉴收集"
    const val CAT_FOCUS = "专注修行"
    const val CAT_RARE = "稀有掉落"

    data class Def(
        val id: String,
        val title: String,
        val desc: String,
        val points: Int,
        val category: String,
        /** 当前进度 → (current, target) */
        val progress: (CollectionRepository) -> Pair<Int, Int>
    ) {
        fun isUnlocked(repo: CollectionRepository): Boolean {
            val (cur, target) = progress(repo)
            return cur >= target
        }
    }

    data class Badge(
        val id: String,
        val title: String,
        val minPoints: Int,
        val color: Int
    )

    /** 徽章：按累计点数逐级解锁 */
    val badges = listOf(
        Badge("bronze", "青铜徽章", 30, 0xFFC97A45.toInt()),
        Badge("silver", "白银徽章", 80, 0xFFC9D4E3.toInt()),
        Badge("gold", "黄金徽章", 150, 0xFFF5C542.toInt()),
        Badge("diamond", "钻石徽章", 250, 0xFF67E8F9.toInt())
    )

    val defs = listOf(
        // ---- 每日登录 ----
        Def("login_3", "小试牛刀", "连续登录 3 天", 5, CAT_LOGIN, { it.loginStreak to 3 }),
        Def("login_7", "七日之约", "连续登录 7 天", 10, CAT_LOGIN, { it.loginStreak to 7 }),
        Def("login_14", "半月之约", "连续登录 14 天", 15, CAT_LOGIN, { it.loginStreak to 14 }),
        Def("login_30", "月度坚持", "连续登录 30 天", 30, CAT_LOGIN, { it.loginStreak to 30 }),
        Def("login_100", "百日筑基", "连续登录 100 天", 60, CAT_LOGIN, { it.loginStreak to 100 }),

        // ---- 图鉴收集 ----
        Def("own_10", "初窥门径", "收集 10 种编号卡片", 5, CAT_COLL, { it.distinctOwnedCount() to 10 }),
        Def("own_50", "小有收藏", "收集 50 种编号卡片", 10, CAT_COLL, { it.distinctOwnedCount() to 50 }),
        Def("full_common", "普卡大师", "集齐全部 101 张普卡", 20, CAT_COLL, { it.rarityOwnedCount(Rarity.COMMON) to CardCatalog.TOTAL }),
        Def("full_copper", "铜卡大师", "集齐全部 101 张铜卡", 30, CAT_COLL, { it.rarityOwnedCount(Rarity.COPPER) to CardCatalog.TOTAL }),
        Def("full_silver", "银卡大师", "集齐全部 101 张银卡", 40, CAT_COLL, { it.rarityOwnedCount(Rarity.SILVER) to CardCatalog.TOTAL }),
        Def("full_gold", "金卡大师", "集齐全部 101 张金卡", 60, CAT_COLL, { it.rarityOwnedCount(Rarity.GOLD) to CardCatalog.TOTAL }),
        Def("full_diamond", "钻石收藏家", "集齐全部 101 张钻石卡", 100, CAT_COLL, { it.rarityOwnedCount(Rarity.DIAMOND) to CardCatalog.TOTAL }),

        // ---- 专注修行 ----
        Def("focus_1", "第一份专注", "完成 1 次专注", 5, CAT_FOCUS, { it.finishedSessions to 1 }),
        Def("focus_10", "渐入佳境", "完成 10 次专注", 10, CAT_FOCUS, { it.finishedSessions to 10 }),
        Def("focus_100", "专注百炼", "完成 100 次专注", 20, CAT_FOCUS, { it.finishedSessions to 100 }),
        Def("min_60", "一小时之约", "累计专注 60 分钟", 5, CAT_FOCUS, { it.totalFocusMinutes to 60 }),
        Def("min_600", "十小时长跑", "累计专注 600 分钟", 15, CAT_FOCUS, { it.totalFocusMinutes to 600 }),
        Def("min_3000", "五十小时大师", "累计专注 3000 分钟", 40, CAT_FOCUS, { it.totalFocusMinutes to 3000 }),
        Def("deep_1", "心流初体验", "完成 1 次深度专注", 10, CAT_FOCUS, { it.deepSessions to 1 }),
        Def("deep_10", "深潜大师", "完成 10 次深度专注", 25, CAT_FOCUS, { it.deepSessions to 10 }),

        // ---- 稀有掉落 ----
        Def("first_gold", "金光一闪", "首次获得金卡", 15, CAT_RARE, { (if (it.hasRarity(Rarity.GOLD)) 1 else 0) to 1 }),
        Def("first_diamond", "钻石时刻", "首次获得钻石卡", 30, CAT_RARE, { (if (it.hasRarity(Rarity.DIAMOND)) 1 else 0) to 1 })
    )

    /** 当前累计成就点数 */
    fun points(repo: CollectionRepository): Int {
        val unlocked = repo.unlockedAchievementIds()
        return defs.filter { it.id in unlocked }.sumOf { it.points }
    }

    /** 当前已达成的最高徽章 */
    fun currentBadge(repo: CollectionRepository): Badge? {
        val p = points(repo)
        return badges.lastOrNull { p >= it.minPoints }
    }

    /** 下一个待解锁徽章 */
    fun nextBadge(repo: CollectionRepository): Badge? {
        val p = points(repo)
        return badges.firstOrNull { p < it.minPoints }
    }

    /** 佩戴中的徽章（可能为 null = 未佩戴） */
    fun equippedBadge(repo: CollectionRepository): Badge? {
        val id = repo.equippedBadgeId ?: return null
        val badge = badges.find { it.id == id } ?: return null
        return if (points(repo) >= badge.minPoints) badge else null
    }

    /**
     * 检查全部成就，把新达成的持久化并返回（用于弹提示）。
     * 在主页 onStart、成就页打开等时机调用即可，天然幂等。
     */
    fun checkNew(repo: CollectionRepository): List<Def> {
        val unlocked = repo.unlockedAchievementIds()
        val fresh = defs.filter { it.id !in unlocked && it.isUnlocked(repo) }
        fresh.forEach { repo.unlockAchievement(it.id) }
        return fresh
    }
}
