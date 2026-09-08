package me.hebin.focus.ui.view

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import me.hebin.focus.data.Rarity

/**
 * 卡片自绘控件。Demo 阶段卡面用编号数字占位，后续美术资源到位后
 * 只需在 [drawFace] 里替换为图片绘制即可，外部接口不变。
 *
 * 模式：
 *  - FACE       正面（稀有度配色 + 编号），locked=true 时为图鉴未收集样式
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
    private val numberPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    private val miscPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()

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

    // ---------------- 正面 ----------------

    private fun drawFace(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        rect.set(0f, 0f, w, h)
        val r = radius

        if (locked) {
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

            numberPaint.textSize = minOf(w, h) * 0.34f
            numberPaint.color = Color.parseColor("#4A5370")
            canvas.drawText(cardNumber.toString(), w / 2, h / 2 + numberPaint.textSize * 0.3f, numberPaint)

            labelPaint.textSize = minOf(w, h) * 0.085f
            labelPaint.color = Color.parseColor("#3D4560")
            canvas.drawText("未收集", w / 2, h - dp(12f), labelPaint)
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

        // 边框
        borderPaint.strokeWidth = dp(if (rarity.ordinal >= Rarity.GOLD.ordinal) 3f else 2f)
        borderPaint.color = rarity.color
        canvas.drawRoundRect(rect, r, r, borderPaint)

        // 编号
        numberPaint.textSize = minOf(w, h) * 0.38f
        numberPaint.color = Color.WHITE
        numberPaint.setShadowLayer(dp(4f), 0f, dp(2f), 0x66000000)
        canvas.drawText(cardNumber.toString(), w / 2, h / 2 + numberPaint.textSize * 0.28f, numberPaint)
        numberPaint.clearShadowLayer()

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

    // ---------------- 剪影 ----------------

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
        numberPaint.textSize = minOf(w, h) * 0.34f
        numberPaint.color = Color.parseColor("#8E99C4")
        canvas.drawText("?", w / 2, h / 2 + numberPaint.textSize * 0.3f, numberPaint)

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
