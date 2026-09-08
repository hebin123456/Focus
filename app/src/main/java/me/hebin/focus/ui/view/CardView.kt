package me.hebin.focus.ui.view

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import me.hebin.focus.data.CardCatalog
import me.hebin.focus.data.Rarity

/**
 * 卡片自绘控件（美术资源版）。
 * 卡面 = assets/cards/NNN.webp 萌宠图 + 稀有度配色 + 名称，
 * 未收集时以深色剪影展示动物轮廓；生成中为通用黑卡剪影（不剧透）。
 *
 * 模式：
 *  - FACE       正面（稀有度配色 + 萌宠图 + 名称），locked=true 时为图鉴未收集样式（暗剪影）
 *  - SILHOUETTE 生成中的剪影（黑卡 + ? + 流光 + 进度渐显）
 */
class CardView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    enum class Mode { FACE, SILHOUETTE }

    var mode: Mode = Mode.FACE
    var rarity: Rarity = Rarity.COMMON
    var cardNumber: Int = 1
        set(v) {
            if (field != v) {
                field = v
                artBitmap = null
                requestedArtId = -1
                invalidate()
            }
        }
    var locked: Boolean = false

    /** 剪影生成进度 0..1（SILHOUETTE 模式下使用） */
    var revealProgress: Float = 0f
        set(v) {
            field = v.coerceIn(0f, 1f)
            invalidate()
        }

    /** 流光相位 0..1，由 shimmer 动画驱动 */
    var shimmerPhase: Float = 0f
        set(v) {
            field = v
            if (mode == Mode.SILHOUETTE) invalidate()
        }

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    private val miscPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    /** 萌宠图绘制 */
    private val artPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { isFilterBitmap = true }

    /** 未收集剪影：把图染成深蓝灰（亮度清零 + 固定色偏移） */
    private val silhouettePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        isFilterBitmap = true
        colorFilter = ColorMatrixColorFilter(
            ColorMatrix(
                floatArrayOf(
                    0f, 0f, 0f, 0f, 42f,   // R -> 42
                    0f, 0f, 0f, 0f, 51f,   // G -> 51
                    0f, 0f, 0f, 0f, 80f,   // B -> 80
                    0f, 0f, 0f, 1f, 0f     // A 保持
                )
            )
        )
    }

    private val rect = RectF()
    private val artRect = RectF()

    /** 已拿到的卡面位图（可能是 null：还在加载） */
    private var artBitmap: Bitmap? = null
    private var requestedArtId: Int = -1

    private var shimmerAnim: ValueAnimator? = null

    private val radius: Float
        get() = minOf(width, height) * 0.10f

    /** 卡片固定纵横比 3 : 4.25；wrap_content 时按宽度推算高度 */
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val hMode = MeasureSpec.getMode(heightMeasureSpec)
        if (w > 0 && (hMode == MeasureSpec.UNSPECIFIED || hMode == MeasureSpec.AT_MOST)) {
            val h = (w * 4.25f / 3f).toInt()
            setMeasuredDimension(w, h)
            return
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    override fun onDraw(canvas: Canvas) {
        if (width == 0 || height == 0) return
        when (mode) {
            Mode.FACE -> drawFace(canvas)
            Mode.SILHOUETTE -> drawSilhouette(canvas)
        }
    }

    /** 按需加载卡面图，到位后 invalidate */
    private fun ensureArt() {
        val id = cardNumber
        if (artBitmap != null || requestedArtId == id) return
        requestedArtId = id
        CardArt.load(context, id) { bmp ->
            if (bmp != null && requestedArtId == id) {
                artBitmap = bmp
                invalidate()
            }
        }
    }

    /** 萌宠图目标区域：顶部留白 + 正方形主体，底部留给文字 */
    private fun artRectOf(w: Float, h: Float): RectF {
        val side = w * 0.86f
        val left = (w - side) / 2f
        val top = h * 0.05f
        artRect.set(left, top, left + side, top + side)
        return artRect
    }

    // ---------------- 正面 ----------------

    private fun drawFace(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        rect.set(0f, 0f, w, h)
        val r = radius

        if (locked) {
            // 未收集：深底 + 虚线框 + 动物暗剪影 + 编号
            bgPaint.shader = LinearGradient(
                0f, 0f, 0f, h,
                Color.parseColor("#141826"), Color.parseColor("#0D101A"),
                Shader.TileMode.CLAMP
            )
            canvas.drawRoundRect(rect, r, r, bgPaint)
            borderPaint.strokeWidth = dp(1.5f)
            borderPaint.color = Color.parseColor("#39415A")
            borderPaint.pathEffect = DashPathEffect(floatArrayOf(dp(5f), dp(4f)), 0f)
            canvas.drawRoundRect(rect, r, r, borderPaint)
            borderPaint.pathEffect = null

            ensureArt()
            artBitmap?.let {
                canvas.drawBitmap(it, null, artRectOf(w, h), silhouettePaint)
            }

            textPaint.textSize = minOf(w, h) * 0.10f
            textPaint.color = Color.parseColor("#4A5370")
            canvas.drawText(
                CardCatalog.formattedNumber(cardNumber) + " · 未收集",
                w / 2, h * 0.86f, textPaint
            )
            return
        }

        // 已收集：按稀有度上色
        bgPaint.shader = LinearGradient(
            0f, 0f, 0f, h,
            rarity.bgTop, rarity.bgBottom,
            Shader.TileMode.CLAMP
        )
        canvas.drawRoundRect(rect, r, r, bgPaint)

        // 高稀有度：角落辉光
        if (rarity.ordinal >= Rarity.GOLD.ordinal) {
            miscPaint.shader = RadialGradient(
                w * 0.8f, h * 0.15f, w * 0.9f,
                intArrayOf(
                    (rarity.color and 0x00FFFFFF) or 0x26000000,
                    Color.TRANSPARENT
                ),
                null, Shader.TileMode.CLAMP
            )
            canvas.drawRoundRect(rect, r, r, miscPaint)
            miscPaint.shader = null
        }

        // 萌宠主体
        ensureArt()
        artBitmap?.let {
            canvas.drawBitmap(it, null, artRectOf(w, h), artPaint)
        }

        // 边框
        borderPaint.strokeWidth = dp(if (rarity.ordinal >= Rarity.GOLD.ordinal) 3f else 2f)
        borderPaint.color = rarity.color
        canvas.drawRoundRect(rect, r, r, borderPaint)

        // 顶部编号
        textPaint.textAlign = Paint.Align.LEFT
        textPaint.textSize = minOf(w, h) * 0.075f
        textPaint.color = 0xB3FFFFFF.toInt()
        canvas.drawText(CardCatalog.formattedNumber(cardNumber), dp(9f), dp(17f), textPaint)
        textPaint.textAlign = Paint.Align.CENTER

        // 名称
        labelPaint.textSize = minOf(w, h) * 0.115f
        labelPaint.color = Color.WHITE
        labelPaint.setShadowLayer(dp(3f), 0f, dp(1.5f), 0x66000000)
        canvas.drawText(CardCatalog.nameOf(cardNumber), w / 2, h * 0.815f, labelPaint)
        labelPaint.clearShadowLayer()

        // 稀有度标签（底部小胶囊）
        labelPaint.textSize = minOf(w, h) * 0.09f
        val text = rarity.label
        val tw = labelPaint.measureText(text)
        val padH = dp(7f)
        val padV = dp(3f)
        val lx = (w - tw) / 2 - padH
        val ly = h - dp(10f) - labelPaint.textSize - padV * 2
        val pill = RectF(lx, ly, lx + tw + padH * 2, ly + labelPaint.textSize + padV * 2)

        miscPaint.color = (rarity.color and 0x00FFFFFF) or 0x33000000
        canvas.drawRoundRect(pill, pill.height() / 2, pill.height() / 2, miscPaint)
        labelPaint.color = rarity.color
        canvas.drawText(text, w / 2, pill.top + padV + labelPaint.textSize * 0.82f, labelPaint)
    }

    // ---------------- 生成中剪影（通用，不剧透是哪张卡） ----------------

    private fun drawSilhouette(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        rect.set(0f, 0f, w, h)
        val r = radius

        // 底色：深黑卡
        bgPaint.shader = LinearGradient(
            0f, 0f, 0f, h,
            Color.parseColor("#0F1322"), Color.parseColor("#0A0D18"),
            Shader.TileMode.CLAMP
        )
        canvas.drawRoundRect(rect, r, r, bgPaint)

        // 生成进度：自下而上的暗光填充
        if (revealProgress > 0f) {
            val fillH = h * revealProgress
            miscPaint.color = Color.parseColor("#263049")
            val fill = RectF(0f, h - fillH, w, h)
            canvas.save()
            canvas.clipRect(fill)
            canvas.drawRoundRect(rect, r, r, miscPaint)
            canvas.restore()
        }

        // 流光斜带
        val bandX = -w + (w * 2.2f) * shimmerPhase
        miscPaint.shader = LinearGradient(
            bandX, 0f, bandX + w * 0.45f, h,
            Color.TRANSPARENT,
            Color.parseColor("#33FFFFFF"),
            Shader.TileMode.CLAMP
        )
        canvas.save()
        canvas.clipRect(rect)
        canvas.drawRect(rect, miscPaint)
        canvas.restore()
        miscPaint.shader = null

        // 虚线边框
        borderPaint.strokeWidth = dp(2f)
        borderPaint.color = Color.parseColor("#55608A")
        borderPaint.pathEffect = DashPathEffect(floatArrayOf(dp(6f), dp(5f)), 0f)
        canvas.drawRoundRect(rect, r, r, borderPaint)
        borderPaint.pathEffect = null

        // 中央问号
        textPaint.textSize = minOf(w, h) * 0.34f
        textPaint.color = Color.parseColor("#8E99C4")
        canvas.drawText("?", w / 2, h / 2 + textPaint.textSize * 0.3f, textPaint)

        // 底部提示
        labelPaint.textSize = minOf(w, h) * 0.085f
        labelPaint.color = Color.parseColor("#6B76A6")
        canvas.drawText("生成中…", w / 2, h - dp(12f), labelPaint)
    }

    // ---------------- 动画 ----------------

    fun startShimmer() {
        if (shimmerAnim != null) return
        shimmerAnim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1600
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.RESTART
            interpolator = LinearInterpolator()
            addUpdateListener { anim -> shimmerPhase = anim.animatedValue as Float }
            start()
        }
    }

    fun stopShimmer() {
        shimmerAnim?.cancel()
        shimmerAnim = null
    }

    override fun onDetachedFromWindow() {
        stopShimmer()
        super.onDetachedFromWindow()
    }

    private fun dp(v: Float): Float = v * resources.displayMetrics.density
}
