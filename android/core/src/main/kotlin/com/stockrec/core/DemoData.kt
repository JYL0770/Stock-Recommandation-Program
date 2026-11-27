package com.stockrec.core

import java.time.DayOfWeek
import java.time.LocalDate
import java.util.Random
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong

/** 인터넷 없이 앱을 체험하기 위한 가상 주가 데이터 (실제 종목과 무관한 '데모종목') */
object DemoData {
    private val SECTORS = listOf("반도체", "2차전지", "바이오", "자동차", "금융", "인터넷", "화학", "조선", "엔터", "유통")

    fun generate(nStocks: Int = 80, nDays: Int = 520, seed: Long = 7, end: LocalDate = LocalDate.now()): List<StockSeries> {
        val rng = Random(seed)
        fun normal(mu: Double, sd: Double) = mu + sd * rng.nextGaussian()
        fun uniform(a: Double, b: Double) = a + (b - a) * rng.nextDouble()

        val dates = ArrayList<LocalDate>(nDays)
        var d = end
        while (dates.size < nDays) {
            if (d.dayOfWeek != DayOfWeek.SATURDAY && d.dayOfWeek != DayOfWeek.SUNDAY) dates += d
            d = d.minusDays(1)
        }
        dates.reverse()
        val market = DoubleArray(nDays) { normal(0.0003, 0.011) }

        return (0 until nStocks).map { i ->
            val beta = uniform(0.5, 1.6)
            val sigma = uniform(0.012, 0.04)
            val regimes = DoubleArray(nDays / 125 + 1) { normal(0.0002, 0.0015) }
            var price = uniform(3000.0, 200000.0)
            val baseVol = uniform(5e4, 3e6)
            val bars = dates.mapIndexed { t, date ->
                var r = beta * market[t] + regimes[t / 125] + normal(0.0, sigma)
                if (rng.nextDouble() < 0.004) r += normal(0.0, 0.12)
                price = max(price * exp(r), 100.0)
                val close = (price / 10).roundToLong() * 10.0
                val open = close * (1 + normal(0.0, sigma / 3))
                val high = max(open, close) * (1 + abs(normal(0.0, sigma / 2)))
                val low = min(open, close) * (1 - abs(normal(0.0, sigma / 2)))
                val volume = (baseVol * exp(normal(0.0, 0.4)) * (1 + 8 * abs(r))).roundToLong().toDouble()
                Bar(date, r10(open), r10(high), r10(low), close, volume, close * volume / 1e6)
            }
            val per = if (rng.nextDouble() < 0.85) uniform(3.0, 40.0) else uniform(-30.0, -1.0)
            StockSeries(
                StockInfo(
                    ticker = "9%04d0".format(i), name = "데모종목%02d".format(i + 1),
                    market = if (i % 3 != 0) "KOSPI" else "KOSDAQ", sector = SECTORS[i % SECTORS.size],
                    per = round2(per), pbr = round2(uniform(0.3, 6.0)), roe = round2(uniform(-10.0, 30.0)),
                ),
                bars,
            )
        }
    }

    private fun r10(x: Double) = (x / 10).roundToLong() * 10.0
    private fun round2(x: Double) = (x * 100).roundToLong() / 100.0
}
