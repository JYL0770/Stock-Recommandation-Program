package com.stockrec.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MarketScanTest {
    @Test
    fun scanOnDemoDataAndJsonRoundTrip() {
        val stocks = DemoData.generate(nStocks = 40, nDays = 780, seed = 5)
        val bySym = stocks.associateBy { it.info.ticker }
        val indexes = BuyZone.KR_INDEXES.associate { (code, name) ->
            code to BuyZone.makeIndex(code, stocks.filter { it.info.market == code }).copy(info = StockInfo(code, name, "INDEX"))
        }
        val s = MarketScan.run("KR", stocks.map { it.info }, BuyZone.KR_INDEXES, { t -> bySym[t] ?: indexes.getValue(t) }, threads = 2) {}
        assertEquals(40, s.universe)
        assertEquals(40, s.analyzed)
        // 후보 = 종목 일봉·주봉 모두 매수권
        val expected = stocks.map { BuyZone.evaluate(it, null) }.filter { it.stockOk }.map { it.info.ticker }.toSet()
        assertEquals(expected, s.candidates.map { it.ticker }.toSet())
        assertEquals(expected.size, s.counts["stock_both"])

        val back = ScanSummary.fromJson(s.toJson())
        assertEquals(s.copy(candidates = emptyList()), back.copy(candidates = emptyList()))
        assertEquals(s.candidates.map { it.ticker }, back.candidates.map { it.ticker })
        assertEquals(setOf("KOSPI", "KOSDAQ"), back.indexes.keys)
    }

    @Test
    fun usSymbolHelpers() {
        assertEquals("BRK-A", UsUniverse.toYahoo("BRK/A"))
        assertEquals("BF-B", UsUniverse.toYahoo("bf.b"))
        assertTrue(UsUniverse.isStockName("Apple Inc. Common Stock"))
        assertFalse(UsUniverse.isStockName("Acme Corp Warrants"))
        assertFalse(UsUniverse.isStockName("XYZ Acquisition Corp Units"))
    }
}
