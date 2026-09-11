package me.hebin.focus.audio

import java.util.Random
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.tanh

/**
 * 程序化声景合成器（44.1kHz 单声道）。
 *
 * 每个工厂返回一个**有状态闭包**，每次调用产出一个 [-1,1] 采样点；
 * 全部为实时 DSP，无采样资源 —— 天然无缝循环、不占 APK 体积、CPU 占用极低
 * （单声景约 20~40 flops/采样）。
 *
 * 常用配方速查：
 *  - 白噪：均匀随机；粉噪：Paul Kellet 精化滤波（1/f）；棕噪：泄漏积分白噪（1/f²）
 *  - 一阶低通：`lp += k * (x - lp)`，截止频率 ≈ k·SR/2π
 *  - 瞬态事件（雨滴 / 爆裂 / 杯碟碰撞）：泊松间隔触发 + 指数衰减包络
 *  - 蟋蟀：~4.2kHz 正弦载波 × 27Hz 脉冲串 × 短语包络；鸟鸣：FM 扫频正弦短语
 */
internal object AmbientSynth {

    private const val SR = 44100
    private const val TWO_PI = (2.0 * PI).toFloat()
    private const val PI_F = PI.toFloat()

    /** 声景 → 生成器工厂 */
    fun generatorFor(s: AmbientSound): () -> Float = when (s) {
        AmbientSound.WHITE -> white()
        AmbientSound.PINK -> pink()
        AmbientSound.BROWN -> brown()
        AmbientSound.RAIN -> rain()
        AmbientSound.THUNDER -> thunder()
        AmbientSound.WIND -> wind()
        AmbientSound.WAVES -> waves()
        AmbientSound.STREAM -> stream()
        AmbientSound.FOREST -> forest()
        AmbientSound.FIRE -> fire()
        AmbientSound.NIGHT -> night()
        AmbientSound.BIRDS -> birds()
        AmbientSound.CAT -> cat()
        AmbientSound.CAFE -> cafe()
        AmbientSound.TRAIN -> train()
        AmbientSound.CLOCK -> clock()
        AmbientSound.FAN -> fan()
        AmbientSound.KEYBOARD -> keyboard()
        AmbientSound.OFF -> { { 0f } }
    }

    private fun rnd(seed: Long) = Random(seed)

    // ---------------- 噪声色 ----------------

    /** 白噪：均匀随机，全频段等能量（略微压低，长时间听不刺耳） */
    private fun white(): () -> Float {
        val r = rnd(11)
        return { (r.nextFloat() * 2f - 1f) * 0.28f }
    }

