package me.hebin.focus.ads

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.ViewGroup
import android.view.Window
import android.widget.Button
import android.widget.FrameLayout
import android.widget.TextView
import me.hebin.focus.R

/**
 * 本地模拟激励视频：全屏播放 3 秒倒计时，看完才能点「领取奖励」。
 * 还原真实激励视频的核心交互（中途退出无奖励），SDK 到位后替换 Provider 即可。
 */
class StubAdApi : AdApi {

    override val name: String = "Stub(模拟广告)"

    override fun initialize(context: Context) = Unit

    override fun isAvailable(): Boolean = true

    override fun show(activity: Activity, onResult: (AdResult) -> Unit) {
        StubAdDialog(activity, onResult).show()
    }

    /** 全屏模拟广告：3 秒倒计时 → 领取按钮亮起；中途退出视为未看完 */
    private class StubAdDialog(
        private val activity: Activity,
        private val onResult: (AdResult) -> Unit
    ) : Dialog(activity, android.R.style.Theme_Black_NoTitleBar_Fullscreen) {

        private val handler = Handler(Looper.getMainLooper())
        private var secondLeft = DURATION_S
        private var rewarded = false

        private val countdownText = TextView(activity).apply {
            text = "广告播放中 · ${DURATION_S}s"
            setTextColor(0xFF8A93A8.toInt())
            textSize = 13f
            gravity = Gravity.CENTER
        }

        private val claimButton = Button(activity).apply {
            text = "看完才能领取奖励"
            isEnabled = false
            isAllCaps = false
            setTextColor(0xFF6B7280.toInt())
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            stateListAnimator = null
            background = GradientDrawable().apply {
                cornerRadius = dp(24).toFloat()
                setColor(0xFF232B3D.toInt())
            }
        }

        private val tickRunnable = object : Runnable {
            override fun run() {
                secondLeft--
                if (secondLeft > 0) {
                    countdownText.text = "广告播放中 · ${secondLeft}s"
                    handler.postDelayed(this, 1000)
                } else {
                    countdownText.text = "播放完毕"
                    claimButton.isEnabled = true
                    claimButton.text = "领取 +${AdRewardManager.REWARD_COINS} 金币"
                    claimButton.setTextColor(0xFFF5C542.toInt())
                    claimButton.background = GradientDrawable().apply {
                        cornerRadius = dp(24).toFloat()
                        setColor(0xFF3A321A.toInt())
                        setStroke(dp(1), 0xFFF5C542.toInt())
                    }
                }
            }
        }

        override fun onCreate(savedInstanceState: Bundle?) {
            super.onCreate(savedInstanceState)
            requestWindowFeature(Window.FEATURE_NO_TITLE)
            setContentView(buildContent())
            setCancelable(true) // 返回键 / 外点可退，退出即未看完

            claimButton.setOnClickListener {
                rewarded = true
                dismiss()
            }
            setOnDismissListener {
                handler.removeCallbacks(tickRunnable)
                onResult(if (rewarded) AdResult.Rewarded else AdResult.Cancelled)
            }
            window?.apply {
                setBackgroundDrawableResource(android.R.color.black)
                // 复用全局弹窗动画（缩放淡入）
                attributes = attributes.apply {
                    windowAnimations = R.style.Animation_Focus_Dialog
                }
            }
        }

        override fun onStart() {
            super.onStart()
            handler.postDelayed(tickRunnable, 1000)
        }

        private fun buildContent(): ViewGroup {
            val root = FrameLayout(activity).apply { setBackgroundColor(0xFF0B0F1A.toInt()) }

            // 中央广告占位
            val placeholder = TextView(activity).apply {
                text = "广告位示例\n正式版将接入激励视频 SDK"
                setTextColor(0xFF59617A.toInt())
                textSize = 14f
                gravity = Gravity.CENTER
                background = GradientDrawable().apply {
                    orientation = GradientDrawable.Orientation.TL_BR
                    colors = intArrayOf(0xFF161D2E.toInt(), 0xFF101524.toInt())
                    cornerRadius = dp(16).toFloat()
                    setStroke(dp(1), 0xFF222B41.toInt())
                }
            }
            root.addView(placeholder, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            ).apply {
                leftMargin = dp(32); rightMargin = dp(32)
                topMargin = dp(140); bottomMargin = dp(180)
            })

            // 顶部倒计时
            root.addView(countdownText, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.CENTER_HORIZONTAL
            ).apply { topMargin = dp(64) })

            // 底部领取按钮
            root.addView(claimButton, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(52)
            ).apply {
                leftMargin = dp(40); rightMargin = dp(40)
                bottomMargin = dp(72)
                gravity = Gravity.BOTTOM
            })

            // 左上角退出提示：中途退出没有奖励
            val skipHint = TextView(activity).apply {
                text = "✕ 退出（无奖励）"
                setTextColor(0xFF4A5268.toInt())
                textSize = 12f
            }
            root.addView(skipHint, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.START
            ).apply { leftMargin = dp(20); topMargin = dp(20) })

            return root
        }

        private fun dp(v: Int): Int =
            (v * activity.resources.displayMetrics.density).toInt()
    }

    private companion object {
        /** 模拟广告时长（秒） */
        const val DURATION_S = 3
    }
}
