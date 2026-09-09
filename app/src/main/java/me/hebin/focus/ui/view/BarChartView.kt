package me.hebin.focus.ui.view

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import android.view.animation.DecelerateInterpolator
import androidx.core.content.ContextCompat
import me.hebin.focus.R
import me.hebin.focus.data.FocusStats
import java.lang.Math.ceil

/**
 * 简易柱状图（Canvas 自绘，专注统计页专用）。
 *
 * - [setData] 后柱子从 0 弹到目标高度（约 240ms 减速曲线）
 * - 点击柱子高亮并回调 [onBarTap]，柱顶气泡显示具体分钟数
 * - 标签自动抽稀（最多约 12 个），0 值柱画矮灰条占位
 */
class BarChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    /** 一根柱子的展示数据（label 为 x 轴文字） */
    private data class Bar(val label: String, val value: Int)

    private var bars: List<Bar> = emptyList()
    private var max = 1

    /** 当前选中柱下标；-1 = 无 */
    private var selected = -1

    /** 入场动画进度 0..1 */
    private var grow = 1f

    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = dp(1f) }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = sp(10f)
    }
    private val valuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = sp(10f)
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    private val bubblePaint = Paint(Paint.ANTI_ALIAS_FLAG)

    /** 点击柱子回调（下钻 / 选中查看） */
    var onBarTap: ((index: Int) -> Unit)? = null

    fun setData(buckets: List<FocusStats.Bucket>, animate: Boolean = true) {
        bars = buckets.map { Bar(it.label, it.minutes) }
        max = (bars.maxOfOrNull { it.value } ?: 0).coerceAtLeast(1)
        selected = -1
        if (animate && bars.isNotEmpty()) {
            grow = 0f
            ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 240
                interpolator = DecelerateInterpolator()
                addUpdateListener { grow = it.animatedValue as Float; invalidate() }
                start()
            }
        } else {
            grow = 1f
        }
        invalidate()
    }

    /** 选中某根柱子（不触发回调）；越界忽略 */
    fun select(index: Int) {
        if (index in bars.indices) {
            selected = index
            invalidate()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (bars.isEmpty()) return

        val textPrimary = ContextCompat.getColor(context, R.color.textSecondary)
        val track = ContextCompat.getColor(context, R.color.track)
        val accent = ContextCompat.getColor(context, R.color.primary)
        val accentDim = ContextCompat.getColor(context, R.color.secondary)

        val labelH = dp(18f)
        val padH = dp(12f)
        val chartBottom = height - labelH
        val chartTop = dp(6f)
        val chartH = (chartBottom - chartTop).coerceAtLeast(dp(1f))

        // 底部基线
        linePaint.color = track
        canvas.drawLine(padH, chartBottom, width - padH, chartBottom, linePaint)

        val slot = (width - padH * 2) / bars.size.toFloat()
        val barW = (slot * 0.62f).coerceAtMost(dp(22f))
        val maxBarH = chartH - dp(4f)
        // 标签抽稀：最多约 12 个
        val labelStep = (ceil(bars.size / 12.0).toInt()).coerceAtLeast(1)
        val radius = dp(3f)

        bars.forEachIndexed { i, b ->
            val cx = padH + slot * (i + 0.5f)
            val left = cx - barW / 2
            val top: Float
            if (b.value <= 0) {
                // 0 值：矮灰条占位
                top = chartBottom - dp(2f)
                trackPaint.color = track
                canvas.drawRoundRect(
                    RectF(left, top, left + barW, chartBottom), radius, radius, trackPaint
                )
            } else {
                val h = maxBarH * (b.value.toFloat() / max) * grow
                top = chartBottom - h
                barPaint.color = if (i == selected) accent else accentDim
                if (i == selected) barPaint.alpha = 255 else barPaint.alpha = 190
                canvas.drawRoundRect(
                    RectF(left, top, left + barW, chartBottom), radius, radius, barPaint
                )
                barPaint.alpha = 255
            }

            // x 轴标签（抽稀 + 最后一根兜底显示）
            if (i % labelStep == 0 || i == bars.size - 1) {
                labelPaint.color = if (i == selected) accent else textPrimary
                canvas.drawText(b.label, cx, chartBottom + dp(13f), labelPaint)
            }

            // 选中柱：柱顶气泡显示分钟数
            if (i == selected && b.value > 0) {
                val text = "${b.value}分"
                valuePaint.color = textPrimary
                val tw = valuePaint.measureText(text)
                val bx = (cx - tw / 2 - dp(5f)).coerceAtLeast(0f)
                val by = (top - dp(18f)).coerceAtLeast(0f)
                bubblePaint.color = track
                canvas.drawRoundRect(
                    RectF(bx, by, bx + tw + dp(10f), by + dp(14f)), dp(7f), dp(7f), bubblePaint
                )
                canvas.drawText(
                    text, bx + dp(5f) + tw / 2, by + dp(11f), valuePaint
                )
            }
        }
    }

    override fun onTouchEvent(event: android.view.MotionEvent): Boolean {
        if (event.action == android.view.MotionEvent.ACTION_DOWN && bars.isNotEmpty()) {
            val padH = dp(12f)
            val slot = (width - padH * 2) / bars.size.toFloat()
            val idx = ((event.x - padH) / slot).toInt()
            if (idx in bars.indices) {
                selected = idx
                invalidate()
                performClick()
                onBarTap?.invoke(idx)
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private fun dp(v: Float): Float = v * resources.displayMetrics.density
    private fun sp(v: Float): Float = v * resources.displayMetrics.scaledDensity
}
