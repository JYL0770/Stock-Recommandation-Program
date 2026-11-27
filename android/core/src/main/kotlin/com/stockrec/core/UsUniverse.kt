package com.stockrec.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.net.HttpURLConnection
import java.net.URL

/**
 * 미국 전체 종목 목록 (서버 스캔용).
 *  - NYSE·NASDAQ·AMEX: Nasdaq 종목 스크리너 (실패 시 nasdaqtrader 심볼 목록)
 *  - OTC·핑크시트: nasdaqtrader OTC 목록 + 해외 대형주 ADR 등 기본 목록 ([Universe.US_OTC])
 * 워런트·유닛·권리·우선주와 ETF 는 제외합니다. 시장 구분(NYSE/NASDAQ/OTC)은 시세 응답에서 다시 정합니다.
 */
object UsUniverse {
    private const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36"
    private val EXCLUDE_NAME = Regex("""(?i)\b(warrants?|units?|rights?|preferred|depositary shares? representing|notes due|debentures)\b""")

    fun all(log: (String) -> Unit = ::println): List<StockInfo> {
        val listed = runCatching { fromScreener() }.onFailure { log("스크리너 실패: $it") }.getOrDefault(emptyList())
            .ifEmpty { runCatching { fromSymbolDirectory() }.onFailure { log("심볼 목록 실패: $it") }.getOrDefault(emptyList()) }
        val otc = runCatching { otcList() }.onFailure { log("OTC 목록 실패: $it") }.getOrDefault(emptyList()) +
            Universe.US_OTC.map { StockInfo(it, it, "OTC") }
        log("미국 상장 ${listed.size}, OTC ${otc.size}")
        return (listed + otc).distinctBy { it.ticker }
    }

    /** Yahoo 심볼 형식 (BRK/A → BRK-A, BRK.B → BRK-B) */
    fun toYahoo(sym: String) = sym.trim().uppercase().replace('/', '-').replace('.', '-')

    fun isStockName(name: String) = !EXCLUDE_NAME.containsMatchIn(name)

    private fun validSymbol(s: String) = s.matches(Regex("[A-Z]{1,5}(-[A-Z])?"))

    fun fromScreener(): List<StockInfo> {
        val text = get("https://api.nasdaq.com/api/screener/stocks?tableonly=true&download=true")
        val rows = ((Json.parseToJsonElement(text) as JsonObject)["data"] as JsonObject)["rows"] as JsonArray
        return rows.mapNotNull { r ->
            val o = r as? JsonObject ?: return@mapNotNull null
            val sym = toYahoo((o["symbol"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null)
            val name = (o["name"] as? JsonPrimitive)?.contentOrNull ?: sym
            if (!validSymbol(sym) || !isStockName(name)) null else StockInfo(sym, name, "US")
        }
    }

    /** nasdaqtrader: nasdaqlisted.txt + otherlisted.txt (ETF·테스트 종목 제외) */
    fun fromSymbolDirectory(): List<StockInfo> {
        val out = mutableListOf<StockInfo>()
        for (line in get("https://www.nasdaqtrader.com/dynamic/symdir/nasdaqlisted.txt").lines().drop(1)) {
            val p = line.split('|'); if (p.size < 8) continue
            if (p[3] == "Y" || p[6] == "Y") continue                      // Test Issue, ETF
            val sym = toYahoo(p[0]); if (validSymbol(sym) && isStockName(p[1])) out += StockInfo(sym, p[1], "NASDAQ")
        }
        for (line in get("https://www.nasdaqtrader.com/dynamic/symdir/otherlisted.txt").lines().drop(1)) {
            val p = line.split('|'); if (p.size < 8) continue
            if (p[4] == "Y" || p[6] == "Y") continue                      // ETF, Test Issue
            val sym = toYahoo(p[0]); if (validSymbol(sym) && isStockName(p[1])) out += StockInfo(sym, p[1], "NYSE")
        }
        return out
    }

    fun otcList(): List<StockInfo> =
        get("https://www.nasdaqtrader.com/dynamic/symdir/otclist.txt").lines().drop(1).mapNotNull { line ->
            val p = line.split('|'); if (p.size < 5 || p[4] == "Y") return@mapNotNull null
            val sym = toYahoo(p[0])
            if (!validSymbol(sym) || !isStockName(p[1])) null else StockInfo(sym, p[1], "OTC")
        }

    private fun get(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 20_000; c.readTimeout = 60_000
            c.setRequestProperty("User-Agent", UA)
            c.setRequestProperty("Accept", "application/json, text/plain, */*")
            if (c.responseCode != 200) throw java.io.IOException("HTTP ${c.responseCode}: $url")
            return c.inputStream.use { String(it.readBytes(), Charsets.UTF_8) }
        } finally {
            c.disconnect()
        }
    }
}
