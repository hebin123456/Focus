package me.hebin.focus.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import java.util.Random

/**
 * 白噪音：四种声景全部程序化合成（AudioTrack 流式播放），零音频资源、不增 APK 体积。
 * 锁屏（不判离开）时继续播放，切走 App（卡片碎裂）时随会话结束停止。
 */
enum class AmbientSound(val label: String) {
    OFF("关闭"),
    WHITE("白噪音"),
    RAIN("雨声"),
    WAVES("海浪"),
    FIRE("炉火")
}

/** 白噪音 / 深度专注偏好（与主题设置同用一个 prefs 文件） */
object AmbientPrefs {

    private fun prefs(ctx: Context) =
        ctx.applicationContext.getSharedPreferences("focus_settings", Context.MODE_PRIVATE)

    /** 上次选的声景（从未选过返回 [def]） */
    fun sound(ctx: Context, def: AmbientSound): AmbientSound {
        val i = prefs(ctx).getInt(KEY_SOUND, Int.MIN_VALUE)
        return AmbientSound.entries.getOrElse(i) { def }
    }

    fun setSound(ctx: Context, s: AmbientSound) {
        prefs(ctx).edit().putInt(KEY_SOUND, s.ordinal).apply()
    }

    /** 音量 0..100，默认 60 */
    fun volume(ctx: Context): Int = prefs(ctx).getInt(KEY_VOLUME, 60)

    fun setVolume(ctx: Context, v: Int) {
        prefs(ctx).edit().putInt(KEY_VOLUME, v.coerceIn(0, 100)).apply()
    }

    /** 主页「深度专注模式」开关记忆 */
    fun deepMode(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_DEEP, false)

    fun setDeepMode(ctx: Context, b: Boolean) {
        prefs(ctx).edit().putBoolean(KEY_DEEP, b).apply()
    }

    private const val KEY_SOUND = "ambientSound"
    private const val KEY_VOLUME = "ambientVolume"
    private const val KEY_DEEP = "deepMode"
}

/**
 * 全局唯一的白噪音播放器：一条低优先级线程持续合成 PCM 写入 AudioTrack。
 * 声景切换 = 重建 AudioTrack（简单可靠），音量实时可调。
 */
object AmbientPlayer {

    private const val SAMPLE_RATE = 44100

    private val lock = Any()
    @Volatile private var running = false
    private var track: AudioTrack? = null
    private var thread: Thread? = null

    val isPlaying: Boolean get() = synchronized(lock) { track != null }

    /** 播放指定声景（OFF 等价 stop）；volume 0..1 */
    fun start(sound: AmbientSound, volume: Float) {
        if (sound == AmbientSound.OFF) {
            stop()
            return
        }
        stop()
        synchronized(lock) {
            val minBuf = AudioTrack.getMinBufferSize(
                SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT
            )
            val t = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(SAMPLE_RATE)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setBufferSizeInBytes(maxOf(minBuf, 8192))
                .build()
            t.setVolume(volume.coerceIn(0f, 1f))
            t.play()

            val gen = generatorFor(sound)
            running = true
            track = t
            thread = Thread {
                val buf = ShortArray(2048)
                while (running) {
                    for (i in buf.indices) {
                        // 软限幅，防爆音
                        val v = (gen() * 32767f).toInt().coerceIn(-32768, 32767)
                        buf[i] = v.toShort()
                    }
                    t.write(buf, 0, buf.size)
                }
            }.apply {
                priority = Thread.MIN_PRIORITY
                name = "ambient-noise"
                start()
            }
        }
    }

    fun setVolume(v: Float) {
        synchronized(lock) { track?.setVolume(v.coerceIn(0f, 1f)) }
    }

    fun stop() {
        synchronized(lock) {
            running = false
            val th = thread
            thread = null
            val t = track
            track = null
            runCatching { t?.stop() }
            runCatching { t?.release() }
            // 写线程每 2048 采样（约 46ms）检查一次退出标记，稍等防并发重建
            runCatching { th?.join(300) }
        }
    }

    // ---------------- 程序化声景合成 ----------------

