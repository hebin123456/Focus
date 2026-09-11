package me.hebin.focus.audio

import android.content.Context

/**
 * 白噪音偏好（与主题设置同用一个 prefs 文件 `focus_settings`）：
 *
 * - **多选声景集合**（XMSLEEP 式混音组合，如「雨声 + 炉火」），跨会话记忆；
 * - **每个声音独立音量** + **主音量**；
 * - 深度专注模式开关记忆；
 * - 旧版单选存储（ordinal int）自动迁移为多选集合。
 */
object AmbientPrefs {

    private const val PREFS = "focus_settings"
    private const val KEY_ACTIVE = "ambientSounds"       // StringSet<AmbientSound.name>
    private const val KEY_VOLUME = "ambientVolume"       // 主音量 0..100（沿用旧键）
    private const val KEY_DEEP = "deepMode"              // 深度专注（沿用旧键）
    private const val KEY_LEGACY_SOUND = "ambientSound"  // 旧版单选 ordinal
    private const val KEY_CONFIGURED = "ambientConfigured" // 用户是否表达过任何选择

    private fun prefs(ctx: Context) =
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // ---------------- 多选声景 ----------------

    /** 当前选中的声景集合（含旧版单选自动迁移） */
    fun activeSounds(ctx: Context): Set<AmbientSound> {
        migrateLegacyIfNeeded(ctx)
        val names = prefs(ctx).getStringSet(KEY_ACTIVE, null) ?: return emptySet()
        return names.mapNotNull { n -> AmbientSound.entries.firstOrNull { it.name == n } }.toSet()
    }

    fun setActiveSounds(ctx: Context, sounds: Set<AmbientSound>) {
        prefs(ctx).edit()
            .putStringSet(KEY_ACTIVE, sounds.map { it.name }.toSet()) // 必须 new Set，防引用共享
            .putBoolean(KEY_CONFIGURED, true)
            .apply()
    }

    fun addActive(ctx: Context, s: AmbientSound) {
        if (s == AmbientSound.OFF) return
        setActiveSounds(ctx, activeSounds(ctx) + s)
    }

    fun removeActive(ctx: Context, s: AmbientSound) {
        setActiveSounds(ctx, activeSounds(ctx) - s)
    }

    /**
     * 用户是否表达过任何选择（旧版选过 / 新版点过任意开关）。
     * 决定「深度专注默认播放雨声」要不要生效：用户明确全关过则尊重，不再强开。
     */
    fun configured(ctx: Context): Boolean {
        migrateLegacyIfNeeded(ctx)
        return prefs(ctx).getBoolean(KEY_CONFIGURED, false)
    }

    // ---------------- 音量 ----------------

    /** 主音量 0..100，默认 60 */
    fun masterVolume(ctx: Context): Int = prefs(ctx).getInt(KEY_VOLUME, 60)

    fun setMasterVolume(ctx: Context, v: Int) {
        prefs(ctx).edit().putInt(KEY_VOLUME, v.coerceIn(0, 100)).apply()
    }

    /** 单个声音的混音音量 0..100，默认 70 */
    fun soundVolume(ctx: Context, s: AmbientSound): Int =
        prefs(ctx).getInt("ambientVol.${s.name}", 70)

    fun setSoundVolume(ctx: Context, s: AmbientSound, v: Int) {
        prefs(ctx).edit().putInt("ambientVol.${s.name}", v.coerceIn(0, 100)).apply()
    }

    // ---------------- 深度专注 ----------------

    fun deepMode(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_DEEP, false)

    fun setDeepMode(ctx: Context, b: Boolean) {
        prefs(ctx).edit().putBoolean(KEY_DEEP, b).apply()
    }

    // ---------------- 旧版迁移 ----------------

    /**
     * 旧版单选 ordinal → 新版多选集合。
     * 旧 enum 前 5 位（OFF/WHITE/RAIN/WAVES/FIRE）与现声明严格对齐；
     * 迁移完成后清除旧键。已迁移过（存在多选键）只补清旧键。
     */
    private fun migrateLegacyIfNeeded(ctx: Context) {
        val p = prefs(ctx)
        if (!p.contains(KEY_LEGACY_SOUND)) return
        if (!p.contains(KEY_ACTIVE)) {
            val ordinal = p.getInt(KEY_LEGACY_SOUND, Int.MIN_VALUE)
            val migrated = AmbientSound.entries.getOrNull(ordinal)
                ?.takeIf { it != AmbientSound.OFF }
                ?.let { setOf(it) } ?: emptySet()
            p.edit()
                .putStringSet(KEY_ACTIVE, migrated.map { it.name }.toSet())
                .putBoolean(KEY_CONFIGURED, true)
                .apply()
        }
        p.edit().remove(KEY_LEGACY_SOUND).apply()
    }
}
