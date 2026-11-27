package com.stockrec.core

import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToLong

/** 팩터 (Python recommender/scoring.py 의 FACTORS / FACTOR_HELP) */
enum class Factor(val key: String, val label: String, val help: String) {
    TREND("trend", "추세", "종가>MA5>MA20>MA60>MA120 정배열 정도, 60일선 이격도, 20일선 기울기의 백분위 평균"),
    MOMENTUM("momentum", "모멘텀", "12-1개월 모멘텀, 6개월·3개월 수익률의 백분위 평균"),
    HIGH("high", "신고가 근접", "52주 최고가에 얼마나 가까운지의 백분위"),
    STABILITY("stability", "안정성", "20일 변동성과 120일 최대낙폭이 작을수록 높음"),
    TIMING("timing", "과열 회피", "RSI(14)가 55 부근이면 100, 과열(80↑)·과매도(30↓)일수록 낮음 (절대 점수)"),
    VOLUME("volume", "거래량 증가", "최근 5일 거래량이 60일 평균 대비 얼마나 늘었는지의 백분위"),
    REVERSAL("reversal", "단기 낙폭(반등)", "최근 1개월 수익률·RSI·볼린저 %B가 낮을수록(단기 조정이 클수록) 높음"),
    VALUE("value", "밸류", "저PER·저PBR·고ROE 백분위 평균 (적자 PER은 최하위). 재무 데이터가 있을 때만");

    companion object {
        fun of(key: String) = entries.first { it.key == key }
    }
}

data class Strategy(val name: String, val description: String, val weights: Map<Factor, Double>)

val STRATEGIES: List<Strategy> = listOf(
    Strategy("균형형", "추세·모멘텀을 중심으로 안정성과 과열 여부를 함께 고려",
        mapOf(Factor.TREND to 25.0, Factor.MOMENTUM to 25.0, Factor.HIGH to 10.0, Factor.STABILITY to 20.0,
            Factor.TIMING to 10.0, Factor.VOLUME to 10.0)),
    Strategy("추세추종", "강하게 오르고 있는 종목 (정배열 + 신고가 근접 + 강한 모멘텀)",
        mapOf(Factor.TREND to 35.0, Factor.MOMENTUM to 35.0, Factor.HIGH to 20.0, Factor.VOLUME to 10.0)),
    Strategy("눌림목", "중장기 상승 추세 속에서 최근 단기 조정을 받은 종목",
        mapOf(Factor.TREND to 35.0, Factor.REVERSAL to 35.0, Factor.STABILITY to 15.0, Factor.TIMING to 15.0)),
    Strategy("저변동 안정형", "변동성과 낙폭이 작고 꾸준히 오르는 종목",
        mapOf(Factor.STABILITY to 50.0, Factor.TREND to 25.0, Factor.MOMENTUM to 15.0, Factor.TIMING to 10.0)),
    Strategy("가치+모멘텀", "싼 종목 중 주가 흐름이 살아난 종목 (재무 데이터 필요)",
        mapOf(Factor.VALUE to 40.0, Factor.MOMENTUM to 30.0, Factor.TREND to 15.0, Factor.STABILITY to 15.0)),
)

data class Filters(
    val minAmount: Double = 1000.0,   // 20일 평균 거래대금 하한 (백만원) = 10억원
    val minDays: Int = 250,
    val minPrice: Double = 1000.0,
    val maxRsi: Double? = null,
    val aboveMa120: Boolean = false,
    val markets: Set<String>? = null,
    val excludeTickers: Set<String> = emptySet(),
)

/** 팩터 점수가 붙은 후보 종목 */
data class Scored(val snap: Snapshot, val factors: Map<Factor, Double>, val score: Double = Double.NaN)

data class Recommendation(
    val rank: Int,
    val snap: Snapshot,
    val factors: Map<Factor, Double>,
    val score: Double,
    val nCandidates: Int,
    val reasons: List<String>,
    val warnings: List<String>,
)

object Scoring {
    private const val NaN = Double.NaN

