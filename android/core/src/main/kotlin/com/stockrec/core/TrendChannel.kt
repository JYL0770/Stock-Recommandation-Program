package com.stockrec.core

import java.time.DayOfWeek
import java.time.LocalDate
import kotlin.math.abs

/**
 * 상승 추세선 채널 (Python recommender/trendline.py 와 동일).
 *  1) 하단선(지지선): 상승 추세 중 스윙 저점을 가장 많이 잇는 우상향 직선 (연 20% 이상, 접점 3회 이상).
 *     추세선 시작부터 최근 조정 직전까지 종가가 선 아래로 허용폭 넘게 내려간 적이 없어야 함.
 *     허용폭 = max(선 값의 3%, 차트 높이의 2%) — 눈으로 볼 때 닿아 보이는 정도
 *  2) 상단선: 하단선과 평행, 하단선 값의 10% 위
 *  3) 마지막 종가가 하단선 ~ 상단선 사이면 그 차트는 매수권
 * 매수권 = 종목 일봉·주봉 + 지수 일봉·주봉 4개 차트 모두 매수권 ([BuyZone]).
 */
enum class ChannelStatus(val label: String, val order: Int) {
    IN("매수권", 0), BELOW("지지선 이탈", 1), ABOVE("채널 위", 2)
}

