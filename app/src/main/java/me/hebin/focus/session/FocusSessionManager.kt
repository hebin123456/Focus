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
 */
object FocusSessionManager {

    sealed class State {
        data object Idle : State()
        data class Focusing(
            val startedAt: Long,
            val endAt: Long,
            val totalMs: Long,
            val minutes: Int
        ) : State()

        data class Success(
            val drop: DropEngine.Drop,
            val minutes: Int,
            val fromBackground: Boolean = false
        ) : State()

        data class Cracked(val reason: String) : State()
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val listeners = mutableSetOf<(State) -> Unit>()

    var state: State = State.Idle
        private set

    /** 最近一次屏幕熄灭时间戳，用于区分「锁屏」和「切走」 */
    @Volatile private var screenOffAt = 0L

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

    /** 开始一场专注（minutes 分钟） */
    fun start(context: Context, minutes: Int) {
        val repo = CollectionRepository.get(context)
        val totalMs = minutes * 60_000L
        val now = System.currentTimeMillis()
        val focusing = State.Focusing(now, now + totalMs, totalMs, minutes)
        setState(focusing)

        scope.launch {
            delay(totalMs)
            // 自然结束 → 掉卡
            val drop = DropEngine.roll(minutes)
            repo.addCard(drop.cardId, drop.rarity)
            repo.addFocusMinutes(minutes)
            repo.addFinishedSession()
            repo.savePendingDrop(drop) // 防进程被杀丢结果；查看后 take 掉
            setState(State.Success(drop, minutes))
        }
    }

    /** 用户离开 App（非锁屏、非旋转）→ 卡片碎裂 */
    fun crackByLeaving(context: Context) {
        if (state !is State.Focusing) return
        CollectionRepository.get(context).addCrack()
        setState(State.Cracked("离开了应用，卡片碎裂了…"))
    }

    /** 用户主动放弃 → 同样碎裂 */
    fun giveUp(context: Context) {
        if (state !is State.Focusing) return
        CollectionRepository.get(context).addCrack()
        setState(State.Cracked("放弃了专注，卡片碎裂了…"))
    }

    /** 查看完结果后复位 */
    fun reset() {
        setState(State.Idle)
    }
}
