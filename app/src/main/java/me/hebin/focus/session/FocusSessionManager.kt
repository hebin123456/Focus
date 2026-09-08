package me.hebin.focus.session

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import me.hebin.focus.data.CollectionRepository
import me.hebin.focus.data.DropEngine
import me.hebin.focus.data.ItemCatalog
import me.hebin.focus.data.Rarity
import me.hebin.focus.data.ShopStore

/**
 * 专注会话管理器（进程级单例）。
 *
 * 生命周期：
 *   start() ──→ Focusing ──┬─→ Success（倒计时自然结束，掉落卡片）
 *                          ├─→ Cracked（离开 App / 主动放弃，卡片碎裂）
 *                          └─→ reset() 回到 Idle
 *
 * 计时使用协程，App 退到后台（如锁屏）期间计时不停；
 * 进程若被系统杀死，通过 Repository 的 pending 机制恢复结果。
 *
 * 道具挂钩：
 *  - 卡片护盾：意外离开 / 被遮挡时自动消耗 1 个，豁免本次碎裂（主动放弃不消耗）
 *  - 暂停券：pause() 冻结倒计时 5 分钟，期间离开也不判碎裂（每场限 1 张）
 *  - 幸运符 / 双倍掉落：自然完成时自动消耗并生效
 */
object FocusSessionManager {

    /** 暂停券冻结时长 */
    const val PAUSE_MS = 5 * 60_000L

    sealed class State {
        data object Idle : State()
        data class Focusing(
            val startedAt: Long,
            val endAt: Long,
            val totalMs: Long,
            val minutes: Int,
            /** 深度专注模式：全屏沉浸，完成金币翻倍 */
            val deep: Boolean = false,
            /** 暂停券生效截止时刻；0 = 未暂停 */
            val pausedUntil: Long = 0L
        ) : State()

        data class Success(
            val drop: DropEngine.Drop,
            val minutes: Int,
            val fromBackground: Boolean = false,
            /** 双倍掉落额外获得的一张（可能为 null） */
            val extraDrop: DropEngine.Drop? = null,
            /** 幸运符是否生效（稀有度已提升 1 级） */
            val luckyUsed: Boolean = false
        ) : State()

        /** 碎裂：reason 展示用；minutes/elapsedMs 用于统计文案 */
        data class Cracked(
            val reason: String,
            val minutes: Int = 0,
            val elapsedMs: Long = 0L
        ) : State()
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val listeners = mutableSetOf<(State) -> Unit>()

    var state: State = State.Idle
        private set

    /** 最近一次屏幕熄灭时间戳，用于区分「锁屏」和「切走」 */
    @Volatile private var screenOffAt = 0L

    /** 暂停券的离开豁免截止时刻：之前离开不判碎裂 */
    @Volatile private var leaveShieldUntil = 0L

    /** 本场是否已用过暂停券 */
    @Volatile private var pauseUsed = false

    /** 护盾最近一次生效时刻（供 UI 弹「护盾救了你」提示） */
    @Volatile var lastShieldSavedAt = 0L
        private set

    fun addListener(listener: (State) -> Unit) {
        listeners.add(listener)
        listener(state)
    }

    fun removeListener(listener: (State) -> Unit) {
        listeners.remove(listener)
    }

    private fun setState(s: State) {
        state = s
        listeners.forEach { it(s) }
    }

    fun noteScreenOff() { screenOffAt = System.currentTimeMillis() }

    /** onStop 时调用：判断这次离开是否是锁屏导致 */
    fun isScreenOffRecent(): Boolean =
        System.currentTimeMillis() - screenOffAt < 3_000

    /** 暂停券是否还在豁免窗口内（FocusActivity 的离开判定用） */
    fun isLeaveShielded(): Boolean =
        System.currentTimeMillis() < leaveShieldUntil

    /** 豁免期结束后的补判时刻（早于 endAt 缓冲，确保人没回来先判碎裂） */
    val LEAVE_SHIELD_RECHECK_AT: Long
        get() = leaveShieldUntil + 300

    /** 本场暂停券是否可用（持有 + 未用过） */
    fun canPause(shop: ShopStore): Boolean =
        !pauseUsed && shop.itemCount(ItemCatalog.ID_PAUSE) > 0

