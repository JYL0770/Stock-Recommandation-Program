package com.stockrec.core

import org.junit.Assume.assumeTrue
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DataTest {
    private val sampleXml = """<?xml version="1.0" encoding="EUC-KR" ?>
<protocol>
<chartdata symbol="005930" name="삼성전자" count="3" timeframe="day" precision="0" origintime="19900103">
<item data="20251124|70000|71000|69500|70500|12345678" />
<item data="20251125|70500|72000|70100|71800|23456789" />
<item data="20251126|0|0|0|71800|0" />
</chartdata>
</protocol>"""

    @Test
    fun parsesEucKrFchart() {
        val text = NaverParser.decode(sampleXml.toByteArray(charset("EUC-KR")), "text/xml")
        val (name, bars) = NaverParser.parseFchart(text)
        assertEquals("삼성전자", name)
        assertEquals(3, bars.size)
        assertEquals(LocalDate.of(2025, 11, 25), bars[1].date)
        assertEquals(71800.0, bars[1].close)
        assertEquals(71800.0, bars[2].open) // 거래정지일 시가 0 → 종가
        assertEquals(71800.0 * 23456789 / 1e6, bars[1].amount, 1e-6)
    }

    @Test
    fun decodesXmlEntitiesInName() {
        val xml = """<chartdata symbol="033780" name="KT&amp;G" count="1"><item data="20251124|1|1|1|1|1" /></chartdata>"""
        assertEquals("KT&G", NaverParser.parseFchart(xml).first)
    }

    @Test
    fun parsesMarketValueJson() {
        val json = """{"stocks":[
            {"itemCode":"005930","stockName":"삼성전자","stockEndType":"stock"},
            {"itemCode":"005935","stockName":"삼성전자우","stockEndType":"stock"},
            {"itemCode":"069500","stockName":"KODEX 200","stockEndType":"etf"},
            {"itemCode":"000660","stockName":"SK하이닉스"}]}"""
        val list = NaverParser.parseMarketValue(json, "KOSPI")
        assertEquals(listOf("005930", "000660"), list.map { it.ticker })
        assertTrue(list.all { it.market == "KOSPI" })
    }

    @Test
    fun defaultUniverseIsValid() {
        val u = Universe.DEFAULT
        assertEquals(u.size, u.map { it.ticker }.toSet().size, "중복 종목")
        assertTrue(u.all { Universe.isCommonStock(it.ticker, it.name) })
    }

    @Test
    fun demoDataRecommends() {
        val feats = DemoData.generate(nStocks = 40, nDays = 400).map(Indicators::compute)
        val snaps = feats.map { it.latest() }
        for (s in STRATEGIES) {
            val top = Scoring.recommend(snaps, s.weights, Filters(minAmount = 0.0), 5)
            assertEquals(5, top.size)
            assertTrue(top.zipWithNext().all { (a, b) -> a.score >= b.score })
        }
        val bt = Backtest.run(feats, STRATEGIES[0].weights, Filters(minAmount = 0.0), 5)
        assertTrue(bt != null && bt.periods.isNotEmpty())
    }

    /** 실제 네이버 시세 연동 (CI 에서 NETWORK_TESTS=1 일 때만) */
    @Test
    fun liveNaverData() {
        assumeTrue(System.getenv("NETWORK_TESTS") == "1")
        val client = NaverClient()
        val s = client.fetchDaily("005930", 300)
        println("fchart: ${s.info.name} ${s.bars.size}봉, 마지막 ${s.bars.last()}")
        assertTrue(s.bars.size >= 250 && s.info.name != "005930")

        for (m in listOf("KOSPI", "KOSDAQ")) {
            val top = client.fetchTopByMarketCap(m, 120)
            println("marketValue $m: ${top.size}개, 앞부분 ${top.take(5).map { "${it.ticker} ${it.name}" }}")
            assertTrue(top.size >= 100, "$m 시총 상위 목록")
        }

        val failed = Universe.DEFAULT.filter { runCatching { client.fetchDaily(it.ticker, 30) }.isFailure }
        println("기본 목록 ${Universe.DEFAULT.size}개 중 실패: $failed")
        assertTrue(failed.isEmpty(), "기본 목록에 시세 없는 종목: $failed")
    }
}
