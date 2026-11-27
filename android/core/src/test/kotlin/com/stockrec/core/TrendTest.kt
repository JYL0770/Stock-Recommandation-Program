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
                else -> line - 3                          // 추세선 바로 아래로 조정
            }
            weeks += Bar(d, close, close + 3, if (t < 122) close - 2 - (if (t % 10 == 0) 0.0 else 1.0) else close - 2, close, 1000.0)
            d = d.plusWeeks(1)
        }
        val ch = assertNotNull(TrendChannel.fit(StockInfo("X"), weeks))
        assertEquals(ChannelStatus.IN, ch.status)
        assertTrue(ch.touches >= 5, "touches=${ch.touches}")
        assertTrue(abs(ch.slope - 2.0) < 0.3, "slope=${ch.slope}")
        assertTrue(ch.position in 0.0..1.0)
    }

    @Test
    fun noChannelForDowntrend() {
        val weeks = (0 until 120).map { t ->
            val c = 500.0 - 2 * t + 10 * kotlin.math.sin(t / 3.0)
            Bar(LocalDate.of(2023, 1, 6).plusWeeks(t.toLong()), c, c + 3, c - 3, c, 1.0)
        }
        assertNull(TrendChannel.fit(StockInfo("Y"), weeks))
    }

    @Test
    fun matchesPython() {
        val rows = res("trend_daily.csv").trim().lines().drop(1).map { it.split(',') }
        val series = rows.groupBy { it[0] }.mapValues { (_, r) ->
            r.map { Bar(LocalDate.parse(it[1]), it[2].toDouble(), it[3].toDouble(), it[4].toDouble(), it[5].toDouble(), it[6].toDouble()) }
        }
        val exp = Json.parseToJsonElement(res("expected_trend.json")).jsonObject
        assertEquals(exp.keys, series.keys)
        var found = 0
        for ((t, e) in exp) {
            val ch = TrendChannel.fit(StockInfo(t), TrendChannel.weekly(series.getValue(t)))
            if (e is JsonNull) { assertNull(ch, t); continue }
            val o = e.jsonObject
            assertNotNull(ch, t)
            found++
            assertEquals(o.getValue("status").jsonPrimitive.content, ch.status.label, t)
            assertEquals(o.getValue("touches").jsonPrimitive.int, ch.touches, t)
            assertEquals(o.getValue("start_date").jsonPrimitive.content, ch.startDate.toString(), t)
            assertEquals(o.getValue("pivot2_date").jsonPrimitive.content, ch.pivot2Date.toString(), t)
            for ((k, v) in listOf("upper" to ch.upper, "lower" to ch.lower, "width" to ch.width, "trend_per_year" to ch.trendPerYear)) {
                val ev = o.getValue(k).jsonPrimitive.double
                assertTrue(abs(v - ev) <= 1e-9 * maxOf(1.0, abs(ev)), "$t $k kotlin=$v python=$ev")
            }
        }
        assertTrue(found >= 5)
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

        val all = TrendChannel.scan(series, statuses = ChannelStatus.entries.toSet())
        println("TREND|INFO|채널 ${all.size}개: " + all.groupingBy { it.status.label }.eachCount())
        for (c in all.filter { it.status == ChannelStatus.IN || it.status == ChannelStatus.NEAR }) {
            println(listOf("TREND", "ROW", c.status.label, c.info.ticker, c.info.name, c.info.market, c.close.toLong(),
                c.upper.toLong(), c.lower.toLong(), "%.2f".format(c.position), "%.3f".format(c.width), c.touches,
                c.startDate, c.pivot2Date, "%.3f".format(c.trendPerYear), "%.3f".format(c.fromRecentHigh)).joinToString("|"))
        }
        // 사용자 차트 비교용: SK하이닉스 (폭 자동 / 10% 고정)
        series.firstOrNull { it.info.ticker == "000660" }?.let { s ->
            val w = TrendChannel.weekly(s.bars)
            for (p in listOf(ChannelParams(), ChannelParams(width = 0.10))) {
                val c = TrendChannel.fit(s.info, w, p)
                println("TREND|HYNIX|width=${p.width}|" + (c?.let {
                    "${it.status.label}|close=${it.close}|upper=${it.upper.toLong()}|lower=${it.lower.toLong()}|w=${"%.3f".format(it.width)}|touches=${it.touches}|start=${it.startDate}(${it.startLow})|p2=${it.pivot2Date}|slope/wk=${it.slope.toLong()}"
                } ?: "채널 없음"))
            }
            println("TREND|HYNIXW|" + w.takeLast(150).joinToString(";") { "${it.date},${it.low.toLong()},${it.close.toLong()}" })
        }
        // 알고리즘 튜닝용: 전체 주봉 덤프 (WEEKLY|ticker|name|market|date,o,h,l,c;...)
        for (s in series) {
            val w = TrendChannel.weekly(s.bars).takeLast(160)
            println("WEEKLY|${s.info.ticker}|${s.info.name}|${s.info.market}|" +
                w.joinToString(";") { "${it.date},${it.open.toLong()},${it.high.toLong()},${it.low.toLong()},${it.close.toLong()}" })
        }
        assertTrue(series.size > 100)
    }
}
