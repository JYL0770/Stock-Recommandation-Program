package com.stockrec.core

import kotlin.math.max
import kotlin.math.sqrt

/**
 * 일봉 → 가격 기반 기술적 지표. Python recommender/indicators.py 를 그대로 옮긴 것으로,
 * pandas 의 rolling/ewm(min_periods) 규칙과 같은 결과를 내도록 구현했습니다.
 * 모든 값은 해당 날짜까지의 과거 데이터만 사용합니다.
 */
object Indicators {
    private const val NaN = Double.NaN

    fun compute(series: StockSeries): FeatureSeries {
        val bars = series.bars.sortedBy { it.date }
        val n = bars.size
        val c = DoubleArray(n) { bars[it].close }
        val h = DoubleArray(n) { bars[it].high }
        val l = DoubleArray(n) { bars[it].low }
        val v = DoubleArray(n) { bars[it].volume }
        val out = LinkedHashMap<String, DoubleArray>()

        for (k in intArrayOf(20, 60, 120, 250)) out["ret_$k"] = ratio(c, shift(c, k), -1.0)
        out[F.MOM_12_1] = ratio(shift(c, 20), shift(c, 250), -1.0)

        val ma5 = rollingMean(c, 5, 5)
        val ma20 = rollingMean(c, 20, 20)
        val ma60 = rollingMean(c, 60, 60)
        val ma120 = rollingMean(c, 120, 120)
        out[F.MA5] = ma5; out[F.MA20] = ma20; out[F.MA60] = ma60; out[F.MA120] = ma120
        out[F.MA_ALIGN] = DoubleArray(n) { i ->
            if (ma120[i].isNaN()) NaN
            else (gt(c[i], ma5[i]) + gt(ma5[i], ma20[i]) + gt(ma20[i], ma60[i]) + gt(ma60[i], ma120[i])) / 4.0
        }
        out[F.DIST_MA20] = ratio(c, ma20, -1.0)
        out[F.DIST_MA60] = ratio(c, ma60, -1.0)
        out[F.DIST_MA120] = ratio(c, ma120, -1.0)
        out[F.MA20_SLOPE] = ratio(ma20, shift(ma20, 5), -1.0)

        out[F.RSI14] = rsi(c, 14)

        val ema12 = ewm(c, 2.0 / 13, 12)
        val ema26 = ewm(c, 2.0 / 27, 26)
        val macd = DoubleArray(n) { ema12[it] - ema26[it] }
        val signal = ewm(macd, 2.0 / 10, 9)
        out[F.MACD_HIST] = DoubleArray(n) { (macd[it] - signal[it]) / c[it] }

        val std20 = rollingStd(c, 20, 20)
        out[F.BB_PCTB] = DoubleArray(n) {
            val upper = ma20[it] + 2 * std20[it]
            val lower = ma20[it] - 2 * std20[it]
            val w = upper - lower
            if (w == 0.0) NaN else (c[it] - lower) / w
        }

        out[F.HIGH_52W] = ratio(c, rollingMax(h, 250, 120), 0.0)
        out[F.LOW_52W] = ratio(c, rollingMin(l, 250, 120), -1.0)

        val daily = ratio(c, shift(c, 1), -1.0)
        out[F.VOL_20] = rollingStd(daily, 20, 20).also { a -> for (i in a.indices) a[i] *= sqrt(252.0) }
        val v5 = rollingMean(v, 5, 5)
        val v60 = rollingMean(v, 60, 60)
        out[F.VOL_RATIO] = DoubleArray(n) { if (v60[it] == 0.0) NaN else v5[it] / v60[it] }

        val hasAmount = bars.any { !it.amount.isNaN() }
        val amount = DoubleArray(n) { if (hasAmount) bars[it].amount else c[it] * v[it] / 1e6 }
        out[F.AMOUNT_20] = rollingMean(amount, 20, 20)

        val peak = rollingMax(c, 120, 60)
        val dd = ratio(c, peak, -1.0)
        out[F.MDD_120] = rollingMin(dd, 120, 60)

        out[F.N_DAYS] = DoubleArray(n) { (it + 1).toDouble() }

        for (arr in out.values) for (i in arr.indices) if (arr[i].isInfinite()) arr[i] = NaN
        return FeatureSeries(series.info, bars, out)
    }

