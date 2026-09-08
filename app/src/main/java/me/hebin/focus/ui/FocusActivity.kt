package me.hebin.focus.ui

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.Canvas
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
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.hebin.focus.R
import me.hebin.focus.data.DropEngine
import me.hebin.focus.databinding.ActivityFocusBinding
import me.hebin.focus.session.FocusSessionManager
import me.hebin.focus.session.FocusSessionManager.State
import me.hebin.focus.ui.view.CardView
import kotlin.random.Random

/**
 * 专注页：计时 → 剪影渐显 →（离开 App / 放弃 → 碎裂动画 | 倒计时结束 → 翻卡揭示）。
 *
 * 「离开 App 判定」：onStop 且非旋转、非锁屏 → 判定离开，卡片碎裂。
 * 锁屏期间计时继续，若期间倒计时结束，回来看结果即可。
 */
class FocusActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_MINUTES = "minutes"
    }

    private lateinit var binding: ActivityFocusBinding
    private var resultShown = false

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

        // 保持屏幕常亮，避免误触发熄屏
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val state = FocusSessionManager.state
        when (state) {
            is State.Idle -> FocusSessionManager.start(this, minutes)
            is State.Focusing, is State.Success, is State.Cracked -> {
                // 旋转/锁屏回来或重进，恢复展示；Success/Cracked 只重放一次结果
            }
        }
        FocusSessionManager.addListener(stateListener)
        binding.btnGiveUp.setOnClickListener { confirmGiveUp() }
        startTicker()
    }

    override fun onStart() {
        super.onStart()
        registerReceiver(screenOffReceiver, IntentFilter(Intent.ACTION_SCREEN_OFF))
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
        super.onDestroy()
    }

    @SuppressLint("MissingSuperCall")
    override fun onBackPressed() {
        when (FocusSessionManager.state) {
            is State.Focusing -> confirmGiveUp()
            else -> {
                FocusSessionManager.reset()
                super.onBackPressed()
            }
        }
    }

    private fun confirmGiveUp() {
        AlertDialog.Builder(this)
            .setTitle("放弃这次专注？")
            .setMessage("卡片会直接碎裂哦")
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
                if (!resultShown) { resultShown = true; playCrack(state.reason) }
                else showCrackedStatic(state.reason)
            }
        }
    }

    private fun showFocusingPlaceholder() {
        binding.textTitle.text = "准备中…"
    }

    private fun showFocusing(state: State.Focusing) {
        binding.textTitle.text = "专注中，别走开"
        binding.textHint.isVisible = true
        binding.btnGiveUp.isVisible = true
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
        binding.textResult.text = "获得 ${drop.rarity.label} · 编号 ${drop.cardId}"
        binding.textResult.setTextColor(drop.rarity.color)
        binding.textReason.isVisible = true
        binding.textReason.text = "已收入图鉴"
        binding.btnCollect.isVisible = true
        binding.btnCollect.setOnClickListener {
            FocusSessionManager.reset()
            finish()
        }
    }

    // ---------------- 失败：碎裂动画 ----------------

    private fun playCrack(reason: String) {
        vibrate(80)
        binding.textHint.isVisible = false
        binding.btnGiveUp.isVisible = false
        binding.cardSilhouette.stopShimmer()
        binding.textCountdown.isVisible = false
        binding.progressFocus.isVisible = false

        val card = binding.cardSilhouette
        val stage = binding.cardStage
        lifecycleScope.launch {
            val ok = withContext(Dispatchers.Default) { spawnShards(stage, card) }
            if (ok) card.isVisible = false
            showCrackedStatic(reason)
        }
    }

    /** 把卡片切成 6 块碎片飞散。返回是否成功生成碎片 */
    private fun spawnShards(stage: FrameLayout, card: CardView): Boolean {
        if (card.width <= 0 || card.height <= 0) return false
        val bmp = Bitmap.createBitmap(card.width, card.height, Bitmap.Config.ARGB_8888)
        card.draw(Canvas(bmp))

        val cols = 3
        val rows = 2
        val cw = bmp.width / cols
        val ch = bmp.height / rows
        val cx = card.left + card.width / 2f
        val cy = card.top + card.height / 2f
        val rng = Random(System.currentTimeMillis())

        for (r in 0 until rows) {
            for (c in 0 until cols) {
                val pw = if (c == cols - 1) bmp.width - c * cw else cw
                val ph = if (r == rows - 1) bmp.height - r * ch else ch
                val piece = Bitmap.createBitmap(bmp, c * cw, r * ch, pw, ph)

                val iv = ImageView(this)
                iv.setImageBitmap(piece)
                val lp = FrameLayout.LayoutParams(pw, ph)
                lp.leftMargin = card.left + c * cw
                lp.topMargin = card.top + r * ch
                stage.addView(iv, lp)

                val shardCx = lp.leftMargin + pw / 2f
                val shardCy = lp.topMargin + ph / 2f
                val dx = (shardCx - cx) * (1.8f + rng.nextFloat())
                val dy = (shardCy - cy) * (1.8f + rng.nextFloat()) + dp(120f) * rng.nextFloat()

                iv.animate()
                    .translationX(dx)
                    .translationY(dy)
                    .rotation(rng.nextFloat() * 160f - 80f)
                    .alpha(0f)
                    .setDuration(680)
                    .setInterpolator(AccelerateInterpolator(1.2f))
                    .withEndAction { (iv.parent as? ViewGroup)?.removeView(iv) }
                    .start()
            }
        }
        return true
    }

    private fun showCrackedStatic(reason: String) {
        binding.textTitle.text = "卡片碎裂了"
        binding.textResult.isVisible = true
        binding.textResult.text = reason
        binding.textResult.setTextColor(0xFFFF6B6B.toInt())
        binding.textReason.isVisible = true
        binding.textReason.text = "专注中途离开，编号未能生成\n再试一次，坚持到底！"
        binding.btnCollect.isVisible = true
        binding.btnCollect.text = "知道了"
        binding.btnCollect.setOnClickListener {
            FocusSessionManager.reset()
            finish()
        }
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
