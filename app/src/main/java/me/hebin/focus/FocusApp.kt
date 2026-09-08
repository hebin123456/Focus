package me.hebin.focus

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import me.hebin.focus.data.ShopStore
import me.hebin.focus.ui.ThemeStore

/**
 * 应用入口：跟踪前台时长，为金币系统线性累积（App 前台每满 1 分钟 +1 金币）。
 *
 * 入账时机：最后一个 Activity 退出前台时 + 前台期间每 30 秒一次（防进程被杀丢进度）。
 * 熄屏时 Activity 会走 onStop，前台计时随之暂停，符合「前台开着才攒」的语义。
 */
class FocusApp : Application() {

    private val handler = Handler(Looper.getMainLooper())
    private var fgStartElapsed = -1L
    private var startedActivities = 0

    /** 前台期间周期性入账 */
    private val bankRunnable = object : Runnable {
        override fun run() {
            if (fgStartElapsed >= 0) {
                bankForegroundMs()
                handler.postDelayed(this, BANK_INTERVAL_MS)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        ThemeStore.applySaved(this)
        registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) {
                if (startedActivities++ == 0) {
                    fgStartElapsed = SystemClock.elapsedRealtime()
                    handler.postDelayed(bankRunnable, BANK_INTERVAL_MS)
                }
            }

            override fun onActivityStopped(activity: Activity) {
                if (--startedActivities <= 0) {
                    startedActivities = 0
                    handler.removeCallbacks(bankRunnable)
                    bankForegroundMs()
                    fgStartElapsed = -1L
                }
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
    }

    /** 当前前台段已累积的毫秒（后台时为 0） */
    fun liveForegroundMs(): Long =
        if (fgStartElapsed < 0) 0L else SystemClock.elapsedRealtime() - fgStartElapsed

    /** 把当前前台段入账，并从当下重新起算 */
    fun bankForegroundMs() {
        if (fgStartElapsed < 0) return
        val ms = SystemClock.elapsedRealtime() - fgStartElapsed
        if (ms > 0) ShopStore.get(this).addMs(ms)
        fgStartElapsed = SystemClock.elapsedRealtime()
    }

    companion object {
        private const val BANK_INTERVAL_MS = 30_000L

        @Volatile
        var instance: FocusApp? = null
            private set
    }
}