data class ChannelParams(
    val lookback: Int = 156,
    val pivotK: Int = 3,
    val recent: Int = 8,
    val minGap: Int = 6,
    val minSpan: Int = 26,
    val minRise: Double = 0.3,
    val minTrendPerYear: Double = 0.2,
    val barsPerYear: Int = 52,
    val tolRel: Double = 0.03,
    val tolChart: Double = 0.02,
    val minTouches: Int = 3,
    val width: Double = 0.10,
    val pullback: Int = 26,
) {
    companion object {
        /** 주봉: 최근 3년, 반년 이상 이어진 추세 */
        val WEEKLY = ChannelParams()
        /** 일봉: 최근 1년, 3개월 이상 이어진 추세 */
        val DAILY = ChannelParams(lookback = 250, pivotK = 5, recent = 10, minGap = 10, minSpan = 60, minRise = 0.1,
            barsPerYear = 252, pullback = 120)
    }
}

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
    val touches: Int,
    val startDate: LocalDate,
    val pivot2Date: LocalDate,
    /** 현재 지지선 값 대비 연간 상승 폭 */
    val trendPerYear: Double,
    val recentHigh: Double,
    val fromRecentHigh: Double,
    /** 차트용 봉(일봉 또는 주봉)과 직선: support(t) = startLow + slope*(t-a) */
    val bars: List<Bar>,
    val a: Int,
    val slope: Double,
    val startLow: Double,
    val offset: Double,
    /** 접점이 된 저점의 봉 인덱스 */
    val touchIdx: List<Int>,
) {
    fun supportAt(t: Int) = if (t < a) Double.NaN else startLow + slope * (t - a)
    fun upperAt(t: Int) = supportAt(t) + offset
    val ok get() = status == ChannelStatus.IN
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

    fun fit(info: StockInfo, allBars: List<Bar>, p: ChannelParams = ChannelParams.WEEKLY): Channel? {
        val w = allBars.takeLast(p.lookback)
        val n = w.size
        if (n < p.minSpan + p.recent) return null
        val low = DoubleArray(n) { w[it].low }
        val close = DoubleArray(n) { w[it].close }
        val chartTol = p.tolChart * (w.maxOf { it.high } - w.minOf { it.low })
        fun tol(v: Double) = maxOf(p.tolRel * v, chartTol)
        val k = p.pivotK
        val end = n - p.recent

        val pivots = (k until n - k).filter { i ->
            (i - k until i).all { low[i] < low[it] } && (i + 1..i + k).all { low[i] <= low[it] }
        }
        val cand = pivots.filter { it < end }

        var bestTouches = -1
        var bestSup = 0.0
        var best: Triple<Int, Int, Double>? = null
        for ((ai, a) in cand.withIndex()) {
            if (n - 1 - a < p.minSpan) continue
            for (b in cand.subList(ai + 1, cand.size)) {
                if (b - a < p.minGap) continue
                val slope = (low[b] - low[a]) / (b - a)
                if (slope <= 0) continue
                val supNow = low[a] + slope * (n - 1 - a)
                if (supNow / low[a] - 1 < p.minRise || slope * p.barsPerYear / supNow < p.minTrendPerYear) continue
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
        val c = close[n - 1]
        val status = when {
            c < sup -> ChannelStatus.BELOW
            c <= upper -> ChannelStatus.IN
            else -> ChannelStatus.ABOVE
        }
        var recentHigh = Double.NEGATIVE_INFINITY
        for (t in maxOf(0, n - p.pullback) until n) recentHigh = maxOf(recentHigh, close[t])
        val touchIdx = pivots.filter { q -> q in a until end && (low[a] + slope * (q - a)).let { lq -> abs(low[q] - lq) <= tol(lq) } }
        return Channel(
            info, status, c, sup, upper, (c - sup) / (upper - sup), bestTouches,
            w[a].date, w[b].date, slope * p.barsPerYear / sup, recentHigh, c / recentHigh - 1,
            w, a, slope, low[a], upper - sup, touchIdx,
        )
    }

    /** 일봉 → (일봉 채널, 주봉 채널) */
    fun daily(info: StockInfo, bars: List<Bar>, width: Double = 0.10): Channel? =
        fit(info, bars.sortedBy { it.date }, ChannelParams.DAILY.copy(width = width))

    fun weeklyChannel(info: StockInfo, bars: List<Bar>, width: Double = 0.10): Channel? =
        fit(info, weekly(bars), ChannelParams.WEEKLY.copy(width = width))
}

// ---------------------------------------------------------------------- 4개 차트 매수권 판정
enum class Check(val label: String) {
    STOCK_DAILY("종목 일봉"), STOCK_WEEKLY("종목 주봉"), INDEX_DAILY("지수 일봉"), INDEX_WEEKLY("지수 주봉")
}

/** 지수 하나의 일봉·주봉 채널 */
data class IndexState(val market: String, val series: StockSeries, val daily: Channel?, val weekly: Channel?) {
    val ok get() = daily?.ok == true && weekly?.ok == true
}

data class BuyZoneResult(
    val info: StockInfo,
    val close: Double,
    val channels: Map<Check, Channel?>,
) {
    fun ok(c: Check) = channels[c]?.ok == true
    val nOk get() = Check.entries.count(::ok)
    /** 4개 차트 모두 매수권 */
    val buy get() = nOk == 4
    /** 종목 일봉·주봉만 매수권 (지수 조건 제외) */
    val stockOk get() = ok(Check.STOCK_DAILY) && ok(Check.STOCK_WEEKLY)
}

object BuyZone {
    /** 종목 시장 → 비교할 지수 (모르면 KOSPI) */
    fun indexFor(market: String) = if (market == "KOSDAQ") "KOSDAQ" else "KOSPI"

    fun index(market: String, series: StockSeries, width: Double = 0.10) = IndexState(
        market, series,
        TrendChannel.daily(series.info, series.bars, width),
        TrendChannel.weeklyChannel(series.info, series.bars, width),
    )

    fun evaluate(s: StockSeries, index: IndexState?, width: Double = 0.10): BuyZoneResult = BuyZoneResult(
        s.info, s.bars.maxBy { it.date }.close,
        mapOf(
            Check.STOCK_DAILY to TrendChannel.daily(s.info, s.bars, width),
            Check.STOCK_WEEKLY to TrendChannel.weeklyChannel(s.info, s.bars, width),
            Check.INDEX_DAILY to index?.daily,
            Check.INDEX_WEEKLY to index?.weekly,
        ),
    )

    /** 매수권 → 충족 개수 → 주봉 접점 많은 순 */
    fun sort(list: List<BuyZoneResult>): List<BuyZoneResult> = list.sortedWith(
        compareByDescending<BuyZoneResult> { it.buy }.thenByDescending { it.nOk }
            .thenByDescending { it.channels[Check.STOCK_WEEKLY]?.touches ?: -1 }
    )

    /** 데모용: 종목들의 동일가중 지수 (각 종목 첫 종가=1000 으로 맞춘 뒤 날짜별 평균) */
    fun makeIndex(name: String, series: List<StockSeries>): StockSeries {
        val sums = sortedMapOf<LocalDate, DoubleArray>()   // o,h,l,c,count
        for (s in series) {
            val sorted = s.bars.sortedBy { it.date }
            val base = sorted.first().close
            for (b in sorted) {
                val acc = sums.getOrPut(b.date) { DoubleArray(5) }
                acc[0] += b.open / base * 1000; acc[1] += b.high / base * 1000
                acc[2] += b.low / base * 1000; acc[3] += b.close / base * 1000; acc[4] += 1.0
            }
        }
        return StockSeries(StockInfo(name, name, "INDEX"), sums.map { (d, a) ->
            Bar(d, a[0] / a[4], a[1] / a[4], a[2] / a[4], a[3] / a[4], 0.0)
        })
    }
}
