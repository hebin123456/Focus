package me.hebin.focus.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import androidx.fragment.app.FragmentActivity
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.net.Uri
import android.view.View
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.hebin.focus.data.CardCatalog
import me.hebin.focus.data.Rarity
import me.hebin.focus.ui.view.CardArt
import me.hebin.focus.ui.view.CardView
import java.io.File

/**
 * 图鉴卡片 → 生成分享图（PNG 海报）→ 拉起系统分享面板。
 *
 * 分享图版式（1080 × 1920）：
 *   顶部品牌语 ｜ 卡片本体（离屏 CardView 直绘，稀有度配色）
 *   ｜ 名称 + 稀有度胶囊 ｜ 底部水印。
 * 图片写进 cache/share，经 FileProvider 授权分享，聊天类 App 都能收。
 */
object ShareCard {

    private const val W = 1080
    private const val H = 1920
    private const val CARD_W = 660          // 卡片宽（高 = 宽 × 4.25 / 3）
    private const val CARD_TOP = 420        // 卡片顶部 y

    /** 入口：生成分享图并拉起分享面板（生成在 IO 线程，主线程只发 Intent） */
    fun share(activity: FragmentActivity, cardId: Int, rarity: Rarity) {
        activity.lifecycleScope.launch(Dispatchers.IO) {
            runCatching {
                val uri = composeAndCache(activity, cardId, rarity)
                withContext(Dispatchers.Main) { launchChooser(activity, uri, cardId, rarity) }
            }.onFailure {
                withContext(Dispatchers.Main) {
                    Toast.makeText(activity, "分享图生成失败，请重试", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    // ---------------- 合成 ----------------

    private fun composeAndCache(context: Context, cardId: Int, rarity: Rarity): Uri {
        val bmp = compose(context, cardId, rarity)
        val uri = runCatching { writeCache(context, bmp, cardId, rarity) }
        bmp.recycle()
        return uri.getOrThrow()
    }

    private fun compose(context: Context, cardId: Int, rarity: Rarity): Bitmap {
        val bmp = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val cx = W / 2f

        // 背景：深夜蓝渐变 + 稀有度色顶部辉光
        val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                0f, 0f, 0f, H.toFloat(),
                0xFF151B30.toInt(), 0xFF0A0E1C.toInt(),
                Shader.TileMode.CLAMP
            )
        }
        c.drawRect(0f, 0f, W.toFloat(), H.toFloat(), bg)
        val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient(
                cx, H * 0.16f, W * 1.05f,
                intArrayOf((rarity.color and 0x00FFFFFF) or 0x59000000, Color.TRANSPARENT),
                null, Shader.TileMode.CLAMP
            )
        }
        c.drawRect(0f, 0f, W.toFloat(), H.toFloat(), glow)

        val bold = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        val white = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }

        // 顶部品牌
        white.textSize = 64f
        white.typeface = bold
        white.textAlign = Paint.Align.CENTER
        c.drawText("FOCUS", cx, 170f, white)
        white.textSize = 34f
        white.typeface = Typeface.DEFAULT
        white.alpha = 165
        c.drawText("专注时收服的萌宠卡", cx, 232f, white)
        white.alpha = 255

        // 卡片本体：离屏 CardView（先同步预热卡面美术）
        warmArt(context, cardId)
        val cardH = (CARD_W * 4.25f / 3f).toInt()
        val card = CardView(context).apply {
            mode = CardView.Mode.FACE
            this.rarity = rarity
            this.cardNumber = cardId
            locked = false
        }
        card.measure(
            View.MeasureSpec.makeMeasureSpec(CARD_W, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(cardH, View.MeasureSpec.EXACTLY)
        )
        card.layout(0, 0, CARD_W, cardH)

        // 卡片投影
        val shadow = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x66000000 }
        val sr = RectF(
            (cx - CARD_W / 2f) + 16f, (CARD_TOP + 24f).toFloat(),
            (cx + CARD_W / 2f) + 16f, (CARD_TOP + cardH + 24f).toFloat()
        )
        c.drawRoundRect(sr, 48f, 48f, shadow)

        c.save()
        c.translate(cx - CARD_W / 2f, CARD_TOP.toFloat())
        card.draw(c)
        c.restore()

        // 名称
        val name = CardCatalog.displayName(cardId)
        white.textSize = 60f
        white.typeface = bold
        c.drawText(name, cx, (CARD_TOP + cardH + 110).toFloat(), white)

        // 稀有度胶囊
        val pill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = rarity.color }
        val pillText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = rarity.color
            textSize = 40f
            typeface = bold
            textAlign = Paint.Align.CENTER
        }
        val label = "${rarity.label} · ${CardCatalog.categoryOfId(cardId)}类萌宠"
        val tw = pillText.measureText(label)
        val pr = RectF(
            cx - tw / 2f - 44f, (CARD_TOP + cardH + 150).toFloat(),
            cx + tw / 2f + 44f, (CARD_TOP + cardH + 246).toFloat()
        )
        pill.style = Paint.Style.STROKE
        pill.strokeWidth = 4f
        c.drawRoundRect(pr, pr.height() / 2f, pr.height() / 2f, pill)
        c.drawText(label, cx, pr.top + pr.height() / 2f + 14f, pillText)

        // 底部水印
        white.textSize = 32f
        white.typeface = Typeface.DEFAULT
        white.alpha = 150
        c.drawText("Focus · 游戏化专注，集卡不打烊", cx, H - 96f, white)
        white.alpha = 255

        return bmp
    }

    /** 卡面美术预热：缓存未命中时本线程直接解码塞入（CardView 首绘即有图） */
    private fun warmArt(context: Context, cardId: Int) {
        if (CardArt.peek(cardId) != null) return
        runCatching {
            context.assets.open(CardArt.assetPath(cardId)).use {
                BitmapFactory.decodeStream(it)
            }
        }.getOrNull()?.let { CardArt.warm(cardId, it) }
    }

    // ---------------- 落盘 + 分享 ----------------

    private fun writeCache(context: Context, bmp: Bitmap, cardId: Int, rarity: Rarity): Uri {
        val dir = File(context.cacheDir, "share").apply { mkdirs() }
        val f = File(dir, "focus_card_%03d_%d.png".format(cardId, rarity.ordinal))
        f.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", f)
    }

    private fun launchChooser(activity: Activity, uri: Uri, cardId: Int, rarity: Rarity) {
        val name = CardCatalog.displayName(cardId)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(
                Intent.EXTRA_TEXT,
                "我在 Focus 专注时收服了「$name · ${rarity.label}」，一起来收集萌宠卡吧！"
            )
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        activity.startActivity(Intent.createChooser(send, "分享「$name」"))
    }
}
