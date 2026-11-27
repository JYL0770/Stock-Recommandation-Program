package com.stockrec.core

import org.junit.Assume.assumeTrue
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test

/** 전체 종목 목록 출처 확인용 (CI NETWORK_TESTS=1) */
class ProbeTest {
    private fun get(url: String, ua: String = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36"): Pair<Int, String> {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 20000; c.readTimeout = 60000
        c.setRequestProperty("User-Agent", ua)
        c.setRequestProperty("Accept", "application/json, text/plain, */*")
        return try {
            val code = c.responseCode
            code to ((if (code < 400) c.inputStream else c.errorStream)?.use { String(it.readBytes()) } ?: "")
        } finally { c.disconnect() }
    }

    @Test
    fun probe() {
        assumeTrue(System.getenv("NETWORK_TESTS") == "1")
        val naver = NaverClient()
        for (m in listOf("KOSPI", "KOSDAQ")) {
            val all = runCatching { naver.fetchTopByMarketCap(m, 5000) }
            println("PROBE|naver $m 전체: ${all.getOrNull()?.size} ${all.exceptionOrNull() ?: ""}")
        }
        for (u in listOf(
            "https://www.nasdaqtrader.com/dynamic/symdir/nasdaqlisted.txt",
            "https://www.nasdaqtrader.com/dynamic/symdir/otherlisted.txt",
            "https://www.nasdaqtrader.com/dynamic/symdir/otclist.txt",
            "https://api.nasdaq.com/api/screener/stocks?tableonly=true&download=true",
            "https://www.sec.gov/files/company_tickers_exchange.json",
            "https://www.otcmarkets.com/research/stock-screener/api/downloadCSV?pageSize=50",
        )) {
            val (code, body) = runCatching { get(u) }.getOrElse { -1 to it.toString() }
            println("PROBE|$code|${body.length}자|$u|" + body.take(300).replace("\n", "⏎"))
            println("PROBE-LINES|$u|" + body.lines().size)
        }
        // Yahoo 연속 호출 한도: 600개 심볼 8병렬
        val text = runCatching { get("https://www.nasdaqtrader.com/dynamic/symdir/otherlisted.txt").second }.getOrDefault("")
        val syms = text.lines().drop(1).mapNotNull { it.split('|').firstOrNull()?.takeIf { s -> s.matches(Regex("[A-Z]{1,5}")) } }.take(600)
        val y = YahooClient()
        val pool = Executors.newFixedThreadPool(8)
        val t0 = System.currentTimeMillis()
        val res = syms.map { s -> pool.submit<String?> { runCatching { y.fetchDaily(s); null }.getOrElse { it.message?.take(40) } } }.map { it.get() }
        pool.shutdown(); pool.awaitTermination(1, TimeUnit.MINUTES)
        println("PROBE|yahoo ${syms.size}개 ${(System.currentTimeMillis() - t0) / 1000}초, 실패 ${res.count { it != null }}: " +
            res.filterNotNull().groupingBy { it.substringBefore(":").take(30) }.eachCount())
    }
}
