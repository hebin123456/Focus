package me.hebin.focus.ads

import android.app.Activity
import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.appcompat.app.AlertDialog

/**
 * 本地模拟广告：弹对话框假播 3 秒，看完自动发奖，可中途跳过（无奖励）。
 * 无任何 SDK 依赖，用于开发期跑通业务闭环。
 */
class StubAdApi : AdApi {

    override val name: String = "Stub(本地模拟)"

    override fun initialize(context: Context) = Unit
    override fun isReady(placement: AdPlacement) = true
    override fun load(placement: AdPlacement) = Unit

    override fun show(activity: Activity, placement: AdPlacement, onResult: (AdResult) -> Unit) {
        val handler = Handler(Looper.getMainLooper())
        var finished = false
        var dialog: AlertDialog? = null

        val finish: (AdResult) -> Unit = { r ->
            if (!finished) {
                finished = true
                runCatching { dialog?.dismiss() }
                onResult(r)
            }
        }

        dialog = AlertDialog.Builder(activity)
            .setTitle("模拟广告 · ${placement.label}")
            .setMessage("广告播放中…（本地模拟 ${PLAY_MS / 1000} 秒）\n\n看完自动发放奖励，中途跳过则无奖励")
            .setCancelable(false)
            .setNegativeButton("跳过（无奖励）", null)
            .create()

        dialog.setOnShowListener {
            dialog?.getButton(AlertDialog.BUTTON_NEGATIVE)?.setOnClickListener { finish(AdResult.ClosedEarly) }
            handler.postDelayed({ finish(AdResult.Rewarded) }, PLAY_MS)
        }
        dialog.show()
    }

    private companion object {
        const val PLAY_MS = 3000L
    }
}
