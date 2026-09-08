package me.hebin.focus.ui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.SeekBar
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.hebin.focus.R
import me.hebin.focus.audio.AmbientPlayer
import me.hebin.focus.audio.AmbientPrefs
import me.hebin.focus.audio.AmbientSound
import me.hebin.focus.data.CardCatalog
import me.hebin.focus.data.CollectionRepository
import me.hebin.focus.data.DropEngine
import me.hebin.focus.data.crackStatLine
import me.hebin.focus.databinding.ActivityFocusBinding
import me.hebin.focus.session.FocusSessionManager
import me.hebin.focus.session.FocusSessionManager.State
import me.hebin.focus.ui.view.CardView
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * 专注页：计时 → 剪影渐显 →（离开 App / 放弃 → 碎裂动画 | 倒计时结束 → 翻卡揭示）。
 *
 * 「离开 App 判定」：onStop 且非旋转、非锁屏 → 判定离开，卡片碎裂。
 * 锁屏期间计时继续，白噪音持续播放，若期间倒计时结束，回来看结果即可。
 *
 * 深度专注模式：全屏沉浸（隐藏系统栏）、不显示放弃按钮、完成金币翻倍、默认播放白噪音。
 */
class FocusActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_MINUTES = "minutes"
        const val EXTRA_DEEP = "deep"
    }

    private lateinit var binding: ActivityFocusBinding
    private var resultShown = false

    /** 深度专注模式（由主页开关带入） */
    private var deep = false

    /** 当前播放的声景，null = 关闭 */
    private var curSound: AmbientSound? = null

    /** 碎裂发生在后台（切走瞬间）或进程被杀后恢复时，回到前台补播动画 */
    private var replayCrack: State.Cracked? = null

    /** 碎裂结果已在本页展示过（返回键退出视为已知悉，避免主页再弹一次） */
    private var crackNoticeShown = false

    private val shardRnd = Random(System.nanoTime())

    private val stateListener: (State) -> Unit = { s ->
        runOnUiThread { render(s) }
    }

    private val screenOffReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            FocusSessionManager.noteScreenOff()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityFocusBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val minutes = intent.getIntExtra(EXTRA_MINUTES, 25)
        deep = intent.getBooleanExtra(EXTRA_DEEP, false)
        if (deep) applyImmersive()

        // 保持屏幕常亮，避免误触发熄屏
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val state = FocusSessionManager.state
        val restoredCrack = if (state is State.Idle) {
            // 进程被杀后任务栈直接恢复到本页：上次专注已碎裂且用户未被告知 → 不开新会话，直接告知
            CollectionRepository.get(this).peekPendingCrack()
        } else null
        if (restoredCrack == null && state is State.Idle) {
            FocusSessionManager.start(this, minutes, deep)
        }
        FocusSessionManager.addListener(stateListener)
        if (restoredCrack != null) {
            resultShown = true
            val st = State.Cracked(restoredCrack.reason, restoredCrack.minutes, restoredCrack.elapsedMs)
            showCrackedStatic(st)
            // 布局完成后补播碎裂动画，让用户看清楚发生了什么
            replayCrack = st
        }
        binding.btnGiveUp.setOnClickListener { confirmGiveUp() }
        setupNoisePanel()
        // 自动续播上次选的声景；深度专注默认雨声（用户明确关过则尊重选择）
        if (restoredCrack == null && FocusSessionManager.state is State.Focusing) {
            val init = AmbientPrefs.sound(this, if (deep) AmbientSound.RAIN else AmbientSound.OFF)
            if (init != AmbientSound.OFF) startAmbient(init)
        }
        startTicker()
    }

    override fun onStart() {
        super.onStart()
        registerReceiver(screenOffReceiver, IntentFilter(Intent.ACTION_SCREEN_OFF))
    }

    override fun onResume() {
        super.onResume()
        // 后台碎裂 / 进程被杀恢复的动画在这里补播，让用户看清楚发生了什么
        val pc = replayCrack
        if (pc != null) {
            replayCrack = null
            // 首次恢复时视图尚未完成布局，post 到布局后再播
            binding.root.post { if (!isFinishing) playCrack(pc) }
        }
    }

    override fun onStop() {
        super.onStop()
        runCatching { unregisterReceiver(screenOffReceiver) }
        if (isFinishing || isChangingConfigurations) return
        if (FocusSessionManager.isScreenOffRecent()) return  // 锁屏不算离开
        FocusSessionManager.crackByLeaving(this)             // 切走 = 离开 App
    }

    override fun onDestroy() {
        FocusSessionManager.removeListener(stateListener)
        AmbientPlayer.stop()
        super.onDestroy()
    }

    @SuppressLint("MissingSuperCall")
    override fun onBackPressed() {
        when (FocusSessionManager.state) {
            is State.Focusing -> confirmGiveUp()
            else -> {
                // 碎裂结果已展示过：视为已知悉，清掉待告知标记，避免主页再弹一次
                if (crackNoticeShown) CollectionRepository.get(this).takePendingCrack()
                FocusSessionManager.reset()
                super.onBackPressed()
            }
        }
    }

    private fun confirmGiveUp() {
        AlertDialog.Builder(this)
            .setTitle(if (deep) "深度专注进行中" else "放弃这次专注？")
            .setMessage(
                if (deep) "现在离开，卡片会碎裂，也无法获得双倍金币奖励"
                else "卡片会直接碎裂哦"
            )
            .setPositiveButton("继续专注") { d, _ -> d.dismiss() }
            .setNegativeButton("放弃") { d, _ ->
                d.dismiss()
                FocusSessionManager.giveUp(this)
            }
            .show()
    }

    // ---------------- 渲染 ----------------

    private fun render(state: State) {
        when (state) {
            is State.Idle -> showFocusingPlaceholder()
            is State.Focusing -> showFocusing(state)
            is State.Success -> {
                if (!resultShown) { resultShown = true; playReveal(state.drop) }
                else showSuccessStatic(state.drop)
            }
            is State.Cracked -> {
                if (!resultShown) {
                    resultShown = true
                    if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                        playCrack(state)
                    } else {
                        // 碎裂发生在后台（切走瞬间）：先摆好静态结果，回前台补播动画
                        replayCrack = state
                        showCrackedStatic(state)
                    }
                } else showCrackedStatic(state)
            }
        }
    }

    private fun showFocusingPlaceholder() {
        binding.textTitle.text = "准备中…"
    }

    private fun showFocusing(state: State.Focusing) {
        binding.textTitle.text = "专注中，别走开"
        binding.textHint.isVisible = true
        // 深度专注不显示放弃按钮，减少诱惑；返回键仍可退出（弹强提醒）
        binding.btnGiveUp.isVisible = !deep
        binding.noisePanel.isVisible = true
        binding.cardSilhouette.isVisible = true
        binding.cardSilhouette.mode = CardView.Mode.SILHOUETTE
        binding.cardSilhouette.startShimmer()
        binding.textResult.isVisible = false
        binding.textReason.isVisible = false
        binding.btnCollect.isVisible = false
        updateCountdown(state)
    }

    private fun startTicker() {
        lifecycleScope.launch {
            while (isActive) {
                val s = FocusSessionManager.state
                if (s is State.Focusing) updateCountdown(s)
                delay(250)
            }
        }
    }

    private fun updateCountdown(s: State.Focusing) {
        val remain = (s.endAt - System.currentTimeMillis()).coerceAtLeast(0)
        val m = remain / 60000
        val sec = (remain % 60000) / 1000
        binding.textCountdown.text = String.format("%02d:%02d", m, sec)
        val elapsed = (s.totalMs - remain).toFloat() / s.totalMs
        binding.progressFocus.progress = (elapsed * 100).toInt()
        binding.cardSilhouette.revealProgress = elapsed
    }

    // ---------------- 成功：翻卡揭示 ----------------

    private fun playReveal(drop: DropEngine.Drop) {
        vibrate(200)
        binding.textHint.isVisible = false
        binding.btnGiveUp.isVisible = false
        binding.noisePanel.isVisible = false
        AmbientPlayer.stop()
        binding.cardSilhouette.stopShimmer()

        val front = binding.cardFront
        front.mode = CardView.Mode.FACE
        front.rarity = drop.rarity
        front.cardNumber = drop.cardId
        front.locked = false
        front.isVisible = true
        front.rotationY = 90f
        front.alpha = 1f

        binding.cardSilhouette.animate()
            .rotationY(-90f)
            .setDuration(220)
            .setInterpolator(AccelerateInterpolator())
            .withEndAction {
                binding.cardSilhouette.isVisible = false
                front.animate()
                    .rotationY(0f)
                    .setDuration(320)
                    .setInterpolator(OvershootInterpolator(1.4f))
                    .withEndAction {
                        front.animate()
                            .scaleX(1.05f).scaleY(1.05f).setDuration(380)
                            .withEndAction {
                                front.animate().scaleX(1f).scaleY(1f).duration = 380
                            }.start()
                        showSuccessStatic(drop)
                    }
                    .start()
            }
            .start()
    }

    private fun showSuccessStatic(drop: DropEngine.Drop) {
        binding.textCountdown.isVisible = false
        binding.progressFocus.isVisible = false
        binding.textTitle.text = "专注完成！"
        binding.textResult.isVisible = true
        binding.textResult.text = "获得 ${drop.rarity.label} · ${CardCatalog.displayName(drop.cardId)}"
        binding.textResult.setTextColor(drop.rarity.color)
        binding.textReason.isVisible = true
        binding.textReason.text = if (deep) "已收入图鉴 · 深度专注金币翻倍" else "已收入图鉴"
        binding.btnCollect.isVisible = true
        binding.btnCollect.setOnClickListener {
            FocusSessionManager.reset()
            finish()
        }
    }

    // ---------------- 失败：碎裂动画 ----------------

    /** 碎裂：抖动 → 闪白 + 13 块放射碎片 + 火花 */
    private fun playCrack(cracked: State.Cracked) {
        vibrate(80)
        binding.textHint.isVisible = false
        binding.btnGiveUp.isVisible = false
        binding.cardSilhouette.stopShimmer()
        showCrackedStatic(cracked)

        val card = binding.cardSilhouette
        val stage = binding.cardStage
        if (card.width <= 0 || card.height <= 0) return

        // 阶段 1：撞击抖动
        val shake = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 260
            interpolator = LinearInterpolator()
            addUpdateListener { a ->
                val t = a.animatedValue as Float
                card.translationX = sin(t * 34f) * dp(7f) * (1f - t)
                card.translationY = cos(t * 41f) * dp(4f) * (1f - t)
            }
        }
        shake.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                card.translationX = 0f
                card.translationY = 0f
                burstShards(stage, card)
            }
        })
        shake.start()
    }

    /** 阶段 2：切碎片（后台线程，纯位图运算）→ 闪白 + 爆散 + 火花（主线程） */
    private fun burstShards(stage: FrameLayout, card: CardView) {
        // View.draw 必须在主线程；切片只碰 Bitmap，放后台
        val source = Bitmap.createBitmap(card.width, card.height, Bitmap.Config.ARGB_8888)
        card.draw(Canvas(source))
        lifecycleScope.launch {
            val shards = withContext(Dispatchers.Default) { sliceShards(source) }
            if (shards.isEmpty()) {
                card.isVisible = false
                return@launch
            }
            card.isVisible = false
            flashStage(stage, card)
            shards.forEach { spawnShardView(stage, card, it) }
            spawnSparks(stage, card)
        }
    }

    /** 一块碎片：位图 + 卡片视图坐标系中的位置与质心 */
    private class Shard(
        val bmp: Bitmap,
        val left: Int,
        val top: Int,
        val cx: Float,
        val cy: Float,
        /** 位图像素 / 视图像素 */
        val scale: Float
    )

    /** 从（略微偏移的）中心放射状切成 13 块不规则碎片。后台线程执行 */
    private fun sliceShards(src: Bitmap): List<Shard> {
        val w = src.width
        val h = src.height
        if (w < 16 || h < 16) return emptyList()

        // 控内存：过大的源图先降到宽 ≤ 540
        val bmp = if (w > 540) {
            val s = 540f / w
            Bitmap.createScaledBitmap(src, 540, (h * s).toInt().coerceAtLeast(1), true)
        } else src
        val bw = bmp.width.toFloat()
        val bh = bmp.height.toFloat()
        val scale = bw / w

        val cx = bw / 2f + (shardRnd.nextFloat() - 0.5f) * bw * 0.12f
        val cy = bh / 2f + (shardRnd.nextFloat() - 0.5f) * bh * 0.12f

        val n = 13
        val pts = Array(n) { i ->
            val ang = (2.0 * Math.PI * i / n + (shardRnd.nextDouble() - 0.5) * 0.22).toFloat()
            rayToRect(cx, cy, ang, bw, bh)
        }

        val out = ArrayList<Shard>(n)
        val p = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        for (i in 0 until n) {
            val a = pts[i]
            val b = pts[(i + 1) % n]
            val path = Path().apply {
                moveTo(cx, cy)
                lineTo(a[0], a[1])
                lineTo(b[0], b[1])
                close()
            }
            val left = minOf(cx, a[0], b[0]).coerceAtLeast(0f)
            val top = minOf(cy, a[1], b[1]).coerceAtLeast(0f)
            val right = maxOf(cx, a[0], b[0]).coerceAtMost(bw)
            val bottom = maxOf(cy, a[1], b[1]).coerceAtMost(bh)
            val iw = (right - left).toInt().coerceAtLeast(1)
            val ih = (bottom - top).toInt().coerceAtLeast(1)

            val piece = Bitmap.createBitmap(iw, ih, Bitmap.Config.ARGB_8888)
            val c = Canvas(piece)
            c.save()
            c.translate(-left, -top)
            c.clipPath(path)
            c.drawBitmap(bmp, 0f, 0f, p)
            c.restore()

            // 三角形质心（决定飞散方向）
            val scx = (cx + a[0] + b[0]) / 3f
            val scy = (cy + a[1] + b[1]) / 3f
            out.add(
                Shard(
                    piece,
                    (left / scale).toInt(),
                    (top / scale).toInt(),
                    scx / scale,
                    scy / scale,
                    scale
                )
            )
        }
        if (bmp !== src) bmp.recycle()
        src.recycle()
        return out
    }

    /** 从 (cx,cy) 沿角度 ang 的射线与矩形边界的交点 */
    private fun rayToRect(cx: Float, cy: Float, ang: Float, w: Float, h: Float): FloatArray {
        val dx = cos(ang)
        val dy = sin(ang)
        var t = Float.MAX_VALUE
        if (dx > 1e-6f) t = minOf(t, (w - cx) / dx)
        if (dx < -1e-6f) t = minOf(t, -cx / dx)
        if (dy > 1e-6f) t = minOf(t, (h - cy) / dy)
        if (dy < -1e-6f) t = minOf(t, -cy / dy)
        if (t == Float.MAX_VALUE) t = 0f
        return floatArrayOf(cx + dx * t, cy + dy * t)
    }

    private fun spawnShardView(stage: FrameLayout, card: CardView, s: Shard) {
        val iv = ImageView(this)
        iv.setImageBitmap(s.bmp)
        val vw = (s.bmp.width / s.scale).toInt().coerceAtLeast(1)
        val vh = (s.bmp.height / s.scale).toInt().coerceAtLeast(1)
        val lp = FrameLayout.LayoutParams(vw, vh)
        lp.leftMargin = card.left + s.left
        lp.topMargin = card.top + s.top
        stage.addView(iv, lp)

        // 从卡片中心向外飞散
        val stageCx = card.left + card.width / 2f
        val stageCy = card.top + card.height / 2f
        val dirX = (lp.leftMargin + vw / 2f) - stageCx
        val dirY = (lp.topMargin + vh / 2f) - stageCy
        val len = maxOf(1f, sqrt(dirX * dirX + dirY * dirY))
        val dist = dp(170f + shardRnd.nextFloat() * 250f)
        val ux = dirX / len
        val uy = dirY / len

        iv.animate()
            .translationX(ux * dist + dirX * 0.45f)
            .translationY(uy * dist + dirY * 0.45f + dp(100f))
            .rotation(shardRnd.nextFloat() * 420f - 210f)
            .alpha(0f)
            .setStartDelay((shardRnd.nextFloat() * 90).toLong())
            .setDuration((650 + shardRnd.nextFloat() * 300).toLong())
            .setInterpolator(AccelerateInterpolator(1.25f))
            .withEndAction { (iv.parent as? ViewGroup)?.removeView(iv) }
            .start()
    }

    /** 撞击闪白 */
    private fun flashStage(stage: FrameLayout, card: CardView) {
        val flash = View(this)
        flash.background = GradientDrawable().apply {
            cornerRadius = dp(14f)
            setColor(0xCCFFFFFF.toInt())
        }
        val lp = FrameLayout.LayoutParams(card.width, card.height)
        lp.leftMargin = card.left
        lp.topMargin = card.top
        stage.addView(flash, lp)
        flash.animate()
            .alpha(0f)
            .setDuration(220)
            .setInterpolator(DecelerateInterpolator())
            .withEndAction { (flash.parent as? ViewGroup)?.removeView(flash) }
            .start()
    }

    /** 四散火花 */
    private fun spawnSparks(stage: FrameLayout, card: CardView) {
        val colors = intArrayOf(0xFFF5C542.toInt(), 0xFFFFFFFF.toInt(), 0xFF67E8F9.toInt())
        val cx = card.left + card.width / 2f
        val cy = card.top + card.height / 2f
        repeat(10) {
            val size = dp(3f + shardRnd.nextInt(4)).toInt()
            val dot = View(this)
            dot.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(colors[shardRnd.nextInt(colors.size)])
            }
            val lp = FrameLayout.LayoutParams(size, size)
            lp.leftMargin = (cx - size / 2f).toInt()
            lp.topMargin = (cy - size / 2f).toInt()
            stage.addView(dot, lp)
            val ang = shardRnd.nextFloat() * 2f * Math.PI.toFloat()
            val dist = dp(120f + shardRnd.nextFloat() * 170f)
            dot.animate()
                .translationX(cos(ang) * dist)
                .translationY(sin(ang) * dist)
                .alpha(0f)
                .setDuration((420 + shardRnd.nextFloat() * 200).toLong())
                .setInterpolator(DecelerateInterpolator(1.6f))
                .withEndAction { (dot.parent as? ViewGroup)?.removeView(dot) }
                .start()
        }
    }

    private fun showCrackedStatic(cracked: State.Cracked) {
        crackNoticeShown = true
        binding.textCountdown.isVisible = false
        binding.progressFocus.isVisible = false
        binding.noisePanel.isVisible = false
        AmbientPlayer.stop()
        binding.textTitle.text = "卡片碎裂了"
        binding.textResult.isVisible = true
        binding.textResult.text = cracked.reason
        binding.textResult.setTextColor(ContextCompat.getColor(this, R.color.danger))
        binding.textReason.isVisible = true
        val stat = crackStatLine(cracked.minutes, cracked.elapsedMs)
        binding.textReason.text = buildString {
            append("专注中断，编号未能生成")
            if (stat.isNotEmpty()) append('\n').append(stat)
            append("\n再试一次，坚持到底！")
        }
        binding.btnCollect.isVisible = true
        binding.btnCollect.text = "知道了"
        binding.btnCollect.setOnClickListener {
            CollectionRepository.get(this).takePendingCrack() // 用户已知悉，清掉待告知标记
            FocusSessionManager.reset()
            finish()
        }
    }

    // ---------------- 白噪音 / 深度专注 ----------------

    /** 深度专注：全屏沉浸，隐藏状态栏与导航栏（滑动可临时呼出） */
    private fun applyImmersive() {
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsetsCompat.Type.systemBars())
    }

    private fun setupNoisePanel() {
        val buttons = listOf(
            AmbientSound.OFF to R.id.btnNoiseOff,
            AmbientSound.WHITE to R.id.btnNoiseWhite,
            AmbientSound.RAIN to R.id.btnNoiseRain,
            AmbientSound.WAVES to R.id.btnNoiseWave,
            AmbientSound.FIRE to R.id.btnNoiseFire
        )
        val current = curSound
            ?: AmbientPrefs.sound(this, if (deep) AmbientSound.RAIN else AmbientSound.OFF)
        binding.noiseGroup.check(buttons.first { it.first == current }.second)
        binding.noiseGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            val s = buttons.first { it.second == checkedId }.first
            AmbientPrefs.setSound(this, s)
            if (s == AmbientSound.OFF) {
                curSound = null
                AmbientPlayer.stop()
            } else {
                startAmbient(s)
            }
        }
        binding.noiseVolume.progress = AmbientPrefs.volume(this)
        binding.noiseVolume.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {
                if (!fromUser) return
                AmbientPrefs.setVolume(this@FocusActivity, p)
                AmbientPlayer.setVolume(p / 100f)
            }

            override fun onStartTrackingTouch(sb: SeekBar) = Unit
            override fun onStopTrackingTouch(sb: SeekBar) = Unit
        })
    }

    private fun startAmbient(s: AmbientSound) {
        curSound = s
        AmbientPlayer.start(s, AmbientPrefs.volume(this) / 100f)
    }

    // ---------------- 工具 ----------------

    private fun vibrate(ms: Long) {
        runCatching {
            val vib = if (Build.VERSION.SDK_INT >= 31) {
                (getSystemService(VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                getSystemService(VIBRATOR_SERVICE) as Vibrator
            }
            if (Build.VERSION.SDK_INT >= 26) {
                vib.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION") vib.vibrate(ms)
            }
        }
    }

    private fun dp(v: Float): Float = v * resources.displayMetrics.density
}
