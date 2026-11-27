package com.stockrec.core

import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.pow
import kotlin.math.sqrt

data class BacktestPeriod(
    val start: LocalDate,
    val end: LocalDate,
    val strategy: Double,
    val benchmark: Double,
    val turnover: Double,
    val picks: List<String>,
)

data class BacktestResult(
    val periods: List<BacktestPeriod>,
    /** 누적 수익 곡선: (날짜, 전략, 벤치마크), 시작=1 */
    val equity: List<Triple<LocalDate, Double, Double>>,
    val years: Double,
    val cagr: Double,
    val benchCagr: Double,
    val mdd: Double,
    val benchMdd: Double,
    val winRate: Double,
    val sharpe: Double,
    val avgTurnover: Double,
)

/**
 * Python recommender/backtest.py 와 동일한 간이 백테스트.
 * 매 리밸런싱일에 그날까지의 지표만으로 상위 N개 동일비중 매수 → 다음 리밸런싱일까지 보유.
 * 벤치마크 = 필터 통과 후보 전체 동일비중. 밸류 팩터는 과거 재무 데이터가 없어 제외.
 */
object Backtest {
    fun run(
        features: List<FeatureSeries>,
        weights: Map<Factor, Double>,
        filters: Filters = Filters(),
        topN: Int = 10,
        holdDays: Int = 20,
        cost: Double = 0.003,
    ): BacktestResult? {
        val w = weights.filterKeys { it != Factor.VALUE }
        val dates = features.flatMap { fs -> fs.bars.map { it.date } }.toSortedSet().toList()
        if (dates.isEmpty()) return null
        val first = dates[minOf(dates.size - 1, maxOf(filters.minDays, 250))]
        val rebal = dates.filter { it >= first }.filterIndexed { i, _ -> i % holdDays == 0 }

        // 날짜 → 종가 / 스냅샷 조회용 인덱스
        val idxByTicker = features.associate { fs -> fs.info.ticker to fs.bars.withIndex().associate { (i, b) -> b.date to i } }
        val byTicker = features.associateBy { it.info.ticker }
        fun close(t: String, d: LocalDate): Double {
            val i = idxByTicker[t]?.get(d) ?: return Double.NaN
            return byTicker.getValue(t).bars[i].close
        }

        val periods = mutableListOf<BacktestPeriod>()
        var prev = emptySet<String>()
        for (k in 0 until rebal.size - 1) {
            val d0 = rebal[k]; val d1 = rebal[k + 1]
            val snaps = features.mapNotNull { fs -> idxByTicker.getValue(fs.info.ticker)[d0]?.let { fs.snapshotAt(it) } }
            val cand = Scoring.applyFilters(snaps, filters)
            if (cand.size < topN) continue
            val scored = Scoring.factorScores(cand).map { it.copy(score = Scoring.composite(it.factors, w)) }
            val picks = scored.filter { !it.score.isNaN() }.sortedByDescending { it.score }.take(topN).map { it.snap.ticker }

            fun fwd(t: String) = close(t, d1) / close(t, d0) - 1
            var strat = picks.map { fwd(it).let { r -> if (r.isNaN()) 0.0 else r } }.average()
            val benchVals = cand.map { fwd(it.ticker) }.filter { !it.isNaN() }
            val bench = if (benchVals.isEmpty()) Double.NaN else benchVals.average()
            val turnover = 1 - (prev intersect picks.toSet()).size.toDouble() / topN
            strat -= cost * turnover
            periods += BacktestPeriod(d0, d1, strat, bench, turnover, picks)
            prev = picks.toSet()
        }
        if (periods.isEmpty()) return null

        val equity = mutableListOf(Triple(periods.first().start, 1.0, 1.0))
        var s = 1.0; var b = 1.0
        for (p in periods) { s *= 1 + p.strategy; b *= 1 + p.benchmark; equity += Triple(p.end, s, b) }

        val years = ChronoUnit.DAYS.between(periods.first().start, periods.last().end) / 365.25
        fun cagr(x: Double) = if (years > 0) x.pow(1 / years) - 1 else Double.NaN
        fun mdd(xs: List<Double>): Double {
            var peak = xs.first(); var m = 0.0
            for (x in xs) { if (x > peak) peak = x; m = minOf(m, x / peak - 1) }
            return m
        }
        val rets = periods.map { it.strategy }
        val mean = rets.average()
        val sd = if (rets.size > 1) sqrt(rets.sumOf { (it - mean) * (it - mean) } / (rets.size - 1)) else Double.NaN
        val perYear = if (years > 0) periods.size / years else Double.NaN

        return BacktestResult(
            periods = periods,
            equity = equity,
            years = years,
            cagr = cagr(s),
            benchCagr = cagr(b),
            mdd = mdd(equity.map { it.second }),
            benchMdd = mdd(equity.map { it.third }),
            winRate = periods.count { it.strategy > it.benchmark }.toDouble() / periods.size,
            sharpe = mean / sd * sqrt(perYear),
            avgTurnover = periods.map { it.turnover }.average(),
        )
    }
}