    fun applyFilters(snaps: List<Snapshot>, f: Filters): List<Snapshot> = snaps.filter { s ->
        s[F.AMOUNT_20].orZero() >= f.minAmount &&
            s[F.N_DAYS].orZero() >= f.minDays &&
            s.close.orZero() >= f.minPrice &&
            (f.maxRsi == null || s[F.RSI14].orZero() <= f.maxRsi) &&
            (!f.aboveMa120 || s[F.DIST_MA120].let { if (it.isNaN()) -1.0 else it } > 0) &&
            (f.markets.isNullOrEmpty() || s.info.market in f.markets) &&
            s.ticker !in f.excludeTickers
    }

    /** 백분위 0~100 (pandas rank(pct=True, method='average'), NaN 유지) */
    fun pct(x: DoubleArray, higherIsBetter: Boolean = true): DoubleArray {
        val idx = x.indices.filter { !x[it].isNaN() }.sortedBy { if (higherIsBetter) x[it] else -x[it] }
        val out = DoubleArray(x.size) { NaN }
        val n = idx.size
        var i = 0
        while (i < n) {
            var j = i
            while (j + 1 < n && x[idx[j + 1]] == x[idx[i]]) j++
            val avgRank = (i + j) / 2.0 + 1
            for (k in i..j) out[idx[k]] = avgRank / n * 100
            i = j + 1
        }
        return out
    }

    private fun mean(vararg cols: DoubleArray) = DoubleArray(cols[0].size) { i ->
        var s = 0.0; var c = 0
        for (col in cols) if (!col[i].isNaN()) { s += col[i]; c++ }
        if (c == 0) NaN else s / c
    }

    fun factorScores(cands: List<Snapshot>): List<Scored> {
        fun col(k: String) = DoubleArray(cands.size) { cands[it][k] }
        val res = mutableMapOf<Factor, DoubleArray>()
        res[Factor.TREND] = mean(pct(col(F.MA_ALIGN)), pct(col(F.DIST_MA60)), pct(col(F.MA20_SLOPE)))
        res[Factor.MOMENTUM] = mean(pct(col(F.MOM_12_1)), pct(col(F.RET_120)), pct(col(F.RET_60)))
        res[Factor.HIGH] = pct(col(F.HIGH_52W))
        res[Factor.STABILITY] = mean(pct(col(F.VOL_20), false), pct(col(F.MDD_120)))
        res[Factor.TIMING] = col(F.RSI14).map { if (it.isNaN()) NaN else (100 * (1 - abs(it - 55) / 45)).coerceIn(0.0, 100.0) }.toDoubleArray()
        res[Factor.VOLUME] = pct(col(F.VOL_RATIO))
        res[Factor.REVERSAL] = mean(pct(col(F.RET_20), false), pct(col(F.RSI14), false), pct(col(F.BB_PCTB), false))

        val per = DoubleArray(cands.size) { cands[it].info.per }
        val pbr = DoubleArray(cands.size) { cands[it].info.pbr }
        val roe = DoubleArray(cands.size) { cands[it].info.roe }
        val perPos = per.map { if (it > 0) it else NaN }.toDoubleArray()
        val perScore = pct(perPos, false).mapIndexed { i, v -> if (per[i].isNaN()) NaN else if (v.isNaN()) 0.0 else v }.toDoubleArray()
        val pbrScore = pct(pbr.map { if (it > 0) it else NaN }.toDoubleArray(), false)
        res[Factor.VALUE] = mean(perScore, pbrScore, pct(roe))

        return cands.mapIndexed { i, s -> Scored(s, Factor.entries.associateWith { res.getValue(it)[i] }) }
    }

    /** 가중 평균 종합점수 (값이 없는 팩터는 나머지로 재분배), 소수 1자리 */
    fun composite(factors: Map<Factor, Double>, weights: Map<Factor, Double>): Double {
        var num = 0.0; var den = 0.0
        for ((k, w) in weights) {
            if (w <= 0) continue
            val v = factors[k] ?: NaN
            if (!v.isNaN()) { num += v * w; den += w }
        }
        return if (den == 0.0) NaN else (num / den * 10).roundToLong() / 10.0
    }

    fun scoreAll(snaps: List<Snapshot>, weights: Map<Factor, Double>, filters: Filters): List<Scored> =
        factorScores(applyFilters(snaps, filters))
            .map { it.copy(score = composite(it.factors, weights)) }
            .filter { !it.score.isNaN() }
            .sortedByDescending { it.score }

