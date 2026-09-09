package me.hebin.focus.data

import java.util.Calendar

/**
 * 专注统计聚合工具（本地时区）。
 *
 * 一场专注的分钟数按 [startedAt, startedAt + minutes) 时间区间均匀铺到各个整点小时，
 * 年 / 月 / 日柱状图都从"小时桶"逐级求和，保证三个视图的数字互相自洽。
 * 全部在内存中对 sessionLog / cardLog（各上限 2000 条）做一次性聚合，无需建索引。
 */
object FocusStats {

    /** 一根柱子：x 轴标签 + 分钟数 */
    data class Bucket(val label: String, val minutes: Int)

    // ---------- 日期工具（本地时区） ----------

    private fun cal(ms: Long): Calendar = Calendar.getInstance().apply { timeInMillis = ms }

    /** 当天 00:00 */
    fun startOfDay(ms: Long): Long = cal(ms).apply {
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    /** 当月 1 号 00:00 */
    fun startOfMonth(ms: Long): Long = startOfDay(ms).let { startOfDay(cal(it).apply { set(Calendar.DAY_OF_MONTH, 1) }.timeInMillis) }

    /** 当年 1 月 1 号 00:00 */
    fun startOfYear(ms: Long): Long = startOfDay(ms).let { startOfDay(cal(it).apply { set(Calendar.MONTH, Calendar.JANUARY); set(Calendar.DAY_OF_MONTH, 1) }.timeInMillis) }

    /** 后移 n 天 / n 月 / n 年 */
    fun addDays(ms: Long, n: Int): Long = cal(ms).apply { add(Calendar.DAY_OF_MONTH, n) }.timeInMillis
    fun addMonths(ms: Long, n: Int): Long = cal(ms).apply { add(Calendar.MONTH, n) }.timeInMillis
    fun addYears(ms: Long, n: Int): Long = cal(ms).apply { add(Calendar.YEAR, n) }.timeInMillis

    /** 某天所在月有几天 */
    fun daysInMonth(monthStart: Long): Int = cal(monthStart).getActualMaximum(Calendar.DAY_OF_MONTH)

    /** 某天是星期几（周一~周日 返回 一/二/.../日） */
    fun weekdayLabel(dayStart: Long): String {
        val names = arrayOf("日", "一", "二", "三", "四", "五", "六")
        return names[cal(dayStart).get(Calendar.DAY_OF_WEEK) - 1]
    }

    // ---------- 分钟聚合 ----------

    /**
     * 全部专注记录按整点小时铺开。
     * @return key = 该小时的 00:00 时刻(ms)，value = 分钟数（可能带小数，展示时四舍五入）
     */
    fun minutesByHour(sessions: List<CollectionRepository.SessionLog>): Map<Long, Double> {
        val out = HashMap<Long, Double>()
        for (s in sessions) {
            if (s.minutes <= 0) continue
            var t = s.startedAt
            val end = s.startedAt + s.minutes * 60_000L
            while (t < end) {
                val hourStart = cal(t).apply {
                    set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
                }.timeInMillis
                val hourEnd = cal(hourStart).apply { add(Calendar.HOUR_OF_DAY, 1) }.timeInMillis
                val seg = minOf(end, hourEnd) - t
                out[hourStart] = (out[hourStart] ?: 0.0) + seg / 60_000.0
                t += seg
            }
        }
        return out
    }

    /** 某天各小时（0..23）的分钟数；日视图 24 根柱 */
    fun hourlyBuckets(byHour: Map<Long, Double>, dayStart: Long): List<Bucket> {
        val next = addDays(dayStart, 1)
        val buckets = DoubleArray(24)
        byHour.forEach { (hourStart, v) ->
            if (hourStart in dayStart until next) {
                val h = cal(hourStart).get(Calendar.HOUR_OF_DAY)
                buckets[h] += v
            }
        }
        return buckets.mapIndexed { h, v -> Bucket(h.toString(), Math.round(v).toInt()) }
    }

    /** 某月每天（1 号起）的分钟数；月视图 N 根柱 */
    fun dailyBuckets(byHour: Map<Long, Double>, monthStart: Long): List<Bucket> {
        val days = daysInMonth(monthStart)
        val m = cal(monthStart)
        val sums = DoubleArray(days)
        byHour.forEach { (hourStart, v) ->
            val c = cal(hourStart)
            if (c.get(Calendar.YEAR) == m.get(Calendar.YEAR) &&
                c.get(Calendar.MONTH) == m.get(Calendar.MONTH)
            ) {
                sums[c.get(Calendar.DAY_OF_MONTH) - 1] += v
            }
        }
        return sums.mapIndexed { i, v -> Bucket((i + 1).toString(), Math.round(v).toInt()) }
    }

    /** 某年各月（1..12 月）的分钟数；年视图 12 根柱 */
    fun monthlyBuckets(byHour: Map<Long, Double>, yearStart: Long): List<Bucket> {
        val y = cal(yearStart).get(Calendar.YEAR)
        val sums = DoubleArray(12)
        byHour.forEach { (hourStart, v) ->
            val c = cal(hourStart)
            if (c.get(Calendar.YEAR) == y) sums[c.get(Calendar.MONTH)] += v
        }
        return sums.mapIndexed { i, v -> Bucket("${i + 1}月", Math.round(v).toInt()) }
    }

    /** 某个时间范围内（[from, to) 按开始时刻归属）完成的专注次数 */
    fun sessionCount(sessions: List<CollectionRepository.SessionLog>, from: Long, to: Long): Int =
        sessions.count { it.startedAt in from until to }

    // ---------- 卡片按天 ----------

    /** 某天（[dayStart, 次日) 按 ts 归属）获得的卡片，时间正序 */
    fun cardsOfDay(logs: List<CollectionRepository.CardLog>, dayStart: Long): List<CollectionRepository.CardLog> {
        val next = addDays(dayStart, 1)
        return logs.filter { it.ts in dayStart until next }
    }
}