    /** 开始一场专注（minutes 分钟；deep = 深度专注模式） */
    fun start(context: Context, minutes: Int, deep: Boolean = false) {
        val repo = CollectionRepository.get(context)
        val totalMs = minutes * 60_000L
        val now = System.currentTimeMillis()
        pauseUsed = false
        leaveShieldUntil = 0L
        setState(State.Focusing(now, now + totalMs, totalMs, minutes, deep))

        scope.launch {
            // 循环检查 endAt：暂停券把 endAt 后推即可冻结倒计时，无需重建协程
            while (true) {
                val s = state as? State.Focusing ?: return@launch // reset()/碎裂后退出
                val remain = s.endAt - System.currentTimeMillis()
                if (remain <= 0) break
                delay(minOf(remain, 500))
            }
            if (state !is State.Focusing) return@launch

            // 自然结束 → 掉卡（幸运符 / 双倍掉落在此自动生效）
            val shop = ShopStore.get(context)
            var drop = DropEngine.roll(minutes)
            var luckyUsed = false
            if (shop.consumeItem(ItemCatalog.ID_LUCKY)) {
                val up = Rarity.entries[
                    (drop.rarity.ordinal + 1)
                        .coerceAtMost(Rarity.DIAMOND.ordinal)
                ]
                drop = DropEngine.Drop(drop.cardId, up)
                luckyUsed = true
            }
            repo.addCard(drop.cardId, drop.rarity)
            var extra: DropEngine.Drop? = null
            if (shop.consumeItem(ItemCatalog.ID_DOUBLE)) {
                extra = DropEngine.roll(minutes)
                    .also { repo.addCard(it.cardId, it.rarity) }
            }
            repo.addFocusMinutes(minutes)
            repo.addFinishedSession()
            if (deep) {
                repo.addDeepSession()
                // 深度专注奖励：按专注时长再发一份金币（相当于本次金币翻倍）
                shop.addMs(totalMs)
            }
            repo.savePendingDrop(drop) // 防进程被杀丢结果；查看后 take 掉
            setState(State.Success(drop, minutes, extraDrop = extra, luckyUsed = luckyUsed))
        }
    }

    /** 暂停券：冻结倒计时 5 分钟 + 期间离开豁免；每场限用 1 张 */
    fun pause(context: Context): Boolean {
        val s = state as? State.Focusing ?: return false
        val shop = ShopStore.get(context)
        if (!canPause(shop)) return false
        if (!shop.consumeItem(ItemCatalog.ID_PAUSE)) return false

        val now = System.currentTimeMillis()
        pauseUsed = true
        leaveShieldUntil = now + PAUSE_MS
        // endAt 后推 5 分钟 + 1 秒缓冲（晚于豁免到期后的补判，确保豁免期一过人没回来先判碎裂）
        setState(s.copy(endAt = s.endAt + PAUSE_MS + 1_000, pausedUntil = now + PAUSE_MS))
        return true
    }

    /** 统一碎裂入口：计数 + 历史 + 待告知落盘 + 广播状态 */
    private fun doCrack(context: Context, s: State.Focusing, reason: String) {
        val repo = CollectionRepository.get(context)
        val elapsed = System.currentTimeMillis() - s.startedAt
        repo.addCrack(reason, s.minutes, elapsed)
        // 落盘：若进程随后被杀，回来后由主页/专注页兜底告知
        repo.savePendingCrack(
            CollectionRepository.PendingCrack(reason, s.minutes, elapsed)
        )
        setState(State.Cracked(reason, s.minutes, elapsed))
    }

    /** 用户离开 App（非锁屏、非旋转）→ 卡片碎裂；护盾 / 暂停豁免可救场 */
    fun crackByLeaving(context: Context) {
        val s = state as? State.Focusing ?: return
        if (tryShield(context)) return
        if (isLeaveShielded()) return
        doCrack(context, s, "离开了应用，卡片碎裂了…")
    }

    /** 悬浮窗 / 分屏 / 画中画遮挡（Activity 暂停但未停止）→ 同样碎裂；护盾 / 暂停豁免可救场 */
    fun crackByOverlay(context: Context) {
        val s = state as? State.Focusing ?: return
        if (tryShield(context)) return
        if (isLeaveShielded()) return
        doCrack(context, s, "被悬浮窗或分屏遮挡，卡片碎裂了…")
    }

    /** 用户主动放弃 → 同样碎裂（护盾不消耗：对话框已二次确认） */
    fun giveUp(context: Context) {
        val s = state as? State.Focusing ?: return
        doCrack(context, s, "放弃了专注，卡片碎裂了…")
    }

    /** 护盾：持有则自动消耗 1 个并豁免本次碎裂，返回 true */
    private fun tryShield(context: Context): Boolean {
        val shop = ShopStore.get(context)
        if (!shop.consumeItem(ItemCatalog.ID_SHIELD)) return false
        lastShieldSavedAt = System.currentTimeMillis()
        return true
    }

    /** 查看完结果后复位 */
    fun reset() {
        leaveShieldUntil = 0L
        setState(State.Idle)
    }
}
