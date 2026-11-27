package com.stockrec.core

import org.junit.Assume.assumeTrue
import java.time.LocalDate
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class YahooTest {
    // 2025-11-24(월) 09:30 뉴욕 = 14:30 UTC, gmtoffset -18000
    private val sample = """{"chart":{"result":[{"meta":{"currency":"USD","symbol":"NSRGY","exchangeName":"PNK",
        "longName":"Nestlé S.A.","gmtoffset":-18000},
        "timestamp":[1763994600,1764081000,1764167400],
        "indicators":{"quote":[{"open":[100.5,null,101.0],"high":[102.0,null,103.5],"low":[99.0,null,100.2],
        "close":[101.25,null,103.0],"volume":[12000,null,0]}]}}],"error":null}}"""

    @Test
    fun parsesChartWithNullsAndExchange() {
        val s = YahooParser.parseChart("NSRGY", sample)
        assertEquals("Nestlé S.A.", s.info.name)
        assertEquals("OTC", s.info.market)
        assertEquals(listOf(LocalDate.of(2025, 11, 24), LocalDate.of(2025, 11, 26)), s.bars.map { it.date })
        assertEquals(101.25, s.bars[0].close)
        assertEquals(99.0, s.bars[0].low)
    }

    @Test
    fun marketAndIndexMapping() {
        assertEquals("NASDAQ", YahooParser.marketOf("NMS"))
        assertEquals("NYSE", YahooParser.marketOf("NYQ"))
        assertEquals("OTC", YahooParser.marketOf("PNK"))
        assertEquals("OTC", YahooParser.marketOf("OID"))
        assertEquals("^IXIC", BuyZone.indexFor("NASDAQ"))
        assertEquals("^GSPC", BuyZone.indexFor("NYSE"))
        assertEquals("^GSPC", BuyZone.indexFor("OTC"))
        assertEquals("KOSDAQ", BuyZone.indexFor("KOSDAQ"))
        assertEquals("KOSPI", BuyZone.indexFor("KOSPI"))
        val all = Universe.US_LARGE + Universe.US_OTC
        assertEquals(all.size, all.toSet().size, "중복 심볼")
    }

    /** 실제 Yahoo 시세 + 미국·핑크시트 매수권 스캔 (CI 에서 NETWORK_TESTS=1) */
    @Test
    fun liveUsScan() {
        assumeTrue(System.getenv("NETWORK_TESTS") == "1")
        val client = YahooClient()
        val idx = BuyZone.US_INDEXES.associate { (code, name) ->
            val s = client.fetchDaily(code)
            println("TREND|US-INDEX|$code $name ${s.bars.size}봉 마지막 ${s.bars.last().date} ${s.bars.last().close}")
            code to BuyZone.index(code, s)
        }
        for ((m, st) in idx) for ((lab, c) in listOf("일봉" to st.daily, "주봉" to st.weekly)) {
            println("TREND|US-INDEX|$m|$lab|" + (c?.let { "${it.status.label}|종가=${it.close}|지지=${"%.2f".format(it.support)}|상단=${"%.2f".format(it.upper)}|접점=${it.touches}" } ?: "추세선 없음"))
        }
        val pool = Executors.newFixedThreadPool(6)
        val symbols = Universe.US_LARGE + Universe.US_OTC
        val res = symbols.map { sym -> pool.submit<Result<StockSeries>> { runCatching { client.fetchDaily(sym) } } }.map { it.get() }
        pool.shutdown(); pool.awaitTermination(1, TimeUnit.MINUTES)
        val series = res.mapNotNull { it.getOrNull() }
        val failed = symbols.zip(res).filter { it.second.isFailure }.map { "${it.first}(${it.second.exceptionOrNull()?.message?.take(60)})" }
        println("TREND|US-INFO|수신 ${series.size}/${symbols.size}, 실패: $failed")
        println("TREND|US-INFO|시장별: " + series.groupingBy { it.info.market }.eachCount())
        val otcWrong = Universe.US_OTC.filter { s -> series.firstOrNull { it.info.ticker == s }?.info?.market.let { it != null && it != "OTC" } }
        println("TREND|US-INFO|OTC 목록 중 OTC 아님: $otcWrong / 최소 봉수 ${series.minOfOrNull { it.bars.size }}")
        println("TREND|US-INFO|거래소 코드: " + series.groupingBy { "${it.info.sector}->${it.info.market}" }.eachCount())
        println("TREND|US-INFO|봉 200개 미만: " + series.filter { it.bars.size < 200 }.map { "${it.info.ticker}(${it.bars.size}, 마지막 ${it.bars.last().date})" })
        val results = BuyZone.sort(series.map { BuyZone.evaluate(it, idx[BuyZone.indexFor(it.info.market)]) })
        println("TREND|US-INFO|매수권(4/4) ${results.count { it.buy }}개, 종목 일봉+주봉 ${results.count { it.stockOk }}개, " +
            "일봉 ${results.count { it.ok(Check.STOCK_DAILY) }}개, 주봉 ${results.count { it.ok(Check.STOCK_WEEKLY) }}개")
        for (r in results.filter { it.stockOk || it.buy }) {
            val d = r.channels[Check.STOCK_DAILY]!!; val w = r.channels[Check.STOCK_WEEKLY]!!
            println(listOf("TREND", "US-ROW", if (r.buy) "매수권" else "지수미충족", r.info.ticker, r.info.name, r.info.market,
                "%.2f".format(r.close), "%.2f".format(d.support), "%.2f".format(w.support), r.nOk).joinToString("|"))
        }
        assertTrue(series.size >= symbols.size * 0.8, "수신 ${series.size}/${symbols.size}")
    }
}
