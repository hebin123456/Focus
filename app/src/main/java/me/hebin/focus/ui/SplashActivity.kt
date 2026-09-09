package me.hebin.focus.ui

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import me.hebin.focus.ads.SplashAdManager
import me.hebin.focus.databinding.ActivitySplashBinding

/**
 * 开屏页：所有桌面图标（506 个 activity-alias）统一先进这里，
 * 3 秒倒计时（随时可点跳过）后进主页。
 *
 * 开屏广告接入（对齐 AdApi / PaymentApi 的 provider 模式）：
 *  - 默认无 SDK：广告容器展示内置品牌图（四宫格萌宠 + slogan）+「开屏广告位」角标；
 *  - Application 里调用 `SplashAdManager.setProvider(XxxSplashAdApi())` 后，
 *    loadView 有填充则铺满容器、隐藏占位图；倒计时与跳过仍由本页统一控制。
 */
class SplashActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySplashBinding
    private val handler = Handler(Looper.getMainLooper())
    private var remainSeconds = COUNTDOWN_SECONDS
    private var dismissed = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySplashBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // 底部版本号（读系统包信息，与 build.gradle 的 versionName 永远一致）
        binding.splashTextVersion.text = runCatching {
            "本地资料版 · v" + packageManager.getPackageInfo(packageName, 0).versionName
        }.getOrDefault("本地资料版")

        mountSplashAd()

        binding.splashBtnSkip.setOnClickListener { goHome() }
        renderSkipButton()
        handler.postDelayed(tick, 1_000)
    }

    /** 广告容器：SDK 有填充则铺满替换品牌占位图；无 SDK 保持品牌图 + 广告位角标 */
    private fun mountSplashAd() {
        val adView = SplashAdManager.loadView(this)
        if (adView != null) {
            binding.splashBrandPlaceholder.isVisible = false
            binding.splashAdBadge.isVisible = false
            binding.splashAdFrame.addView(
                adView,
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        } else {
            // 品牌占位图淡入
            binding.splashAdFrame.alpha = 0f
            binding.splashAdFrame.animate().alpha(1f).setDuration(400).start()
        }
    }

    private val tick = object : Runnable {
        override fun run() {
            remainSeconds -= 1
            if (remainSeconds <= 0) {
                goHome()
            } else {
                renderSkipButton()
                handler.postDelayed(this, 1_000)
            }
        }
    }

    private fun renderSkipButton() {
        binding.splashBtnSkip.text = "跳过 $remainSeconds"
    }

    /** 进主页（倒计时结束 / 点跳过都会走这里；只执行一次） */
    private fun goHome() {
        if (dismissed) return
        dismissed = true
        handler.removeCallbacksAndMessages(null)
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    companion object {
        private const val COUNTDOWN_SECONDS = 3
    }
}
