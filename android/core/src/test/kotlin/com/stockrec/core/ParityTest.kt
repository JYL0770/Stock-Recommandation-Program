package com.stockrec.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.LocalDate
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Kotlin 엔진이 Python 엔진(recommender/)과 같은 결과를 내는지 검증.
 * 픽스처는 scripts/export_parity_fixtures.py 로 생성.
 */
class ParityTest {
    private fun res(name: String) = javaClass.getResource("/parity/$name")!!.readText()

    private fun csv(name: String): List<Map<String, String>> {
        val lines = res(name).trim().lines()
        val header = lines.first().split(',')
        return lines.drop(1).map { header.zip(it.split(',')).toMap() }
    }

    private fun d(s: String?) = if (s.isNullOrEmpty()) Double.NaN else s.toDouble()

    private val series: List<StockSeries> by lazy {
        val meta = csv("meta.csv").associateBy { it.getValue("ticker") }
        csv("daily.csv").groupBy { it.getValue("ticker") }.map { (t, rows) ->
            val m = meta.getValue(t)
            StockSeries(
                StockInfo(t, m.getValue("name"), m.getValue("market"), m.getValue("sector"),
                    d(m["per"]), d(m["pbr"]), d(m["roe"])),
                rows.map {
                    Bar(LocalDate.parse(it.getValue("date")), d(it["open"]), d(it["high"]), d(it["low"]),
                        d(it["close"]), d(it["volume"]), d(it["amount"]))
                },
            )
        }
    }
    private val features by lazy { series.map(Indicators::compute) }
    private val snaps by lazy { features.map { it.latest() } }
    private val expected by lazy { Json.parseToJsonElement(res("expected_scores.json")).jsonObject }
    private val filters = Filters(minAmount = 0.0, minDays = 250, minPrice = 1000.0)

    private fun close(a: Double, b: Double, tol: Double, what: String) {
        if (a.isNaN() && b.isNaN()) return
        if (abs(a - b) > tol * maxOf(1.0, abs(b))) fail("$what: kotlin=$a python=$b")
    }

    private fun checkFeatures(file: String, pick: (FeatureSeries) -> Snapshot?) {
        val exp = csv(file)
        assertTrue(exp.isNotEmpty())
        val byTicker = features.associateBy { it.info.ticker }
        for (row in exp) {
            val t = row.getValue("ticker")
            val s = pick(byTicker.getValue(t)) ?: fail("$t 스냅샷 없음")
            for ((k, v) in row) if (k != "ticker") close(s[k], d(v), 1e-6, "$file $t $k")
        }
    }

    @Test
    fun latestFeaturesMatchPython() = checkFeatures("expected_snapshot.csv") { it.latest() }

    @Test
    fun historicalFeaturesMatchPython() {
        val midDate = series.flatMap { s -> s.bars.map { it.date } }.toSortedSet().toList()[280]
        checkFeatures("expected_mid.csv") { fs -> fs.indexOf(midDate).takeIf { it >= 0 }?.let(fs::snapshotAt) }
    }

    @Test
    fun scoresAndRecommendationsMatchPython() {
        for (strat in STRATEGIES) {
            val exp = expected.getValue(strat.name).jsonObject
            val scored = Scoring.scoreAll(snaps, strat.weights, filters).associateBy { it.snap.ticker }
            val expScores = exp.getValue("scores").jsonObject
            assertEquals(expScores.keys, scored.keys, "${strat.name} 후보")
            // 소수 1자리 반올림 방식(pandas=half-even) 차이로 최대 0.1 차이 허용
            for ((t, v) in expScores) {
                val k = scored.getValue(t).score
                val e = v.jsonPrimitive.double
                assertTrue(abs(k - e) <= 0.1 + 1e-9, "${strat.name} $t score: kotlin=$k python=$e")
            }

            for ((t, fac) in exp.getValue("factors").jsonObject) for ((k, v) in fac.jsonObject) {
                val f = Factor.of(k.removePrefix("factor_"))
                val e = if (v is JsonNull) Double.NaN else v.jsonPrimitive.double
                close(scored.getValue(t).factors.getValue(f), e, 1e-9, "${strat.name} $t $k")
            }

            val top = Scoring.recommend(snaps, strat.weights, filters, 10)
            assertEquals(exp.getValue("top").jsonArray.map { it.jsonPrimitive.content }, top.map { it.snap.ticker }, "${strat.name} top10")
            val reasons = exp.getValue("reasons").jsonObject
            val warnings = exp.getValue("warnings").jsonObject
            for (r in top) {
                assertEquals(reasons.getValue(r.snap.ticker).jsonArray.map { it.jsonPrimitive.content }, r.reasons, "${strat.name} 사유")
                assertEquals(warnings.getValue(r.snap.ticker).jsonArray.map { it.jsonPrimitive.content }, r.warnings, "${strat.name} 주의")
            }
        }
    }

    @Test
    fun backtestMatchesPython() {
        for (strat in STRATEGIES) {
            val exp = expected.getValue(strat.name).jsonObject.getValue("backtest").jsonObject
            val res = Backtest.run(features, strat.weights, filters, topN = 5, holdDays = 20) ?: fail("결과 없음")
            val periods = exp.getValue("periods") as JsonArray
            assertEquals(periods.size, res.periods.size)
            for ((p, e) in res.periods.zip(periods)) {
                val o = e.jsonObject
                assertEquals(o.getValue("start").jsonPrimitive.content, p.start.toString())
                assertEquals(o.getValue("picks").jsonArray.map { it.jsonPrimitive.content }.toSet(), p.picks.toSet())
                close(p.strategy, o.getValue("strategy").jsonPrimitive.double, 1e-9, "${strat.name} period")
                close(p.benchmark, o.getValue("benchmark").jsonPrimitive.double, 1e-9, "${strat.name} bench")
            }
            val st = exp.getValue("stats") as JsonObject
            close(res.cagr, st.getValue("전략 CAGR").jsonPrimitive.double, 1e-9, "CAGR")
            close(res.mdd, st.getValue("전략 MDD").jsonPrimitive.double, 1e-9, "MDD")
            close(res.winRate, st.getValue("초과수익 승률").jsonPrimitive.double, 1e-9, "승률")
            close(res.sharpe, st.getValue("전략 샤프(근사)").jsonPrimitive.double, 1e-6, "샤프")
        }
    }
}
