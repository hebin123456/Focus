package me.hebin.focus.ui

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate

/**
 * 外观设置：跟随系统 / 浅色 / 深色。
 * 通过 AppCompatDelegate.setDefaultNightMode 全局生效，颜色资源在 values / values-night 间切换。
 */
object ThemeStore {
    const val FOLLOW_SYSTEM = 0
    const val LIGHT = 1
    const val DARK = 2

    val labels = arrayOf("跟随系统", "浅色", "深色")

    private fun prefs(ctx: Context) =
        ctx.applicationContext.getSharedPreferences("focus_settings", Context.MODE_PRIVATE)

    fun mode(ctx: Context): Int = prefs(ctx).getInt(KEY, FOLLOW_SYSTEM)

    fun setMode(ctx: Context, m: Int) {
        prefs(ctx).edit().putInt(KEY, m).apply()
        AppCompatDelegate.setDefaultNightMode(nightModeOf(m))
    }

    /** 启动时恢复上次设置 */
    fun applySaved(ctx: Context) {
        AppCompatDelegate.setDefaultNightMode(nightModeOf(mode(ctx)))
    }

    private fun nightModeOf(m: Int) = when (m) {
        LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
        DARK -> AppCompatDelegate.MODE_NIGHT_YES
        else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
    }

    private const val KEY = "themeMode"
}
