package com.stockrec.core

import java.time.DayOfWeek
import java.time.LocalDate
import kotlin.math.abs

/**
 * 주봉 상승 추세선 채널 (Python recommender/trendline.py 와 동일).
 *  1) 하단선(지지선): 상승 추세 중 주봉 스윙 저점을 가장 많이 잇는 우상향 직선 (연 20% 이상 상승).
 *     추세선 시작부터 최근 조정 직전까지 주봉 종가가 선 아래로 허용폭 넘게 내려간 적이 없어야 함.
 *     허용폭 = max(선 값의 3%, 차트 높이의 2%) — 눈으로 볼 때 닿아 보이는 정도
 *  2) 상단선: 하단선과 평행, 하단선 값의 10% 위
 *  3) 현재가가 두 선 사이면 매수권
 */
enum class ChannelStatus(val label: String, val order: Int) {
    IN("매수권", 0), BELOW("지지선 이탈", 1), ABOVE("채널 위", 2)
}

data class ChannelParams(
    val lookbackWeeks: Int = 156,
    val pivotK: Int = 3,
    val recentWeeks: Int = 8,
    val minGapWeeks: Int = 6,
    val minSpanWeeks: Int = 26,
    val minRise: Double = 0.3,
    val minTrendPerYear: Double = 0.2,
    val tolRel: Double = 0.03,
    val tolChart: Double = 0.02,
    val minTouches: Int = 3,
    val width: Double = 0.10,
    val pullbackWeeks: Int = 26,
)

data class Channel(
    val info: StockInfo,
    val status: ChannelStatus,
    val close: Double,
    /** 하단선 (지지선) 현재 값 */
    val support: Double,
    /** 상단선 현재 값 */
    val upper: Double,
    /** 0=하단선, 1=상단선 */
    val position: Double,
    val tolerance: Double,
    val touches: Int,
    val startDate: LocalDate,
    val pivot2Date: LocalDate,
    /** 현재 지지선 값 대비 연간 상승 폭 */
    val trendPerYear: Double,
    val recentHigh: Double,
    val fromRecentHigh: Double,
    /** 차트용 주봉과 직선: support(t) = startLow + slope*(t-a) */
    val weeks: List<Bar>,
    val a: Int,
    val slope: Double,
    val startLow: Double,
    val offset: Double,
    /** 접점이 된 저점의 주봉 인덱스 */
    val touchIdx: List<Int>,
) {
    fun supportAt(t: Int) = if (t < a) Double.NaN else startLow + slope * (t - a)
    fun upperAt(t: Int) = supportAt(t) + offset
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
        val chartTol = p.tolChart * (w.maxOf { it.high } - w.minOf { it.low })
        fun tol(v: Double) = maxOf(p.tolRel * v, chartTol)
        val k = p.pivotK
        val end = n - p.recentWeeks

        val pivots = (k until n - k).filter { i ->
            (i - k until i).all { low[i] < low[it] } && (i + 1..i + k).all { low[i] <= low[it] }
        }
        val cand = pivots.filter { it < end }

        var bestTouches = -1
        var bestSup = 0.0
        var best: Triple<Int, Int, Double>? = null
        for ((ai, a) in cand.withIndex()) {
            if (n - 1 - a < p.minSpanWeeks) continue
            for (b in cand.subList(ai + 1, cand.size)) {
                if (b - a < p.minGapWeeks) continue
                val slope = (low[b] - low[a]) / (b - a)
                if (slope <= 0) continue
                val supNow = low[a] + slope * (n - 1 - a)
                if (supNow / low[a] - 1 < p.minRise || slope * 52 / supNow < p.minTrendPerYear) continue
                if ((a until end).any { t -> (low[a] + slope * (t - a)).let { l -> close[t] < l - tol(l) } }) continue
                val touches = pivots.count { q ->
                    q in a until end && (low[a] + slope * (q - a)).let { lq -> abs(low[q] - lq) <= tol(lq) }
                }
                if (touches < p.minTouches) continue
                if (touches > bestTouches || (touches == bestTouches && supNow > bestSup)) {
                    bestTouches = touches; bestSup = supNow; best = Triple(a, b, slope)
                }
            }
        }
        val (a, b, slope) = best ?: return null
        val sup = low[a] + slope * (n - 1 - a)
        val upper = sup * (1 + p.width)
        val tNow = tol(sup)
        val c = close[n - 1]
        val status = when {
            c < sup - tNow -> ChannelStatus.BELOW
            c <= upper -> ChannelStatus.IN
            else -> ChannelStatus.ABOVE
        }
        var recentHigh = Double.NEGATIVE_INFINITY
        for (t in maxOf(0, n - p.pullbackWeeks) until n) recentHigh = maxOf(recentHigh, close[t])
        val touchIdx = pivots.filter { q -> q in a until end && (low[a] + slope * (q - a)).let { lq -> abs(low[q] - lq) <= tol(lq) } }
        return Channel(
            info, status, c, sup, upper, (c - sup) / (upper - sup), tNow, bestTouches,
            w[a].date, w[b].date, slope * 52 / sup, recentHigh, c / recentHigh - 1,
            w, a, slope, low[a], upper - sup, touchIdx,
        )
    }

    /** 전체 종목 스캔 → 지정 상태만, 접점 많은 순 → 하단선에 가까운 순 */
    fun scan(
        series: List<StockSeries>,
        p: ChannelParams = ChannelParams(),
        statuses: Set<ChannelStatus> = setOf(ChannelStatus.IN),
    ): List<Channel> =
        sort(series.mapNotNull { s -> fit(s.info, weekly(s.bars), p)?.takeIf { it.status in statuses } })

    fun sort(list: List<Channel>): List<Channel> =
        list.sortedWith(compareBy<Channel> { it.status.order }.thenByDescending { it.touches }.thenBy { it.position })
}