    /** 每次调用产出一个 [-1,1] 采样点的有状态闭包 */
    private fun generatorFor(sound: AmbientSound): () -> Float = when (sound) {
        AmbientSound.WHITE -> whiteGen()
        AmbientSound.RAIN -> rainGen()
        AmbientSound.WAVES -> wavesGen()
        AmbientSound.FIRE -> fireGen()
        AmbientSound.OFF -> { { 0f } }
    }

    private fun rnd(seed: Long) = Random(seed)

    /** 白噪音：粉噪滤波版（听感比纯白噪柔和，长时间听不刺耳） */
    private fun whiteGen(): () -> Float {
        val r = rnd(11)
        var b0 = 0f; var b1 = 0f; var b2 = 0f; var b3 = 0f; var b4 = 0f; var b5 = 0f; var b6 = 0f
        return {
            val w = r.nextFloat() * 2f - 1f
            b0 = 0.99886f * b0 + w * 0.0555179f
            b1 = 0.99332f * b1 + w * 0.0750759f
            b2 = 0.96900f * b2 + w * 0.1538520f
            b3 = 0.86650f * b3 + w * 0.3104856f
            b4 = 0.55000f * b4 + w * 0.5329522f
            b5 = -0.7616f * b5 - w * 0.0168980f
            val pink = b0 + b1 + b2 + b3 + b4 + b5 + b6 + w * 0.5362f
            b6 = w * 0.115926f
            pink * 0.11f
        }
    }

    /** 雨声：低通沙沙雨幕 + 随机雨滴爆裂（指数衰减瞬态） */
    private fun rainGen(): () -> Float {
        val r = rnd(22)
        var lp = 0f
        var dropEnv = 0f
        var nextDrop = 0
        return {
            val w = r.nextFloat() * 2f - 1f
            lp += 0.18f * (w - lp)                 // 一阶低通
            var s = (w - lp) * 0.55f + lp * 0.12f  // 高频雨幕 + 低频铺底
            if (--nextDrop <= 0) {
                nextDrop = (SAMPLE_RATE * (0.015f + r.nextFloat() * 0.09f)).toInt()
                dropEnv = 0.25f + r.nextFloat() * 0.5f
            }
            if (dropEnv > 0.003f) {
                s += w * dropEnv * 0.7f            // 雨滴噼啪感
                dropEnv *= 0.998f                  // 约 80ms 衰减
            }
            s
        }
    }

    /** 海浪：布朗噪声 + 约 9 秒周期的浪涌包络，浪尖叠高频泡沫嘶声 */
    private fun wavesGen(): () -> Float {
        val r = rnd(33)
        var brown = 0f
        var lp = 0f
        var t = 0f
        val period = SAMPLE_RATE * 9f
        return {
            val w = r.nextFloat() * 2f - 1f
            brown = (brown + 0.02f * w) / 1.02f
            t += 1f
            val ph = (t % period) / period
            val swell = 0.5f - 0.5f * kotlin.math.cos(2.0 * Math.PI * ph).toFloat() // 0..1
            val crest = swell * swell
            lp += 0.3f * (w - lp)
            val foam = (w - lp) * 0.35f * crest    // 浪尖泡沫
            brown * 3.2f * (0.3f + 0.7f * crest) * 0.5f + foam
        }
    }

    /** 炉火：低频炭火轰鸣 + 泊松分布的爆裂噼啪（指数衰减瞬态） */
    private fun fireGen(): () -> Float {
        val r = rnd(44)
        var brown = 0f
        var popEnv = 0f
        var nextPop = 0
        return {
            val w = r.nextFloat() * 2f - 1f
            brown = (brown + 0.02f * w) / 1.02f
            var s = brown * 1.35f                  // 炭火底噪
            if (--nextPop <= 0) {
                nextPop = (SAMPLE_RATE * (0.04f + r.nextFloat() * 0.3f)).toInt()
                popEnv = 0.3f + r.nextFloat() * 0.7f
            }
            if (popEnv > 0.004f) {
                s += w * popEnv * 0.55f            // 木柴爆裂
                popEnv *= 0.9985f                  // 约 50ms 衰减
            }
            s
        }
    }
}
