package me.hebin.focus.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import com.google.android.material.chip.Chip
import me.hebin.focus.R
import me.hebin.focus.data.CollectionRepository
import me.hebin.focus.data.FocusStats
import me.hebin.focus.databinding.ActivityStatsBinding
import me.hebin.focus.ui.view.CardView
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * 专注统计页：每日专注时间的年 / 月 / 日柱状图。
 *
 * - 年视图：12 根柱 = 各月专注分钟；点击月份下钻到月视图
 * - 月视图：N 根柱 = 每日专注分钟；点击日期下钻到日视图
 * - 日视图：24 根柱 = 每小时专注分钟；下方展示当天收集到的卡片
 * - 左右按钮跨年 / 跨月 / 跨日导航（不能切到未来）
 */
class StatsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityStatsBinding
    private lateinit var repo: CollectionRepository

    private enum class Mode { YEAR, MONTH, DAY }

    private var mode = Mode.MONTH

    /** 当前查看的日期锚点（取其年 / 月 / 日） */
    private var anchor = System.currentTimeMillis()

    private val timeFmt = SimpleDateFormat("HH:mm", Locale.getDefault())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        EdgeToEdge.enable(this)
        binding = ActivityStatsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        EdgeToEdge.pad(binding.root)

        repo = CollectionRepository.get(this)

        binding.btnBack.setOnClickListener { finish() }
        binding.btnPrev.setOnClickListener { shift(-1) }
        binding.btnNext.setOnClickListener { shift(1) }

        buildModeChips()
        render()
    }

    /** 视图粒度：年 / 月 / 日（默认月） */
    private fun buildModeChips() {
        val labels = listOf("年" to Mode.YEAR, "月" to Mode.MONTH, "日" to Mode.DAY)
        labels.forEachIndexed { i, (label, m) ->
            val chip = Chip(this).apply {
                text = label
                isCheckable = true
                id = View.generateViewId()
                isChecked = m == mode
                setOnCheckedChangeListener { _, checked ->
                    if (checked && mode != m) {
                        mode = m
                        render()
                    }
                }
            }
            binding.chipViewMode.addView(chip)
        }
    }

    private fun modeChip(mode: Mode): Chip? {
        val idx = listOf(Mode.YEAR, Mode.MONTH, Mode.DAY).indexOf(mode)
        return binding.chipViewMode.getChildAt(idx) as? Chip
    }

    /** 前 / 后导航（不能到未来）；render 里再根据边界禁用后按钮 */
    private fun shift(dir: Int) {
        anchor = when (mode) {
            Mode.YEAR -> FocusStats.addYears(anchor, dir)
            Mode.MONTH -> FocusStats.addMonths(anchor, dir)
            Mode.DAY -> FocusStats.addDays(anchor, dir)
        }
        render()
    }

    // ---------------- 渲染 ----------------

    private fun render() {
        val sessions = repo.sessionLogs()
        val byHour = FocusStats.minutesByHour(sessions)
        val now = System.currentTimeMillis()

        val from: Long
        val to: Long
        val dateText: String
        val hint: String
        val bars: List<FocusStats.Bucket>

        when (mode) {
            Mode.YEAR -> {
                val yearStart = FocusStats.startOfYear(anchor)
                from = yearStart
                to = FocusStats.addYears(yearStart, 1)
                dateText = "${cal(yearStart).get(Calendar.YEAR)} 年"
                hint = "每月专注分钟 · 点击柱子下钻到月"
                bars = FocusStats.monthlyBuckets(byHour, yearStart)
                binding.barChart.onBarTap = { i ->
                    anchor = FocusStats.addMonths(yearStart, i)
                    switchMode(Mode.MONTH)
                }
            }
            Mode.MONTH -> {
                val monthStart = FocusStats.startOfMonth(anchor)
                from = monthStart
                to = FocusStats.addMonths(monthStart, 1)
                val c = cal(monthStart)
                dateText = "${c.get(Calendar.YEAR)} 年 ${c.get(Calendar.MONTH) + 1} 月"
                hint = "每日专注分钟 · 点击柱子下钻到当天"
                bars = FocusStats.dailyBuckets(byHour, monthStart)
                binding.barChart.onBarTap = { i ->
                    anchor = FocusStats.addDays(monthStart, i)
                    switchMode(Mode.DAY)
                }
            }
            Mode.DAY -> {
                val dayStart = FocusStats.startOfDay(anchor)
                from = dayStart
                to = FocusStats.addDays(dayStart, 1)
                val c = cal(dayStart)
                dateText = "${c.get(Calendar.MONTH) + 1} 月 ${c.get(Calendar.DAY_OF_MONTH)} 日 · 周${FocusStats.weekdayLabel(dayStart)}"
                hint = "按小时的专注分钟分布"
                bars = FocusStats.hourlyBuckets(byHour, dayStart)
                binding.barChart.onBarTap = null // 日视图点击仅选中看数值
            }
        }

        // 顶部日期与导航按钮
        binding.textDate.text = dateText
        val prevLabel: String
        val nextLabel: String
        when (mode) {
            Mode.YEAR -> { prevLabel = "‹ 前一年"; nextLabel = "后一年 ›" }
            Mode.MONTH -> { prevLabel = "‹ 前一月"; nextLabel = "后一月 ›" }
            Mode.DAY -> { prevLabel = "‹ 前一天"; nextLabel = "后天 ›" }
        }
        binding.btnPrev.text = prevLabel
        binding.btnNext.text = nextLabel
        // 未来不可达：下一格起始时间已到当前所在格即禁用
        binding.btnNext.isEnabled = when (mode) {
            Mode.YEAR -> to <= FocusStats.startOfYear(now)
            Mode.MONTH -> to <= FocusStats.startOfMonth(now)
            Mode.DAY -> to <= FocusStats.startOfDay(now)
        }

        // 汇总三块
        val cardsInRange = repo.cardLogs().count { it.ts in from until to }
        binding.statValueMinutes.text = bars.sumOf { it.minutes }.toString()
        binding.statValueSessions.text = FocusStats.sessionCount(sessions, from, to).toString()
        binding.statValueCards.text = cardsInRange.toString()

        // 柱状图
        binding.textChartHint.text = hint
        binding.barChart.setData(bars)

        // 日视图：当天收集的卡片
        binding.cardSection.isVisible = mode == Mode.DAY
        if (mode == Mode.DAY) renderDayCards(from)
    }

    /** 日视图下方：当天收集到的卡片横滑展示（含获得时刻） */
    private fun renderDayCards(dayStart: Long) {
        val logs = FocusStats.cardsOfDay(repo.cardLogs(), dayStart)
        val c = cal(dayStart)
        binding.textCardsTitle.text =
            "${c.get(Calendar.MONTH) + 1} 月 ${c.get(Calendar.DAY_OF_MONTH)} 日 收集的卡片" +
                "（${logs.size} 张）"

        binding.cardsScroll.isVisible = logs.isNotEmpty()
        binding.textCardsEmpty.isVisible = logs.isEmpty()
        binding.cardsRow.removeAllViews()
        logs.forEach { log ->
            val wrapper = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = android.view.Gravity.CENTER_HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    dp(92), LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { marginEnd = dp(12) }
            }
            val card = CardView(this).apply {
                mode = CardView.Mode.FACE
                rarity = log.rarity
                cardNumber = log.cardId
                locked = false
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }
            val time = TextView(this).apply {
                text = timeFmt.format(Date(log.ts))
                setTextColor(androidx.core.content.ContextCompat.getColor(this@StatsActivity, R.color.textSecondary))
                textSize = 11f
                gravity = android.view.Gravity.CENTER
            }
            card.setOnClickListener { showCardDetail(log) }
            wrapper.addView(card)
            wrapper.addView(time)
            binding.cardsRow.addView(wrapper)
        }
    }

    /** 点击某张卡：弹出卡面详情（标题带获得时间） */
    private fun showCardDetail(log: CollectionRepository.CardLog) {
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_card_detail, null)
        val card = view.findViewById<CardView>(R.id.detailCard)
        card.mode = CardView.Mode.FACE
        card.rarity = log.rarity
        card.cardNumber = log.cardId
        card.locked = false

        val c = cal(log.ts)
        val title = "${c.get(Calendar.MONTH) + 1}月${c.get(Calendar.DAY_OF_MONTH)}日 ${timeFmt.format(Date(log.ts))} 收集"

        AlertDialog.Builder(this)
            .setTitle(title)
            .setView(view)
            .setPositiveButton("确认") { d, _ -> d.dismiss() }
            .show()

        view.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnShareCard).apply {
            isVisible = true
            setOnClickListener { ShareCard.share(this@StatsActivity, log.cardId, log.rarity) }
        }
    }

    /** 下钻切换粒度（同步 chip 选中态） */
    private fun switchMode(m: Mode) {
        mode = m
        modeChip(m)?.isChecked = true // 触发 listener，但 mode 已相同，不会重复 render
        render()
    }

    // ---------------- 工具 ----------------

    private fun cal(ms: Long): Calendar = Calendar.getInstance().apply { timeInMillis = ms }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
