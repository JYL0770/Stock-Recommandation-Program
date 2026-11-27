package com.stockrec.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.Charset
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * 네이버 금융 공개 시세 데이터 (API 키 불필요).
 *  - 일봉: fchart.stock.naver.com (수정주가)
 *  - 시가총액 상위 종목: m.stock.naver.com
 *
 * 공식 Open API 가 아니므로 형식이 바뀔 수 있습니다 → 실패 시 기본 종목 목록 / 캐시로 대체.
 * 키움 REST API 는 등록한 고정 IP 에서만 호출되어 휴대폰에서 직접 쓰기 어렵기 때문에 사용합니다.
 */
class NaverClient(
    private val timeoutMs: Int = 15_000,
    private val userAgent: String = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Mobile Safari/537.36",
) {
    fun fetchDaily(ticker: String, count: Int = 520): StockSeries {
        val text = get("https://fchart.stock.naver.com/sise.nhn?symbol=$ticker&timeframe=day&count=$count&requestType=0")
        val (name, bars) = NaverParser.parseFchart(text)
        if (bars.isEmpty()) throw IOException("$ticker: 시세 데이터 없음")
        return StockSeries(StockInfo(ticker, name ?: ticker), bars)
    }

    /** 시가총액 상위 종목 (market = "KOSPI" | "KOSDAQ") */
    fun fetchTopByMarketCap(market: String, n: Int): List<StockInfo> {
        val out = mutableListOf<StockInfo>()
        var page = 1
        while (out.size < n && page <= 20) {
            val text = get("https://m.stock.naver.com/api/stocks/marketValue/$market?page=$page&pageSize=100")
            val items = NaverParser.parseMarketValue(text, market)
            if (items.isEmpty()) break
            out += items
            page++
        }
        return out.distinctBy { it.ticker }.take(n)
    }

    private fun get(url: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = timeoutMs
            conn.readTimeout = timeoutMs
            conn.setRequestProperty("User-Agent", userAgent)
            conn.setRequestProperty("Referer", "https://m.stock.naver.com/")
            val code = conn.responseCode
            if (code != 200) throw IOException("HTTP $code: $url")
            val bytes = conn.inputStream.use { it.readBytes() }
            return NaverParser.decode(bytes, conn.contentType)
        } finally {
            conn.disconnect()
        }
    }
}

object NaverParser {
    private val DATE = DateTimeFormatter.ofPattern("yyyyMMdd")
    private val ENCODING = Regex("""encoding="([^"]+)"""")
    private val CHARSET = Regex("""charset=([\w-]+)""", RegexOption.IGNORE_CASE)
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** XML 선언/Content-Type 의 인코딩(EUC-KR 등)에 맞게 디코딩 */
    fun decode(bytes: ByteArray, contentType: String?): String {
        val head = String(bytes, 0, minOf(bytes.size, 200), Charsets.ISO_8859_1)
        val enc = ENCODING.find(head)?.groupValues?.get(1)
            ?: contentType?.let { CHARSET.find(it)?.groupValues?.get(1) }
            ?: "UTF-8"
        return String(bytes, runCatching { Charset.forName(enc) }.getOrDefault(Charsets.UTF_8))
    }

    /**
     * fchart XML → (종목명, 일봉). item data="20240102|시가|고가|저가|종가|거래량"
     *
     * 정규식 대신 indexOf 로 직접 훑습니다. Android 의 정규식은 ICU(네이티브) 기반이라
     * Regex.findAll 이 매치마다 응답 전체를 네이티브 메모리로 복사해, 수백 종목을 받으면
     * 메모리가 폭증해 앱이 강제 종료되었습니다.
     */
    fun parseFchart(xml: String): Pair<String?, List<Bar>> {
        val name = attr(xml, "<chartdata", "name")?.takeIf { it.isNotBlank() }
        val bars = ArrayList<Bar>(600)
        val key = "<item data=\""
        var pos = 0
        while (true) {
            val s = xml.indexOf(key, pos)
            if (s < 0) break
            val start = s + key.length
            val end = xml.indexOf('"', start)
            if (end < 0) break
            pos = end + 1
            parseItem(xml.substring(start, end))?.let(bars::add)
        }
        bars.sortBy { it.date }
        return name to bars.distinctBy { it.date }
    }

    private fun parseItem(data: String): Bar? {
        val p = data.split('|')
        if (p.size < 6) return null
        val o = p[1].trim().toDoubleOrNull() ?: return null
        val h = p[2].trim().toDoubleOrNull() ?: return null
        val l = p[3].trim().toDoubleOrNull() ?: return null
        val c = p[4].trim().toDoubleOrNull() ?: return null
        val v = p[5].trim().toDoubleOrNull() ?: return null
        if (c <= 0) return null
        val date = runCatching { LocalDate.parse(p[0].trim().take(8), DATE) }.getOrNull() ?: return null
        // 거래 정지일 등 시가 0 인 날은 종가로 채움
        return Bar(date, if (o > 0) o else c, if (h > 0) h else c, if (l > 0) l else c, c, v, c * v / 1e6)
    }

    /** <tag ... name="value"> 에서 속성 값 */
    private fun attr(xml: String, tag: String, name: String): String? {
        val t = xml.indexOf(tag).takeIf { it >= 0 } ?: return null
        val close = xml.indexOf('>', t).takeIf { it >= 0 } ?: return null
        val a = xml.indexOf(" $name=\"", t).takeIf { it in t..close } ?: return null
        val start = a + name.length + 3
        val end = xml.indexOf('"', start).takeIf { it in start..close } ?: return null
        return xml.substring(start, end)
    }

    /** m.stock.naver.com marketValue JSON → 보통주 목록 (ETF·ETN·우선주 등 제외) */
    fun parseMarketValue(text: String, market: String): List<StockInfo> {
        val root = runCatching { json.parseToJsonElement(text) }.getOrNull() ?: return emptyList()
        val arr = findArray(root, "stocks") ?: return emptyList()
        return arr.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val code = o.str("itemCode") ?: o.str("reutersCode") ?: return@mapNotNull null
            val name = o.str("stockName") ?: o.str("itemName") ?: code
            val endType = o.str("stockEndType")
            if (endType != null && endType != "stock") return@mapNotNull null
            if (!Universe.isCommonStock(code, name)) return@mapNotNull null
            StockInfo(code, name, market)
        }
    }

    private fun JsonObject.str(k: String) = (this[k] as? JsonPrimitive)?.contentOrNull

    private fun findArray(e: JsonElement, key: String): JsonArray? = when (e) {
        is JsonObject -> (e[key] as? JsonArray) ?: e.values.firstNotNullOfOrNull { findArray(it, key) }
        is JsonArray -> e.firstNotNullOfOrNull { findArray(it, key) }
        else -> null
    }
}
