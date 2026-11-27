package com.stockrec.core

import java.time.DayOfWeek
import java.time.LocalDate
import kotlin.math.abs

/**
 * 주봉 상승 추세선 채널 (Python recommender/trendline.py 와 동일).
 *  1) 상단선(지지선): 상승 추세 중 주봉 스윙 저점을 가장 많이 잇는 우상향 직선.
 *     추세선 시작부터 최근 조정 직전까지 주봉 종가가 선 아래로 3% 넘게 내려간 적이 없어야 함.
 *  2) 하단선: 상단선과 평행, 추세 기간 중 저점이 상단선을 가장 깊게 찔렀던 폭만큼 아래 (4~15%).
 *  3) 현재가가 두 선 사이 → 매수권, 상단선 위 2% 이내 → 상단 근접.
 */
enum class ChannelStatus(val label: String, val order: Int) {
    IN("매수권", 0), NEAR("상단 근접", 1), BELOW("하단 이탈", 2), ABOVE("추세선 위", 3)
}

data class ChannelParams(
    val lookbackWeeks: Int = 156,
    val pivotK: Int = 3,
    val recentWeeks: Int = 8,
    val minGapWeeks: Int = 6,
    val minSpanWeeks: Int = 26,
    val minRise: Double = 0.3,
    val closeTol: Double = 0.03,
    val touchTol: Double = 0.04,
    val minTouches: Int = 3,
    val width: Double? = null,      // null=자동, 아니면 현재 상단선 대비 비율
    val minWidth: Double = 0.04,
    val maxWidth: Double = 0.15,
    val nearTol: Double = 0.02,
)

data class Channel(
    val info: StockInfo,
    val status: ChannelStatus,
    val close: Double,
    val upper: Double,
    val lower: Double,
    /** 0=하단선, 1=상단선 */
    val position: Double,
    val width: Double,
    val touches: Int,
    val startDate: LocalDate,
    val pivot2Date: LocalDate,
    /** 현재 추세선 값 대비 연간 상승 폭 */
    val trendPerYear: Double,
    val recentHigh: Double,
    val fromRecentHigh: Double,
    /** 차트용 주봉 (lookback 구간) 과 직선 정보: upper(t) = startLow + slope*(t-a) */
    val weeks: List<Bar>,
    val a: Int,
    val slope: Double,
    val startLow: Double,
    val offset: Double,
) {
    fun upperAt(t: Int) = if (t < a) Double.NaN else startLow + slope * (t - a)
    fun lowerAt(t: Int) = upperAt(t) - offset
}

object TrendChannel {
    /** 일봉 → 주봉 (월요일 시작 주, date = 그 주 마지막 거래일) */
    fun weekly(bars: List<Bar>): List<Bar> =
        bars.sortedBy { it.date }
            .groupBy { it.date.with(DayOfWeek.MONDAY) }
            .toSortedMap()
            .values.map { w ->
                Bar(w.last().date, w.first().open, w.maxOf { it.high }, w.minOf { it.low }, w.last().close,
                    w.sumOf { it.volume }, Double.NaN)
            }

    fun fit(info: StockInfo, weeklyBars: List<Bar>, p: ChannelParams = ChannelParams()): Channel? {
        val w = weeklyBars.takeLast(p.lookbackWeeks)
        val n = w.size
        if (n < p.minSpanWeeks + p.recentWeeks) return null
        val low = DoubleArray(n) { w[it].low }
        val close = DoubleArray(n) { w[it].close }
        val k = p.pivotK
        val end = n - p.recentWeeks

        val pivots = (k until n - k).filter { i ->
            (i - k until i).all { low[i] < low[it] } && (i + 1..i + k).all { low[i] <= low[it] }
        }
        val cand = pivots.filter { it < end }

        var bestKey: Triple<Int, Int, Double>? = null
        var best: IntArray? = null
        var bestSlope = 0.0
        for ((ai, a) in cand.withIndex()) {
            if (n - 1 - a < p.minSpanWeeks) continue
            for (b in cand.subList(ai + 1, cand.size)) {
                if (b - a < p.minGapWeeks) continue
                val slope = (low[b] - low[a]) / (b - a)
                if (slope <= 0) continue
                if ((a until end).any { close[it] < (low[a] + slope * (it - a)) * (1 - p.closeTol) }) continue
                val upperNow = low[a] + slope * (n - 1 - a)
                if (upperNow / low[a] - 1 < p.minRise) continue
                val touches = pivots.count { q ->
                    q in a until end && (low[a] + slope * (q - a)).let { lq -> abs(low[q] - lq) / lq <= p.touchTol }
                }
                if (touches < p.minTouches) continue
                val key = Triple(touches, n - 1 - a, upperNow)
                if (bestKey == null || compareKey(key, bestKey) > 0) {
                    bestKey = key; best = intArrayOf(a, b, touches); bestSlope = slope
                }
            }
        }
        val (a, b, touches) = best ?: return null
        val slope = bestSlope
        val upperNow = low[a] + slope * (n - 1 - a)
        val width = p.width ?: run {
            var pen = Double.NEGATIVE_INFINITY
            for (t in a until end) pen = maxOf(pen, low[a] + slope * (t - a) - low[t])
            (pen / upperNow).coerceIn(p.minWidth, p.maxWidth)
        }
        val offset = width * upperNow
        val lowerNow = upperNow - offset
        val c = close[n - 1]
        val status = when {
            c < lowerNow -> ChannelStatus.BELOW
            c <= upperNow -> ChannelStatus.IN
            c <= upperNow * (1 + p.nearTol) -> ChannelStatus.NEAR
            else -> ChannelStatus.ABOVE
        }
        var recentHigh = Double.NEGATIVE_INFINITY
        for (t in end - 1 until n) recentHigh = maxOf(recentHigh, close[t])
        return Channel(
            info, status, c, upperNow, lowerNow, (c - lowerNow) / offset, width, touches,
            w[a].date, w[b].date, slope * 52 / upperNow, recentHigh, c / recentHigh - 1,
            w, a, slope, low[a], offset,
        )
    }

    private fun compareKey(x: Triple<Int, Int, Double>, y: Triple<Int, Int, Double>): Int =
        compareValuesBy(x, y, { it.first }, { it.second }, { it.third })

    /** 전체 종목 스캔 → 지정 상태만, 매수권 → 상단 근접 순, 접점 많은 순 */
    fun scan(
        series: List<StockSeries>,
        p: ChannelParams = ChannelParams(),
        statuses: Set<ChannelStatus> = setOf(ChannelStatus.IN, ChannelStatus.NEAR),
    ): List<Channel> =
        series.mapNotNull { s -> fit(s.info, weekly(s.bars), p)?.takeIf { it.status in statuses } }
            .sortedWith(compareBy<Channel> { it.status.order }.thenByDescending { it.touches }.thenByDescending { it.trendPerYear })
}