    /** 粉噪：Paul Kellet 精化 IIR 滤波（1/f 频谱，-3dB/倍频程） */
    private fun pink(): () -> Float {
        val r = rnd(12)
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

    /** 棕噪：泄漏积分白噪（1/f² 频谱，-6dB/倍频程），低沉的轰鸣感 */
    private fun brown(): () -> Float {
        val r = rnd(13)
        var b = 0f
        return {
            val w = r.nextFloat() * 2f - 1f
            b = (b + 0.02f * w) / 1.02f   // 积分 + 泄漏防漂移
            b * 3.5f
        }
    }

    // ---------------- 天气 ----------------

    /** 雨声：低通雨幕 + 泊松雨滴爆裂（指数衰减瞬态） */
    private fun rain(): () -> Float {
        val r = rnd(22)
        var lp = 0f
        var dropEnv = 0f
        var nextDrop = 0
        return {
            val w = r.nextFloat() * 2f - 1f
            lp += 0.18f * (w - lp)                  // 一阶低通雨幕
            var s = (w - lp) * 0.55f + lp * 0.12f    // 高频雨丝 + 低频铺底
            if (--nextDrop <= 0) {
                nextDrop = (SR * (0.015f + r.nextFloat() * 0.09f)).toInt()
                dropEnv = 0.25f + r.nextFloat() * 0.5f
            }
            if (dropEnv > 0.003f) {
                s += w * dropEnv * 0.7f             // 雨滴噼啪感
                dropEnv *= 0.998f                   // ~80ms 衰减
            }
            s
        }
    }

    /** 雷雨：轻雨幕衬底 + 8~22 秒一次的远处闷雷（快起慢落低频轰鸣） */
    private fun thunder(): () -> Float {
        val r = rnd(55)
        var lp = 0f
        var dropEnv = 0f
        var nextDrop = 0
        var rumbleLp = 0f
        var rumbleEnv = 0f
        var rumbleRising = false
        var nextRumble = (SR * 3f).toInt()
        return {
            val w = r.nextFloat() * 2f - 1f
            // 轻雨幕
            lp += 0.16f * (w - lp)
            var s = (w - lp) * 0.3f + lp * 0.08f
            if (--nextDrop <= 0) {
                nextDrop = (SR * (0.03f + r.nextFloat() * 0.12f)).toInt()
                dropEnv = 0.2f + r.nextFloat() * 0.35f
            }
            if (dropEnv > 0.004f) {
                s += w * dropEnv * 0.4f
                dropEnv *= 0.997f
            }
            // 远雷：深低通棕噪 × 不对称包络
            if (--nextRumble <= 0) {
                nextRumble = (SR * (8f + r.nextFloat() * 14f)).toInt()
                rumbleEnv = 0.02f
                rumbleRising = true
            }
            if (rumbleEnv > 0.001f) {
                rumbleLp += 0.045f * (w - rumbleLp)          // ~315Hz 深低通
                rumbleEnv = if (rumbleRising) {
                    val e = rumbleEnv * 1.00018f             // ~0.5s 起音
                    if (e >= 1f) { rumbleRising = false; 1f } else e
                } else {
                    rumbleEnv * 0.9999f                       // τ≈1s，全程 ~3.5s
                }
                s += rumbleLp * rumbleEnv * 6.5f
            }
            s
        }
    }

    /** 风：截止频率与响度都随风速游走的低通噪声，阵风忽强忽弱 */
    private fun wind(): () -> Float {
        val r = rnd(66)
        var lp = 0f
        var gust = 0.3f
        var gustTarget = 0.5f
        var gustT = 0f
        return {
            val w = r.nextFloat() * 2f - 1f
            if (++gustT > SR * 0.8f) {                       // 每 ~0.8s 换一个阵风目标
                gustT = 0f
                gustTarget = 0.15f + r.nextFloat() * 0.85f
            }
            gust += (gustTarget - gust) * 0.00004f            // τ≈0.57s 平滑游走
            val k = 0.06f + 0.22f * gust                      // 风大 → 频带宽 → 更亮
            lp += k * (w - lp)
            lp * (0.25f + 0.75f * gust) * 2.2f
        }
    }

    // ---------------- 自然 ----------------

    /** 海浪：布朗噪声 + 约 9 秒周期的浪涌包络，浪尖叠高频泡沫嘶声 */
    private fun waves(): () -> Float {
        val r = rnd(33)
        var brown = 0f
        var lp = 0f
        var t = 0f
        val period = SR * 9f
        return {
            val w = r.nextFloat() * 2f - 1f
            brown = (brown + 0.02f * w) / 1.02f
            t += 1f
            val ph = (t % period) / period
            val swell = 0.5f - 0.5f * cos(2.0 * PI * ph).toFloat()  // 0..1
            val crest = swell * swell
            lp += 0.3f * (w - lp)
            val foam = (w - lp) * 0.35f * crest                // 浪尖泡沫
            brown * 3.2f * (0.3f + 0.7f * crest) * 0.5f + foam
        }
    }

    /** 溪流：中频水流 + 低频铺底 + 随机气泡（短促衰减正弦的叮咚感） */
    private fun stream(): () -> Float {
        val r = rnd(77)
        var lp = 0f
        var lp2 = 0f
        var bubEnv = 0f
        var bubFreq = 0f
        var bubPhase = 0f
        var nextBub = 0
        return {
            val w = r.nextFloat() * 2f - 1f
            lp += 0.35f * (w - lp)                // 中频湍流
            lp2 += 0.04f * (w - lp2)              // 低频铺底
            var s = (w - lp) * 0.32f + lp * 0.3f + lp2 * 0.8f
            if (--nextBub <= 0) {
                nextBub = (SR * (0.02f + r.nextFloat() * 0.14f)).toInt()
                bubEnv = 0.25f + r.nextFloat() * 0.5f
                bubFreq = 350f + r.nextFloat() * 850f
            }
            if (bubEnv > 0.004f) {
                bubPhase += TWO_PI * bubFreq / SR
                if (bubPhase > TWO_PI) bubPhase -= TWO_PI
                s += sin(bubPhase) * bubEnv * 0.35f
                bubEnv *= 0.9985f                // ~45ms 衰减
            }
            s
        }
    }

    /** 森林：软风床 + 高频树叶沙沙（沙沙亮度与密度随风起落） */
    private fun forest(): () -> Float {
        val r = rnd(88)
        var lp = 0f
        var lpFast = 0f
        var gust = 0.35f
        var gustTarget = 0.5f
        var gustT = 0f
        return {
            val w = r.nextFloat() * 2f - 1f
            if (++gustT > SR * 0.9f) {
                gustT = 0f
                gustTarget = 0.2f + r.nextFloat() * 0.8f
            }
            gust += (gustTarget - gust) * 0.00003f
            lp += 0.05f * (w - lp)                       // 风床
            val hpOut = w - lpFast                        // 一阶高通：保留叶子沙沙的高频
            lpFast += 0.5f * (w - lpFast)
            val rustle = hpOut * gust * 0.5f
            val bed = lp * (0.4f + 0.6f * gust) * 1.2f
            rustle + bed
        }
    }

    /** 炉火：低频炭火轰鸣 + 泊松分布的木柴爆裂（指数衰减瞬态） */
    private fun fire(): () -> Float {
        val r = rnd(44)
        var brown = 0f
        var popEnv = 0f
        var nextPop = 0
        return {
            val w = r.nextFloat() * 2f - 1f
            brown = (brown + 0.02f * w) / 1.02f
            var s = brown * 1.35f                         // 炭火底噪
            if (--nextPop <= 0) {
                nextPop = (SR * (0.04f + r.nextFloat() * 0.3f)).toInt()
                popEnv = 0.3f + r.nextFloat() * 0.7f
            }
            if (popEnv > 0.004f) {
                s += w * popEnv * 0.55f                   // 木柴爆裂
                popEnv *= 0.9985f                         // ~50ms 衰减
            }
            s
        }
    }

    // ---------------- 动物 ----------------

    /** 夜虫：单只蟋蟀，~4.2kHz 载波（带慢速颤音）× 27Hz 脉冲串 × 短语包络，间歇静默 */
    private fun night(): () -> Float {
        val r = rnd(99)
        var phase = 0f
        var wobble = 0f
        var pulsePh = 0f
        var chirpLeft = 0
        var chirpDur = 0
        var gapLeft = (SR * 0.8f).toInt()
        return {
            var s = 0f
            if (chirpLeft > 0) {
                chirpLeft--
                wobble += TWO_PI * 4.3f / SR
                if (wobble > TWO_PI) wobble -= TWO_PI
                phase += TWO_PI * (4200f + 150f * sin(wobble)) / SR
                if (phase > TWO_PI) phase -= TWO_PI
                pulsePh += TWO_PI * 27f / SR
                if (pulsePh > TWO_PI) pulsePh -= TWO_PI
                val t01 = 1f - chirpLeft.toFloat() / chirpDur
                val env = sin(PI_F * t01) * (0.55f + 0.45f * sin(pulsePh))
                s = sin(phase) * env * 0.22f
                if (chirpLeft == 0) gapLeft = (SR * (0.6f + r.nextFloat() * 2.4f)).toInt()
            } else if (--gapLeft <= 0) {
                chirpDur = (SR * (0.18f + r.nextFloat() * 0.35f)).toInt()
                chirpLeft = chirpDur
            }
            s
        }
    }

    /** 鸟鸣：FM 扫频正弦音符组成的短语（2~5 音符），句间随机停顿 */
    private fun birds(): () -> Float {
        val r = rnd(111)
        var ph = 0f
        var f = 0f
        var fFrom = 0f
        var fTo = 0f
        var noteLeft = 0
        var noteDur = 0
        var noteGap = (SR * 1.2f).toInt()
        var phraseNotesLeft = 0
        return {
            var s = 0f
            if (noteLeft > 0) {
                noteLeft--
                val t01 = 1f - noteLeft.toFloat() / noteDur
                f = fFrom + (fTo - fFrom) * t01                 // 音符内扫频（婉转感）
                ph += TWO_PI * f / SR
                if (ph > TWO_PI) ph -= TWO_PI
                val env = sin(PI_F * t01).pow(1.5f)
                s = sin(ph) * env * 0.3f
                if (noteLeft == 0) {
                    if (--phraseNotesLeft > 0) {
                        fFrom = 2200f + r.nextFloat() * 1600f
                        fTo = fFrom * (0.7f + r.nextFloat() * 0.6f)
                        noteDur = (SR * (0.07f + r.nextFloat() * 0.12f)).toInt()
                        noteLeft = noteDur
                    } else {
                        noteGap = (SR * (0.6f + r.nextFloat() * 1.8f)).toInt()
                    }
                }
            } else if (--noteGap <= 0) {
                fFrom = 2200f + r.nextFloat() * 1600f
                fTo = fFrom * (0.7f + r.nextFloat() * 0.6f)
                noteDur = (SR * (0.07f + r.nextFloat() * 0.12f)).toInt()
                noteLeft = noteDur
                phraseNotesLeft = 2 + r.nextInt(4)
            }
            s
        }
    }

    /** 猫呼噜：中低频噪声 × 25Hz 呼噜颤动 × 0.32Hz 呼吸包络 */
    private fun cat(): () -> Float {
        val r = rnd(122)
        var lp = 0f
        var lp2 = 0f
        var ph24 = 0f
        var phBr = 0f
        return {
            val w = r.nextFloat() * 2f - 1f
            lp += 0.08f * (w - lp)
            lp2 += 0.015f * (w - lp2)
            ph24 += TWO_PI * 25f / SR
            if (ph24 > TWO_PI) ph24 -= TWO_PI
            phBr += TWO_PI * 0.32f / SR
            if (phBr > TWO_PI) phBr -= TWO_PI
            val breath = 0.55f + 0.45f * sin(phBr)
            val purr = lp * (0.55f + 0.45f * sin(ph24)) * 1.4f
            (purr + lp2 * 0.5f) * breath
        }
    }

    // ---------------- 场所 ----------------

    /** 咖啡馆：带通人声嘈杂（慢波动）+ 泊松杯碟碰撞 + 空调底噪 */
    private fun cafe(): () -> Float {
        val r = rnd(133)
        var b = 0f
        var lp1 = 0f
        var lp2 = 0f
        var murEnv = 0.5f
        var murTarget = 0.5f
        var murT = 0f
        var clinkEnv = 0f
        var clinkPh = 0f
        var clinkF = 0f
        var nextClink = 0
        return {
            val w = r.nextFloat() * 2f - 1f
            // 人声嘈杂：棕噪 → 带通（~300-800Hz）→ 慢波动（似有似无的话语感）
            b = (b + 0.03f * w) / 1.03f
            lp1 += 0.2f * (b - lp1)
            lp2 += 0.06f * (lp1 - lp2)
            if (++murT > SR * 0.5f) {
                murT = 0f
                murTarget = 0.35f + r.nextFloat() * 0.65f
            }
            murEnv += (murTarget - murEnv) * 0.00008f
            var s = (lp1 - lp2) * murEnv * 9f
            // 杯碟碰撞：1.8~3.2kHz 短促衰减正弦
            if (--nextClink <= 0) {
                nextClink = (SR * (3f + r.nextFloat() * 9f)).toInt()
                clinkEnv = 0.25f + r.nextFloat() * 0.4f
                clinkF = 1800f + r.nextFloat() * 1400f
                clinkPh = 0f
            }
            if (clinkEnv > 0.004f) {
                clinkPh += TWO_PI * clinkF / SR
                s += sin(clinkPh) * clinkEnv * 0.3f
                clinkEnv *= 0.9993f               // ~70ms 衰减
            }
            s + b * 0.15f                          // 空调底噪
        }
    }

    /** 火车：行车轰鸣 + 轨缝「哐当」（转向架双击，周期随车速微变） */
    private fun train(): () -> Float {
        val r = rnd(144)
        var b = 0f
        var lp = 0f
        var beatPeriod = SR * 1.5f
        var clackLeft = 0
        var clackIdx = 0
        var clackEnv = 0f
        var nextClack = 0
        return {
            val w = r.nextFloat() * 2f - 1f
            b = (b + 0.02f * w) / 1.02f
            lp += 0.25f * (w - lp)
            var s = b * 2.2f                        // 行车轰鸣
            if (--nextClack <= 0) {
                if (clackIdx == 0) beatPeriod = SR * (1.3f + r.nextFloat() * 0.35f)
                clackIdx = (clackIdx + 1) % 2
                nextClack = if (clackIdx == 1) (SR * 0.14f).toInt() else (beatPeriod - SR * 0.14f).toInt()
                clackEnv = 0.5f + r.nextFloat() * 0.3f
            }
            if (clackEnv > 0.004f) {
                s += lp * clackEnv * 1.1f           // 轨缝冲击
                clackEnv *= 0.9982f                 // ~35ms 衰减
            }
            s
        }
    }

    // ---------------- 物品 ----------------

    /** 时钟：每半秒一响的滴答交替（tick 频率略高于 tock，短促噪声爆裂） */
    private fun clock(): () -> Float {
        val r = rnd(155)
        var lp = 0f
        var t = 0
        var tickEnv = 0f
        var tickHi = false
        return {
            val w = r.nextFloat() * 2f - 1f
            var s = 0f
            t++
            if (t >= SR / 2) {
                t = 0
                tickHi = !tickHi
                tickEnv = 0.4f + r.nextFloat() * 0.15f
            }
            if (tickEnv > 0.002f) {
                lp += (if (tickHi) 0.6f else 0.45f) * (w - lp)
                s = (w - lp) * tickEnv * 0.9f
                tickEnv *= 0.994f                   // ~12ms 衰减
            }
            s
        }
    }

    /** 风扇：宽频风体 × 21Hz 叶片扫掠调制 × 极慢摆动 + 低频嗡床 */
    private fun fan(): () -> Float {
        val r = rnd(166)
        var lp = 0f
        var lp2 = 0f
        var b = 0f
        var phBlade = 0f
        var phWob = 0f
        return {
            val w = r.nextFloat() * 2f - 1f
            lp += 0.18f * (w - lp)
            lp2 += 0.03f * (w - lp2)
            b = (b + 0.02f * w) / 1.02f
            phBlade += TWO_PI * 21f / SR
            if (phBlade > TWO_PI) phBlade -= TWO_PI
            phWob += TWO_PI * 0.13f / SR
            if (phWob > TWO_PI) phWob -= TWO_PI
            val blade = 0.9f + 0.1f * sin(phBlade)
            val wob = 0.94f + 0.06f * sin(phWob)
            lp * blade * wob * 1.1f + lp2 * 0.8f + b * 0.4f
        }
    }

    /** 键盘：泊松敲击（键帽脆响 + 机身低频咚），空格键更沉更响 */
    private fun keyboard(): () -> Float {
        val r = rnd(177)
        var lp = 0f
        var clickEnv = 0f
        var thockEnv = 0f
        var nextClick = 0
        var space = false
        return {
            val w = r.nextFloat() * 2f - 1f
            var s = 0f
            if (--nextClick <= 0) {
                nextClick = (SR * (0.14f + r.nextFloat() * 0.38f)).toInt()
                space = r.nextFloat() < 0.12f
                clickEnv = if (space) 0.5f else 0.3f + r.nextFloat() * 0.2f
                thockEnv = clickEnv * 0.8f
            }
            if (clickEnv > 0.003f) {
                lp += 0.55f * (w - lp)
                s += (w - lp) * clickEnv * (if (space) 0.7f else 1f)  // 键帽脆响
                clickEnv *= 0.992f                                     // ~18ms
            }
            if (thockEnv > 0.003f) {
                s += (w * 0.3f + lp * 0.7f) * thockEnv * 0.5f          // 机身「咚」
                thockEnv *= 0.9985f                                    // ~45ms
            }
            s
        }
    }
}
