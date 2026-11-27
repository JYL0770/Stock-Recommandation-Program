package com.stockrec.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset

/**
 * 해외(미국) 주식 시세: Yahoo Finance 공개 차트 데이터 (API 키 불필요).
 * NYSE·NASDAQ·AMEX 와 장외시장(OTC, 핑크시트) 종목 모두 지원하며,
 * 응답의 거래소 정보로 시장(NYSE/NASDAQ/OTC)을 자동 구분합니다.
 * 공식 API 가 아니므로 형식이 바뀌거나 호출이 제한될 수 있습니다.
 */
class YahooClient(
    private val timeoutMs: Int = 15_000,
    private val userAgent: String = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Mobile Safari/537.36",
) {
    fun fetchDaily(symbol: String, days: Int = 1200): StockSeries {
        val now = Instant.now().epochSecond
        val from = now - days * 86_400L
        val sym = URLEncoder.encode(symbol, "UTF-8")
        var last: Exception? = null
        for (host in listOf("query1", "query2")) {
            try {
                val text = get("https://$host.finance.yahoo.com/v8/finance/chart/$sym?period1=$from&period2=$now&interval=1d&events=split")
                return YahooParser.parseChart(symbol, text)
            } catch (e: Exception) {
                last = e
            }
        }
        throw last ?: IOException("$symbol 시세 없음")
    }

    private fun get(url: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = timeoutMs
            conn.readTimeout = timeoutMs
            conn.setRequestProperty("User-Agent", userAgent)
            conn.setRequestProperty("Accept", "application/json")
            val code = conn.responseCode
            if (code != 200) throw IOException("HTTP $code: $url")
            return conn.inputStream.use { String(it.readBytes(), Charsets.UTF_8) }
        } finally {
            conn.disconnect()
        }
    }
}

object YahooParser {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** Yahoo 거래소 코드 → 시장 */
    fun marketOf(exchange: String?): String = when (exchange?.uppercase()) {
        "NMS", "NGM", "NCM", "NAS", "NASDAQ" -> "NASDAQ"
        "NYQ", "NYS", "NYSE", "ASE", "AMEX", "PCX", "BTS", "CBO" -> "NYSE"
        "PNK", "OQB", "OQX", "OEM", "OTC", "OBB", "PINX" -> "OTC"
        null, "" -> "US"
        else -> "US"
    }

    /** v8 chart JSON → 일봉 (거래소 현지 날짜, 종가 없는 날 제외) */
    fun parseChart(symbol: String, text: String): StockSeries {
        val root = json.parseToJsonElement(text).let { it as? JsonObject } ?: throw IOException("$symbol: 응답 형식 오류")
        val chart = root["chart"] as? JsonObject ?: throw IOException("$symbol: chart 없음")
        val result = (chart["result"] as? JsonArray)?.firstOrNull() as? JsonObject
            ?: throw IOException("$symbol: ${(chart["error"] as? JsonObject)?.get("description") ?: "결과 없음"}")
        val meta = result["meta"] as? JsonObject ?: JsonObject(emptyMap())
        val ts = (result["timestamp"] as? JsonArray)?.map { (it as? JsonPrimitive)?.longOrNull } ?: emptyList()
        val quote = ((result["indicators"] as? JsonObject)?.get("quote") as? JsonArray)?.firstOrNull() as? JsonObject
            ?: throw IOException("$symbol: quote 없음")
        fun col(k: String) = (quote[k] as? JsonArray)?.map { if (it is JsonNull) null else (it as? JsonPrimitive)?.doubleOrNull }
            ?: emptyList()
        val o = col("open"); val h = col("high"); val l = col("low"); val c = col("close"); val v = col("volume")
        val offset = (meta["gmtoffset"] as? JsonPrimitive)?.longOrNull ?: 0L

        val bars = ts.indices.mapNotNull { i ->
            val t = ts[i] ?: return@mapNotNull null
            val close = c.getOrNull(i)?.takeIf { it > 0 } ?: return@mapNotNull null
            val date = LocalDateTime.ofEpochSecond(t + offset, 0, ZoneOffset.UTC).toLocalDate()
            val op = o.getOrNull(i)?.takeIf { it > 0 } ?: close
            val hi = maxOf(h.getOrNull(i)?.takeIf { it > 0 } ?: close, op, close)
            val lo = minOf(l.getOrNull(i)?.takeIf { it > 0 } ?: close, op, close)
            val vol = v.getOrNull(i) ?: 0.0
            Bar(date, op, hi, lo, close, vol, close * vol / 1e6)
        }.sortedBy { it.date }.distinctBy { it.date }
        if (bars.isEmpty()) throw IOException("$symbol: 시세 데이터 없음")

        fun str(k: String) = (meta[k] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
        val name = str("longName") ?: str("shortName") ?: symbol
        return StockSeries(StockInfo(symbol, name, marketOf(str("exchangeName"))), bars)
    }
}
