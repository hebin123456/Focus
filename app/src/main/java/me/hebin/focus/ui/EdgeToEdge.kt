package me.hebin.focus.ui

import android.app.Activity
import android.graphics.Color
import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat

/**
 * 全屏沉浸（Edge-to-Edge）统一入口。
 *
 * - 系统栏透明，内容绘制到状态栏 / 导航栏 / 刘海区域底下
 * - 刘海按 SHORT_EDGES 处理：竖屏时内容在刘海两侧正常排布，不被系统黑边裁掉
 * - 目标视图用 padding 吃掉系统栏 insets，避免控件被刘海 / 手势条遮挡
 *
 * 所有 pad* 都是「增量」的：XML 里已有的 padding 会被保留，insets 叠加在其上，
 * 所以可以放心挂在任何视图上。
 *
 * 用法：
 *   EdgeToEdge.enable(this)              // onCreate 里调一次（窗口层面）
 *   EdgeToEdge.pad(binding.root)         // 根布局整体留白（背景仍铺满全屏）
 *   EdgeToEdge.padTopOnly(binding.header)// 只需要避开顶部时
 */
object EdgeToEdge {

    /** 窗口层面：透明系统栏 + 刘海 shortEdges。在 setContentView 前后调用均可。 */
    fun enable(activity: Activity) {
        val window = activity.window
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        // 刘海处理：SHORT_EDGES = 内容延伸进刘海两侧（默认 DEFAULT 只在横屏全屏时延伸）
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val lp = window.attributes
            lp.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            window.attributes = lp
        }
    }

    /**
     * 目标视图按「系统栏 + 刘海」insets 留白（上下左右）。
     * 首次分发时记住 XML padding，之后在原值上叠加 insets。
     */
    fun pad(view: View) {
        incrementalInsets(view) { v, l, t, r, b, bars ->
            v.setPadding(l + bars.left, t + bars.top, r + bars.right, b + bars.bottom)
        }
    }

    /** 只避开顶部（状态栏 / 刘海），左右底不动 */
    fun padTopOnly(view: View) {
        incrementalInsets(view) { v, l, t, r, b, bars ->
            v.setPadding(l + bars.left, t + bars.top, r, b)
        }
    }

    /** 只避开底部（手势条 / 导航栏），左右顶不动 */
    fun padBottomOnly(view: View) {
        incrementalInsets(view) { v, l, t, r, b, bars ->
            v.setPadding(l, t, r, b + bars.bottom)
        }
    }

    /** 统一实现：首次回调时快照原始 padding，之后每次在原始值上叠加 insets */
    private inline fun incrementalInsets(
        view: View,
        crossinline apply: (v: View, l: Int, t: Int, r: Int, b: Int, bars: androidx.core.graphics.Insets) -> Unit
    ) {
        var baseL = -1
        var baseT = 0
        var baseR = 0
        var baseB = 0
        ViewCompat.setOnApplyWindowInsetsListener(view) { v, insets ->
            if (baseL == -1) {
                baseL = v.paddingLeft
                baseT = v.paddingTop
                baseR = v.paddingRight
                baseB = v.paddingBottom
            }
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            apply(v, baseL, baseT, baseR, baseB, bars)
            insets
        }
        // 视图已attach时主动请求一次insets分发（否则部分场景监听不触发）
        if (view.isAttachedToWindow) ViewCompat.requestApplyInsets(view)
    }

    /** 兼容旧调用：让某个 ViewGroup 的首个子 View 避开顶部（无 ID 的标题栏场景） */
    fun padFirstChildTop(root: View) {
        (root as? ViewGroup)?.getChildAt(0)?.let { padTopOnly(it) }
    }
}