    // ------------------------------------------------------------ 기본 연산
    private fun gt(a: Double, b: Double) = if (a > b) 1.0 else 0.0  // NaN 비교는 false

    /** a/b + add (b==0 → ±inf → 나중에 NaN 처리) */
    private fun ratio(a: DoubleArray, b: DoubleArray, add: Double) = DoubleArray(a.size) { a[it] / b[it] + add }

    fun shift(x: DoubleArray, k: Int) = DoubleArray(x.size) { if (it - k >= 0) x[it - k] else NaN }

    private inline fun rolling(x: DoubleArray, w: Int, minP: Int, f: (DoubleArray, Int) -> Double): DoubleArray {
        val out = DoubleArray(x.size) { NaN }
        val buf = DoubleArray(w)
        for (i in x.indices) {
            var cnt = 0
            for (j in max(0, i - w + 1)..i) if (!x[j].isNaN()) buf[cnt++] = x[j]
            if (cnt >= minP && cnt > 0) out[i] = f(buf, cnt)
        }
        return out
    }

    fun rollingMean(x: DoubleArray, w: Int, minP: Int) = rolling(x, w, minP) { b, n ->
        var s = 0.0; for (i in 0 until n) s += b[i]; s / n
    }

    fun rollingStd(x: DoubleArray, w: Int, minP: Int) = rolling(x, w, minP) { b, n ->
        if (n < 2) NaN else {
            var s = 0.0; for (i in 0 until n) s += b[i]
            val m = s / n
            var ss = 0.0; for (i in 0 until n) ss += (b[i] - m) * (b[i] - m)
            sqrt(ss / (n - 1))
        }
    }

    fun rollingMax(x: DoubleArray, w: Int, minP: Int) = rolling(x, w, minP) { b, n ->
        var m = b[0]; for (i in 1 until n) if (b[i] > m) m = b[i]; m
    }

    fun rollingMin(x: DoubleArray, w: Int, minP: Int) = rolling(x, w, minP) { b, n ->
        var m = b[0]; for (i in 1 until n) if (b[i] < m) m = b[i]; m
    }

    /** pandas ewm(alpha, adjust=False, min_periods) — 앞쪽 NaN 은 건너뛰고 시작 */
    fun ewm(x: DoubleArray, alpha: Double, minP: Int): DoubleArray {
        val out = DoubleArray(x.size) { NaN }
        var y = NaN
        var cnt = 0
        for (i in x.indices) {
            if (!x[i].isNaN()) {
                y = if (y.isNaN()) x[i] else (1 - alpha) * y + alpha * x[i]
                cnt++
            }
            if (cnt >= minP && !y.isNaN()) out[i] = y
        }
        return out
    }

    /** Wilder RSI */
    fun rsi(c: DoubleArray, n: Int): DoubleArray {
        val diff = DoubleArray(c.size) { if (it == 0) NaN else c[it] - c[it - 1] }
        val gain = ewm(DoubleArray(c.size) { if (diff[it].isNaN()) NaN else max(diff[it], 0.0) }, 1.0 / n, n)
        val loss = ewm(DoubleArray(c.size) { if (diff[it].isNaN()) NaN else max(-diff[it], 0.0) }, 1.0 / n, n)
        return DoubleArray(c.size) {
            when {
                gain[it].isNaN() -> NaN
                loss[it] == 0.0 -> 100.0
                loss[it].isNaN() -> NaN
                else -> 100 - 100 / (1 + gain[it] / loss[it])
            }
        }
    }
}
