package me.hebin.focus.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.tanh

/**
 * XMSLEEP 式多声混音白噪音播放器：
 *
 * - **多声叠加**：N 个声景同时播放（如「雨声 + 炉火 + 猫呼噜」自由组合），
 *   各自独立音量、独立淡入淡出（~30ms，开关无咔哒声）；
 * - **混合总线** tanh 软限幅防爆音；**主音量**走 AudioTrack 层实时可调；
 * - 全部程序化合成（见 [AmbientSynth]）：零音频资源、天然无缝循环、不增 APK 体积；
 * - 锁屏（不判离开）时继续播放，切走 App（卡片碎裂）时随会话结束停止。
 *
 * 用法：
 * ```
 * AmbientPlayer.start(AmbientSound.RAIN, 0.7f)   // 开一条声部（幂等，重复开只重设音量）
 * AmbientPlayer.setSoundVolume(AmbientSound.RAIN, 0.5f)
 * AmbientPlayer.stop(AmbientSound.RAIN)          // 淡出并移除该声部
 * AmbientPlayer.setMasterVolume(0.6f)            // 总音量
 * AmbientPlayer.stop()                          // 会话结束：全部立即停止
 * ```
 */
object AmbientPlayer {

    private const val SAMPLE_RATE = 44100

    /** 淡入淡出时间常数：每个采样向目标增益靠近 e^-1，约 20ms 达到平滑过渡 */
    private const val FADE_K = 0.0011f

    /** 一条活跃声部：生成器 + 增益（目标 / 当前）+ 生命周期标记 */
    private class Voice(val gen: () -> Float) {
        @Volatile var target = 0f   // 目标增益 0..1（UI 线程写）
        var cur = 0f                // 当前增益（仅音频线程写）
        @Volatile var dying = false // 待淡出移除
    }

    private val lock = Any()
    @Volatile private var running = false
    private var track: AudioTrack? = null
    private var thread: Thread? = null

    /** 活跃声部表：音频线程迭代时顺带移除已淡出的声部 */
    private val voices = ConcurrentHashMap<AmbientSound, Voice>()

    val isPlaying: Boolean get() = synchronized(lock) { track != null }

    /** 开启/更新一条声部（volume 为该声部混音增益 0..1；重复开同一声部只更新音量） */
    fun start(sound: AmbientSound, volume: Float) {
        if (sound == AmbientSound.OFF) return
        val v = volume.coerceIn(0f, 1f)
        synchronized(lock) {
            ensureEngine()
            val existing = voices[sound]
            if (existing != null) {
                existing.target = v
                existing.dying = false
            } else {
                voices[sound] = Voice(AmbientSynth.generatorFor(sound)).apply { target = v }
            }
        }
    }

    /** 淡出并移除单条声部 */
    fun stop(sound: AmbientSound) {
        voices[sound]?.dying = true
    }

    /** 单声部音量（0..1，实时生效） */
    fun setSoundVolume(sound: AmbientSound, volume: Float) {
        voices[sound]?.let { if (!it.dying) it.target = volume.coerceIn(0f, 1f) }
    }

    /** 主音量（0..1，AudioTrack 层，作用于所有声部） */
    fun setMasterVolume(v: Float) {
        synchronized(lock) { track?.setVolume(v.coerceIn(0f, 1f)) }
    }

    /** 全部停止（会话结束 / 卡片碎裂 / 页面销毁） */
    fun stop() {
        synchronized(lock) {
            running = false
            voices.clear()
        }
        thread?.join(300)
        synchronized(lock) {
            if (thread?.isAlive != true) {
                thread = null
                track = null
            }
        }
    }

    // ---------------- 引擎生命周期 ----------------

    /** 懒创建 AudioTrack + 合成线程（引擎常驻到 [stop]，无声部时静音待命，切换零重建开销） */
    private fun ensureEngine() {
        if (thread?.isAlive == true && track != null) return
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
        t.play()
        running = true
        track = t
        thread = Thread {
            synthLoop(t)
            // 自收尾：释放本线程持有的 track（stop() 已抢先清理时引用为 null，安全跳过）
            synchronized(lock) {
                if (track === t) {
                    track = null
                    thread = null
                }
                running = false
            }
            runCatching { t.stop() }
            runCatching { t.release() }
        }.apply {
            priority = Thread.MIN_PRIORITY
            name = "ambient-mixer"
            start()
        }
    }

    /** 合成主循环：混音所有活跃声部 → tanh 软限幅 → 写入 AudioTrack */
    private fun synthLoop(t: AudioTrack) {
        val buf = ShortArray(2048)
        while (running) {
            if (voices.isEmpty()) {                 // 无声部：静音待命（省去生成器计算）
                java.util.Arrays.fill(buf, 0)
                t.write(buf, 0, buf.size)
                continue
            }
            for (i in buf.indices) {
                var mix = 0f
                val it = voices.entries.iterator()
                while (it.hasNext()) {
                    val v = it.next().value
                    v.cur += (v.target - v.cur) * FADE_K
                    if (v.dying && v.cur < 0.0015f) {  // 淡出完毕：移除声部
                        it.remove()
                        continue
                    }
                    mix += v.gen() * v.cur
                }
                buf[i] = (tanh(mix) * 32767f).toInt().toShort()
            }
            t.write(buf, 0, buf.size)
        }
    }
}
