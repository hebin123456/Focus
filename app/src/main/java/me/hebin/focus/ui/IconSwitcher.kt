package me.hebin.focus.ui

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import me.hebin.focus.data.Rarity

/**
 * 自定义桌面图标：activity-alias 方案。
 *
 * 清单里预置 1 个默认别名 + 101 卡 × 5 稀有度共 506 个别名（卡片别名默认禁用），
 * 由 tools/gen_icon_aliases.py 生成。运行时切换启用状态实现换图标；
 * 先启用新的、再禁用旧的，保证桌面入口不出现真空期。
 *
 * 限制：卡片图标是自适应图标（API 26+），Android 8.0 以下仅支持默认图标。
 */
object IconSwitcher {

    private const val PREFIX = "me.hebin.focus.icon."
    const val DEFAULT_ALIAS = "standard"

    private fun prefs(ctx: Context) =
        ctx.applicationContext.getSharedPreferences("focus_icon", Context.MODE_PRIVATE)

    /** 当前生效别名："standard" 或 "a018_4" */
    fun currentAlias(ctx: Context): String =
        prefs(ctx).getString(KEY_ALIAS, DEFAULT_ALIAS) ?: DEFAULT_ALIAS

    fun isCustom(ctx: Context): Boolean = currentAlias(ctx) != DEFAULT_ALIAS

    /** 卡片图标为自适应图标，仅 API 26+ 支持 */
    fun isCustomSupported(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O

    fun aliasFor(cardId: Int, rarity: Rarity): String = "a%03d_%d".format(cardId, rarity.ordinal)

    fun applyCard(ctx: Context, cardId: Int, rarity: Rarity): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return false
        return switch(ctx, aliasFor(cardId, rarity))
    }

    fun applyDefault(ctx: Context): Boolean = switch(ctx, DEFAULT_ALIAS)

    private fun switch(ctx: Context, newAlias: String): Boolean {
        val appCtx = ctx.applicationContext
        val pm = appCtx.packageManager
        val old = currentAlias(appCtx)
        if (old == newAlias) return true
        return try {
            pm.setComponentEnabledSetting(
                ComponentName(appCtx, PREFIX + newAlias),
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                PackageManager.DONT_KILL_APP
            )
            pm.setComponentEnabledSetting(
                ComponentName(appCtx, PREFIX + old),
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP
            )
            prefs(appCtx).edit().putString(KEY_ALIAS, newAlias).apply()
            true
        } catch (e: Exception) {
            false
        }
    }

    private const val KEY_ALIAS = "alias"
}
