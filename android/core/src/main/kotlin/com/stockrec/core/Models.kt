package com.stockrec.core

import java.time.LocalDate

/** 일봉 한 개. amount = 거래대금(백만원), 없으면 NaN */
data class Bar(
    val date: LocalDate,
    val open: Double,
    val high: Double,
    val low: Double,
    val close: Double,
    val volume: Double,
    val amount: Double = Double.NaN,
)

/** 종목 기본 정보. per/pbr/roe 는 없으면 NaN */
data class StockInfo(
    val ticker: String,
    val name: String = ticker,
    val market: String = "",
    val sector: String = "",
    val per: Double = Double.NaN,
    val pbr: Double = Double.NaN,
    val roe: Double = Double.NaN,
)

data class StockSeries(val info: StockInfo, val bars: List<Bar>)

/** 지표 키 (Python recommender/indicators.py 와 동일한 이름) */
object F {
    const val RET_20 = "ret_20"
    const val RET_60 = "ret_60"
    const val RET_120 = "ret_120"
    const val RET_250 = "ret_250"
    const val MOM_12_1 = "mom_12_1"
    const val MA5 = "ma5"
    const val MA20 = "ma20"
    const val MA60 = "ma60"
    const val MA120 = "ma120"
    const val MA_ALIGN = "ma_align"
    const val DIST_MA20 = "dist_ma20"
    const val DIST_MA60 = "dist_ma60"
    const val DIST_MA120 = "dist_ma120"
    const val MA20_SLOPE = "ma20_slope"
    const val RSI14 = "rsi14"
    const val MACD_HIST = "macd_hist"
    const val BB_PCTB = "bb_pctb"
    const val HIGH_52W = "high_52w"
    const val LOW_52W = "low_52w"
    const val VOL_20 = "vol_20"
    const val VOL_RATIO = "vol_ratio"
    const val AMOUNT_20 = "amount_20"
    const val MDD_120 = "mdd_120"
    const val N_DAYS = "n_days"

    val DESCRIPTIONS = linkedMapOf(
        RET_20 to "1개월(20거래일) 수익률",
        RET_60 to "3개월(60거래일) 수익률",
        RET_120 to "6개월(120거래일) 수익률",
        RET_250 to "12개월(250거래일) 수익률",
        MOM_12_1 to "12-1개월 모멘텀",
        MA_ALIGN to "이동평균 정배열 점수 (0~1)",
        DIST_MA20 to "20일선 이격도",
        DIST_MA60 to "60일선 이격도",
        DIST_MA120 to "120일선 이격도",
        MA20_SLOPE to "20일선 기울기 (5일 변화율)",
        RSI14 to "RSI(14)",
        MACD_HIST to "MACD 히스토그램 (종가 대비)",
        BB_PCTB to "볼린저밴드 %B",
        HIGH_52W to "52주 최고가 대비 위치",
        LOW_52W to "52주 최저가 대비 상승률",
        VOL_20 to "20일 연환산 변동성",
        VOL_RATIO to "거래량 비율 (5일/60일)",
        AMOUNT_20 to "20일 평균 거래대금 (백만원)",
        MDD_120 to "120일 최대낙폭",
        N_DAYS to "데이터 일수",
    )

    /** 퍼센트로 보여줄 지표 */
    val PERCENT = setOf(RET_20, RET_60, RET_120, RET_250, MOM_12_1, DIST_MA20, DIST_MA60, DIST_MA120,
        MA20_SLOPE, MACD_HIST, LOW_52W, VOL_20, MDD_120)
}

/** 종목별 전 기간 지표 (차트·백테스트용) */
class FeatureSeries(val info: StockInfo, val bars: List<Bar>, val values: Map<String, DoubleArray>) {
    val size get() = bars.size
    operator fun get(key: String): DoubleArray = values.getValue(key)
    fun snapshotAt(i: Int): Snapshot =
        Snapshot(info, bars[i].date, bars[i].close, values.mapValues { it.value[i] })
    fun latest(): Snapshot = snapshotAt(size - 1)
    fun indexOf(date: LocalDate): Int = bars.binarySearchBy(date) { it.date }
}

/** 한 시점의 종목 지표 한 줄 */
data class Snapshot(val info: StockInfo, val date: LocalDate, val close: Double, val values: Map<String, Double>) {
    val ticker get() = info.ticker
    operator fun get(key: String): Double = values[key] ?: Double.NaN
}