    fun recommend(snaps: List<Snapshot>, weights: Map<Factor, Double>, filters: Filters = Filters(), topN: Int = 20): List<Recommendation> {
        val scored = scoreAll(snaps, weights, filters)
        return scored.take(topN).mapIndexed { i, s ->
            Recommendation(i + 1, s.snap, s.factors, s.score, scored.size, reasons(s), warnings(s.snap))
        }
    }

    // ------------------------------------------------------------ 추천 사유
    private fun Double.orZero() = if (isNaN()) 0.0 else this
    private fun Double.ok() = !isNaN()
    private fun fmtPct(x: Double) = String.format(Locale.US, "%+.1f%%", x * 100)
    private fun f0(x: Double) = String.format(Locale.US, "%.0f", x)

    fun reasons(sc: Scored): List<String> {
        val r = sc.snap
        val fac = sc.factors
        val out = mutableListOf<String>()
        val align = r[F.MA_ALIGN]
        if (align == 1.0) out += "이동평균 완전 정배열 (종가>5>20>60>120일선)"
        else if (align >= 0.75) out += "이동평균 정배열에 근접"
        val fm = fac[Factor.MOMENTUM] ?: NaN
        if (r[F.MOM_12_1].ok() && fm >= 80) out += "12-1개월 모멘텀 ${fmtPct(r[F.MOM_12_1])} (상위 ${f0(100 - fm)}%)"
        if (r[F.HIGH_52W].ok() && r[F.HIGH_52W] >= 0.95) out += "52주 최고가 대비 ${fmtPct(r[F.HIGH_52W] - 1)} — 신고가 근접"
        if ((fac[Factor.STABILITY] ?: NaN) >= 75 && r[F.VOL_20].ok())
            out += "변동성 낮음 (연 ${f0(r[F.VOL_20] * 100)}%, 120일 MDD ${f0(r[F.MDD_120] * 100)}%)"
        if (r[F.VOL_RATIO].ok() && r[F.VOL_RATIO] >= 1.5)
            out += "최근 거래량 60일 평균 대비 ${String.format(Locale.US, "%.1f", r[F.VOL_RATIO])}배"
        if (r[F.RSI14].ok() && r[F.RSI14] in 45.0..65.0) out += "RSI ${f0(r[F.RSI14])} — 과열 아닌 적정 구간"
        if (align >= 0.5 && r[F.RET_20].ok() && r[F.RET_20] < -0.03 && r[F.DIST_MA120].ok() && r[F.DIST_MA120] > 0)
            out += "120일선 위에서 1개월 ${fmtPct(r[F.RET_20])} 조정 (눌림목)"
        if (r[F.MACD_HIST].ok() && r[F.MACD_HIST] > 0) out += "MACD 히스토그램 양전환 상태"
        if ((fac[Factor.VALUE] ?: NaN) >= 80)
            out += "밸류 상위권 (PER ${String.format(Locale.US, "%.1f", r.info.per)}, PBR ${String.format(Locale.US, "%.2f", r.info.pbr)})"
        return out.ifEmpty { listOf("여러 지표가 고르게 평균 이상") }
    }

    fun warnings(r: Snapshot): List<String> {
        val out = mutableListOf<String>()
        if (r[F.RSI14].ok() && r[F.RSI14] >= 75) out += "RSI ${f0(r[F.RSI14])} 과열 — 단기 조정 가능성"
        if (r[F.DIST_MA20].ok() && r[F.DIST_MA20] >= 0.15) out += "20일선 이격도 ${fmtPct(r[F.DIST_MA20])} — 단기 급등"
        if (r[F.VOL_20].ok() && r[F.VOL_20] >= 0.6) out += "변동성 매우 큼 (연 ${f0(r[F.VOL_20] * 100)}%)"
        if (r[F.DIST_MA120].ok() && r[F.DIST_MA120] < 0) out += "120일선 아래 — 장기 추세 약세"
        if (r[F.BB_PCTB].ok() && r[F.BB_PCTB] > 1) out += "볼린저밴드 상단 돌파"
        return out
    }
}
