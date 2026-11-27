package com.stockrec.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import java.io.File
import java.time.Instant
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * 전체 시장 스캔 (GitHub Actions 에서 매일 실행).
 * 모든 종목의 일봉을 받아 4개 차트 판정을 하고, 종목 일봉·주봉을 모두 만족한 '후보'만 JSON 으로 저장합니다.
 * 앱은 이 후보 목록을 받아 최신 시세로 다시 판정합니다 (휴대폰에서 수천 종목을 받지 않아도 됨).
 */
data class ScanCandidate(val ticker: String, val name: String, val market: String, val close: Double, val nOk: Int, val buy: Boolean)

data class ScanSummary(
    val region: String,
    val generatedAt: String,
    val asOf: String,
    val universe: Int,
    val analyzed: Int,
    val failed: Int,
    val counts: Map<String, Int>,
    val indexes: Map<String, String>,
    val candidates: List<ScanCandidate>,
) {
    fun toJson(): String {
        fun o(vararg p: Pair<String, JsonElement>) = JsonObject(p.toMap())
        val root = o(
            "region" to JsonPrimitive(region), "generatedAt" to JsonPrimitive(generatedAt), "asOf" to JsonPrimitive(asOf),
            "universe" to JsonPrimitive(universe), "analyzed" to JsonPrimitive(analyzed), "failed" to JsonPrimitive(failed),
            "counts" to JsonObject(counts.mapValues { JsonPrimitive(it.value) }),
            "indexes" to JsonObject(indexes.mapValues { JsonPrimitive(it.value) }),
            "candidates" to JsonArray(candidates.map {
                o("ticker" to JsonPrimitive(it.ticker), "name" to JsonPrimitive(it.name), "market" to JsonPrimitive(it.market),
                    "close" to JsonPrimitive(it.close), "nOk" to JsonPrimitive(it.nOk), "buy" to JsonPrimitive(it.buy))
            }),
        )
        return Json { prettyPrint = true }.encodeToString(JsonObject.serializer(), root)
    }

    companion object {
        fun fromJson(text: String): ScanSummary {
            val r = Json.parseToJsonElement(text) as JsonObject
            fun s(o: JsonObject, k: String) = (o[k] as? JsonPrimitive)?.contentOrNull ?: ""
            fun i(o: JsonObject, k: String) = (o[k] as? JsonPrimitive)?.intOrNull ?: 0
            return ScanSummary(
                s(r, "region"), s(r, "generatedAt"), s(r, "asOf"), i(r, "universe"), i(r, "analyzed"), i(r, "failed"),
                (r["counts"] as? JsonObject)?.mapValues { (it.value as? JsonPrimitive)?.intOrNull ?: 0 } ?: emptyMap(),
                (r["indexes"] as? JsonObject)?.mapValues { (it.value as? JsonPrimitive)?.contentOrNull ?: "" } ?: emptyMap(),
                (r["candidates"] as? JsonArray)?.mapNotNull { e ->
                    val o = e as? JsonObject ?: return@mapNotNull null
                    ScanCandidate(s(o, "ticker"), s(o, "name"), s(o, "market"),
                        (o["close"] as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull() ?: Double.NaN,
                        i(o, "nOk"), (o["buy"] as? JsonPrimitive)?.booleanOrNull ?: false)
                } ?: emptyList(),
            )
        }
    }
}

object MarketScan {
    /** universe 종목을 fetch 로 받아 판정 → 요약 (병렬) */
    fun run(
        region: String,
        universe: List<StockInfo>,
        indexes: List<Pair<String, String>>,
        fetch: (String) -> StockSeries,
        threads: Int = 8,
        log: (String) -> Unit = ::println,
    ): ScanSummary {
        val idx = indexes.mapNotNull { (code, name) ->
            runCatching { fetch(code) }.onFailure { log("지수 $code 실패: $it") }.getOrNull()
                ?.let { code to BuyZone.index(code, StockSeries(StockInfo(code, name, "INDEX"), it.bars)) }
        }.toMap()

        val pool = Executors.newFixedThreadPool(threads)
        val done = AtomicInteger()
        val futures = universe.map { info ->
            pool.submit<BuyZoneResult?> {
                val r = runCatching {
                    val s = fetch(info.ticker)
                    // 국내: 목록의 이름·시장 / 해외: 시세 응답의 이름·시장(NYSE/NASDAQ/OTC), 거래소 불명이면 목록 기준
                    val merged = when {
                        region == "KR" -> StockSeries(info, s.bars)
                        s.info.market == "US" -> StockSeries(s.info.copy(market = info.market), s.bars)
                        else -> s
                    }
                    if (merged.bars.size < 60) null else BuyZone.evaluate(merged, idx[BuyZone.indexFor(merged.info.market)])
                }.getOrNull()
                val n = done.incrementAndGet()
                if (n % 500 == 0) log("$region 진행 $n/${universe.size}")
                r
            }
        }
        val results = futures.map { it.get() }
        pool.shutdown(); pool.awaitTermination(1, TimeUnit.MINUTES)
        val ok = results.filterNotNull()

        fun st(c: Channel?) = c?.status?.label ?: "추세선 없음"
        val cands = BuyZone.sort(ok.filter { it.stockOk }).map {
            ScanCandidate(it.info.ticker, it.info.name, it.info.market, it.close, it.nOk, it.buy)
        }
        return ScanSummary(
            region = region,
            generatedAt = Instant.now().toString(),
            asOf = ok.mapNotNull { r -> r.channels[Check.STOCK_DAILY]?.bars?.lastOrNull()?.date }.maxOrNull()?.toString() ?: "",
            universe = universe.size,
            analyzed = ok.size,
            failed = universe.size - ok.size,
            counts = mapOf(
                "stock_daily" to ok.count { it.ok(Check.STOCK_DAILY) },
                "stock_weekly" to ok.count { it.ok(Check.STOCK_WEEKLY) },
                "stock_both" to ok.count { it.stockOk },
                "buy" to ok.count { it.buy },
            ),
            indexes = idx.mapValues { (_, s) -> "일봉 ${st(s.daily)} / 주봉 ${st(s.weekly)}" },
            candidates = cands,
        )
    }
}

/** gradle :core:scan --args="KR out/scan_kr.json" */
fun main(args: Array<String>) {
    val region = args.getOrElse(0) { "KR" }.uppercase()
    val out = File(args.getOrElse(1) { "scan_${region.lowercase()}.json" })
    val t0 = System.currentTimeMillis()
    val summary = when (region) {
        "KR" -> {
            val naver = NaverClient()
            val universe = listOf("KOSPI", "KOSDAQ").flatMap { naver.fetchTopByMarketCap(it, 5000) }.distinctBy { it.ticker }
            println("KR 전체 ${universe.size}종목")
            MarketScan.run("KR", universe, BuyZone.KR_INDEXES, { naver.fetchDaily(it, 800) })
        }
        else -> {
            val yahoo = YahooClient()
            val universe = UsUniverse.all()
            println("US 전체 ${universe.size}종목")
            MarketScan.run("US", universe, BuyZone.US_INDEXES, { yahoo.fetchDaily(it, 1200) }, threads = 6)
        }
    }
    out.absoluteFile.parentFile.mkdirs()
    out.writeText(summary.toJson())
    println("완료 ${(System.currentTimeMillis() - t0) / 1000}초: 분석 ${summary.analyzed}/${summary.universe}, " +
        "후보 ${summary.candidates.size}, 매수권 ${summary.counts["buy"]}, 지수 ${summary.indexes} → ${out.path}")
}
