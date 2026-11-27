package com.stockrec.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assume.assumeTrue
import java.time.LocalDate
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TrendTest {
    private fun res(name: String) = javaClass.getResource("/parity/$name")!!.readText()

    @Test
    fun weeklyAggregation() {
        val days = listOf(  // 2025-11-24(월) ~ 11-28(금), 12-01(월)
            Bar(LocalDate.of(2025, 11, 24), 10.0, 12.0, 9.0, 11.0, 100.0),
            Bar(LocalDate.of(2025, 11, 26), 11.0, 15.0, 10.0, 14.0, 200.0),
            Bar(LocalDate.of(2025, 11, 28), 14.0, 14.5, 8.0, 13.0, 300.0),
            Bar(LocalDate.of(2025, 12, 1), 13.0, 13.0, 12.0, 12.5, 50.0),
        )
        val w = TrendChannel.weekly(days.shuffled())
        assertEquals(2, w.size)
        assertEquals(Bar(LocalDate.of(2025, 11, 28), 10.0, 15.0, 8.0, 13.0, 600.0, Double.NaN).toString(), w[0].toString())
        assertEquals(LocalDate.of(2025, 12, 1), w[1].date)
    }

    @Test
    fun findsPullbackIntoChannel() {
        // 120주 동안 저점이 직선 위로 오르는 지그재그 상승 → 급등 → 최근 추세선까지 하락
        val weeks = mutableListOf<Bar>()
        var d = LocalDate.of(2023, 1, 6)
        for (t in 0 until 130) {
            val line = 100.0 + 2.0 * t
            val wave = 12.0 * (1 - abs((t % 10) - 5) / 5.0)   // 0~12 (10주마다 저점이 선에 닿음)
            val close = when {
                t < 110 -> line + 2 + wave
                t < 122 -> line + 40 + (t - 110) * 6    // 급등
                else -> line * 1.04                       // 지지선 바로 위로 조정
            }
            weeks += Bar(d, close, close + 3, if (t < 122) close - 2 - (if (t % 10 == 0) 0.0 else 1.0) else close - 2, close, 1000.0)
            d = d.plusWeeks(1)
        }
        val ch = assertNotNull(TrendChannel.fit(StockInfo("X"), weeks))
        assertEquals(ChannelStatus.IN, ch.status)
        assertTrue(ch.touches >= 5, "touches=${ch.touches}")
        assertTrue(abs(ch.slope - 2.0) < 0.3, "slope=${ch.slope}")
        assertTrue(ch.position in 0.0..1.0, "position=${ch.position}")
        assertTrue(ch.fromRecentHigh < -0.1)

        // 같은 흐름에서 지지선 아래 10% 로 무너지면 이탈
        val broken = weeks.mapIndexed { t, b -> if (t >= 122) b.copy(close = (100.0 + 2 * t) * 0.9, low = (100.0 + 2 * t) * 0.88) else b }
        assertEquals(ChannelStatus.BELOW, TrendChannel.fit(StockInfo("X"), broken)?.status)
    }

    @Test
    fun noChannelForDowntrend() {
        val weeks = (0 until 120).map { t ->
            val c = 500.0 - 2 * t + 10 * kotlin.math.sin(t / 3.0)
            Bar(LocalDate.of(2023, 1, 6).plusWeeks(t.toLong()), c, c + 3, c - 3, c, 1.0)
        }
        assertNull(TrendChannel.fit(StockInfo("Y"), weeks))
    }

    private fun checkChannel(what: String, ch: Channel?, e: kotlinx.serialization.json.JsonElement) {
        if (e is JsonNull) { assertNull(ch, what); return }
        val o = e.jsonObject
        assertNotNull(ch, what)
        assertEquals(o.getValue("status").jsonPrimitive.content, ch.status.label, what)
        assertEquals(o.getValue("touches").jsonPrimitive.int, ch.touches, what)
        assertEquals(o.getValue("start_date").jsonPrimitive.content, ch.startDate.toString(), what)
        assertEquals(o.getValue("pivot2_date").jsonPrimitive.content, ch.pivot2Date.toString(), what)
        for ((k, v) in listOf("support" to ch.support, "upper" to ch.upper, "trend_per_year" to ch.trendPerYear,
                "from_recent_high" to ch.fromRecentHigh)) {
            val ev = o.getValue(k).jsonPrimitive.double
            assertTrue(abs(v - ev) <= 1e-8 * maxOf(1.0, abs(ev)), "$what $k kotlin=$v python=$ev")
        }
    }

    @Test
    fun matchesPython() {
        val rows = res("trend_daily.csv").trim().lines().drop(1).map { it.split(',') }
        val markets = res("trend_meta.csv").trim().lines().drop(1).map { it.split(',') }.associate { it[0] to it[1] }
        val series = rows.groupBy { it[0] }.map { (t, r) ->
            StockSeries(StockInfo(t, t, markets.getValue(t)),
                r.map { Bar(LocalDate.parse(it[1]), it[2].toDouble(), it[3].toDouble(), it[4].toDouble(), it[5].toDouble(), it[6].toDouble()) })
        }
        val exp = Json.parseToJsonElement(res("expected_trend.json")).jsonObject

        val idx = listOf("KOSPI", "KOSDAQ").associateWith { m ->
            BuyZone.index(m, BuyZone.makeIndex(m, series.filter { it.info.market == m }))
        }
        for ((m, st) in idx) {
            val e = exp.getValue("index").jsonObject.getValue(m).jsonObject
            assertEquals(e.getValue("n").jsonPrimitive.int, st.series.bars.size)
            val lc = e.getValue("last_close").jsonPrimitive.double
            assertTrue(abs(st.series.bars.last().close - lc) < 1e-8 * lc, "$m 지수 종가")
            checkChannel("$m 지수 일봉", st.daily, e.getValue("daily"))
            checkChannel("$m 지수 주봉", st.weekly, e.getValue("weekly"))
        }

        val stocks = exp.getValue("stocks").jsonObject
        assertEquals(stocks.keys, series.map { it.info.ticker }.toSet())
        var daily = 0; var weekly = 0
        for (s in series) {
            val e = stocks.getValue(s.info.ticker).jsonObject
            val r = BuyZone.evaluate(s, idx.getValue(BuyZone.indexFor(s.info.market)))
            checkChannel("${s.info.ticker} 주봉", r.channels[Check.STOCK_WEEKLY], e.getValue("weekly"))
            checkChannel("${s.info.ticker} 일봉", r.channels[Check.STOCK_DAILY], e.getValue("daily"))
            assertEquals(e.getValue("n_ok").jsonPrimitive.int, r.nOk, s.info.ticker)
            assertEquals(e.getValue("buy").jsonPrimitive.content.toBoolean(), r.buy, s.info.ticker)
            if (r.channels[Check.STOCK_DAILY] != null) daily++
            if (r.channels[Check.STOCK_WEEKLY] != null) weekly++
        }
        assertTrue(daily >= 5 && weekly >= 5, "일봉 $daily 주봉 $weekly")
    }

    /** 실제 시장 스캔 (CI 에서 NETWORK_TESTS=1). 결과를 TREND| 로 출력 */
    @Test
    fun liveScan() {
        assumeTrue(System.getenv("NETWORK_TESTS") == "1")
        val client = NaverClient()
        val universe = listOf("KOSPI", "KOSDAQ").flatMap { m -> client.fetchTopByMarketCap(m, 300) }
            .ifEmpty { Universe.DEFAULT }
        val pool = Executors.newFixedThreadPool(8)
        val futures = universe.map { info ->
            pool.submit<StockSeries?> {
                runCatching { client.fetchDaily(info.ticker, 800) }.getOrNull()
                    ?.let { StockSeries(info.copy(name = it.info.name.takeIf { n -> n != info.ticker } ?: info.name), it.bars) }
            }
        }
        val series = futures.mapNotNull { it.get() }
        pool.shutdown(); pool.awaitTermination(1, TimeUnit.MINUTES)
        println("TREND|INFO|종목 ${universe.size}개 중 수신 ${series.size}개, 기준일 ${series.maxOf { it.bars.last().date }}")

        val idx = listOf("KOSPI", "KOSDAQ").associateWith { m -> BuyZone.index(m, client.fetchDaily(m, 800)) }
        for ((m, st) in idx) for ((lab, c) in listOf("일봉" to st.daily, "주봉" to st.weekly)) {
            println("TREND|INDEX|$m|$lab|" + (c?.let { "${it.status.label}|종가=${it.close}|지지=${"%.2f".format(it.support)}|상단=${"%.2f".format(it.upper)}|접점=${it.touches}" } ?: "추세선 없음"))
        }
        val results = BuyZone.sort(series.map { BuyZone.evaluate(it, idx.getValue(BuyZone.indexFor(it.info.market))) })
        println("TREND|INFO|매수권(4/4) ${results.count { it.buy }}개, 종목 일봉+주봉 충족 ${results.count { it.stockOk }}개, " +
            "종목 일봉 ${results.count { it.ok(Check.STOCK_DAILY) }}개, 종목 주봉 ${results.count { it.ok(Check.STOCK_WEEKLY) }}개")
        for (r in results.filter { it.stockOk || it.buy }) {
            val d = r.channels[Check.STOCK_DAILY]!!; val w = r.channels[Check.STOCK_WEEKLY]!!
            println(listOf("TREND", "ROW", if (r.buy) "매수권" else "지수미충족", r.info.ticker, r.info.name, r.info.market,
                r.close.toLong(), d.support.toLong(), d.upper.toLong(), w.support.toLong(), w.upper.toLong(), r.nOk).joinToString("|"))
        }
        assertTrue(series.size > 100)
    }

    private fun fmtN(x: Double) = if (x == Math.floor(x)) x.toLong().toString() else "%.2f".format(x)
}
