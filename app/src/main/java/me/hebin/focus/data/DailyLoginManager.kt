package me.hebin.focus.data

import android.content.Context
import android.os.SystemClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/**
 * 每日登录奖励：每天首次打开 App 送一张随机卡，连续登录天数越多卡越好。
 *
 * 防改本地时间策略：
 *  1. 发奖时间必须来自网络校验（三个时间源容错：淘宝 / worldtimeapi / 任意 HTTPS Date 头）；
 *  2. 记录「最近一次可信网络时间 + 当时 elapsedRealtime」快照，离线时用单调时钟推算，
 *     重启后（elapsedRealtime 归零）无法推算 → 暂缓发奖，等联网；
 *  3. 网络时间比上次记录早超过 60 秒（时间回拨）→ 拒绝发奖。
 */
object DailyLoginManager {

    sealed class Result {
        /** 领取成功 */
        data class Claimed(val cardId: Int, val rarity: Rarity, val streak: Int) : Result()

        /** 今天已领过 */
        data object AlreadyClaimed : Result()

        /** 无法校验时间（离线且无快照），等联网后自动重试 */
        data object Deferred : Result()

        /** 时间校验异常（疑似篡改/回拨），拒绝发奖 */
        data object Rejected : Result()
    }

    /** 主入口：校验时间并发每日奖励（suspend，内部已切 IO 线程） */
    suspend fun checkAndClaim(context: Context): Result {
        val repo = CollectionRepository.get(context)

        var now = fetchNetworkTime()
        var verified = true
        if (now == null) {
            // 离线：尝试用上次可信网络时间 + 单调时钟推算
            val lastNet = repo.lastNetworkTimeMs
            val lastElapsed = repo.lastElapsedRealtimeMs
            val elapsed = SystemClock.elapsedRealtime()
            if (lastNet > 0 && lastElapsed > 0 && elapsed >= lastElapsed) {
                now = lastNet + (elapsed - lastElapsed)
                verified = false
            } else {
                return Result.Deferred // 重启过或没有参照，等联网
            }
        }

        // 防回拨：网络时间不允许比上次记录的可信时间早 60s 以上
        if (repo.lastNetworkTimeMs > 0 && now < repo.lastNetworkTimeMs - 60_000) {
            return Result.Rejected
        }

        val today = localEpochDay(now)
        val last = repo.lastClaimDay

        if (last == today) {
            snapshotClock(repo, now, verified)
            return Result.AlreadyClaimed
        }
        if (last > today) return Result.Rejected // 时间倒退，异常

        // 连续登录：昨天领过 → streak+1；断了 → 从 1 重新计
        val streak = if (last > 0 && today - last == 1L) repo.loginStreak + 1 else 1

        // 发卡：streak 越长，等效专注分钟越高，稀有度概率越高
        val drop = DropEngine.rollForLogin(streak)
        repo.addCard(drop.cardId, drop.rarity)
        repo.lastClaimDay = today
        repo.loginStreak = streak
        snapshotClock(repo, now, verified)
        return Result.Claimed(drop.cardId, drop.rarity, streak)
    }

    /** 只有真正联网校验的时间才更新快照 */
    private fun snapshotClock(repo: CollectionRepository, netTime: Long, verified: Boolean) {
        if (verified) {
            repo.lastNetworkTimeMs = netTime
            repo.lastElapsedRealtimeMs = SystemClock.elapsedRealtime()
        }
    }

    /**
     * 联网获取可信时间。全部走 HTTPS（targetSdk 28+ 禁明文 HTTP）。
     * 返回 null 表示全部时间源失败（离线）。
     */
    suspend fun fetchNetworkTime(): Long? = withContext(Dispatchers.IO) {
        taobaoTime() ?: worldTimeApi() ?: httpsDateHeader()
    }

    /** 淘宝时间接口（国内可达性好）：{"data":{"t":"1694156400000"}} */
    private fun taobaoTime(): Long? = runCatching {
        val conn = URL("https://acs.m.taobao.com/gw/mtop.common.getTimestamp/").openConnection() as HttpURLConnection
        conn.connectTimeout = 5000
        conn.readTimeout = 5000
        try {
            val body = conn.inputStream.bufferedReader().use(BufferedReader::readText)
            JSONObject(body).getJSONObject("data").getString("t").toLong()
        } finally {
            conn.disconnect()
        }
    }.getOrNull()

    /** worldtimeapi.org：{"unixtime":1694156400} */
    private fun worldTimeApi(): Long? = runCatching {
        val conn = URL("https://worldtimeapi.org/api/timezone/Asia/Shanghai").openConnection() as HttpURLConnection
        conn.connectTimeout = 5000
        conn.readTimeout = 5000
        try {
            val body = conn.inputStream.bufferedReader().use(BufferedReader::readText)
            JSONObject(body).getLong("unixtime") * 1000L
        } finally {
            conn.disconnect()
        }
    }.getOrNull()

    /** 兜底：任意 HTTPS 响应的 Date 头（秒级精度，做每日判断足够） */
    private fun httpsDateHeader(): Long? = runCatching {
        val conn = URL("https://www.baidu.com").openConnection() as HttpURLConnection
        conn.connectTimeout = 5000
        conn.readTimeout = 5000
        conn.requestMethod = "HEAD"
        try {
            val date = conn.getHeaderField("Date") ?: return@runCatching null
            val fmt = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US)
            fmt.timeZone = TimeZone.getTimeZone("GMT")
            fmt.parse(date)?.time
        } finally {
            conn.disconnect()
        }
    }.getOrNull()

    /** 网络时间(ms) → 本地时区的「天序号」，用于连续登录判断 */
    fun localEpochDay(ms: Long): Long {
        val cal = Calendar.getInstance()
        cal.timeInMillis = ms
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        return cal.timeInMillis / 86_400_000L
    }
}
