package com.stockrec.app

import android.content.Context
import com.stockrec.core.Factor
import com.stockrec.core.Filters
import com.stockrec.core.STRATEGIES

enum class DataSource(val label: String) { LIVE("실시간 시세 (네이버 금융)"), DEMO("데모 (가상 종목)") }

data class Settings(
    val source: DataSource = DataSource.LIVE,
    val strategyIndex: Int = 0,
    val weights: Map<Factor, Double> = STRATEGIES[0].weights,
    val minAmountEok: Double = 10.0,   // 20일 평균 거래대금 하한 (억원)
    val minDays: Int = 250,
    val maxRsi: Double? = null,
    val aboveMa120: Boolean = false,
    val markets: Set<String> = setOf("KOSPI", "KOSDAQ", "직접추가"),
    val topN: Int = 20,
    val kospiCount: Int = 100,          // 시가총액 상위 N
    val kosdaqCount: Int = 50,
    val customTickers: List<String> = emptyList(),
) {
    val strategy get() = STRATEGIES[strategyIndex]
    val isCustomWeights get() = weights.filterValues { it > 0 } != strategy.weights.filterValues { it > 0 }

    fun filters() = Filters(
        minAmount = minAmountEok * 100, minDays = minDays, maxRsi = maxRsi,
        aboveMa120 = aboveMa120, markets = markets,
    )

    /** 이 값이 바뀌면 시세를 다시 받아야 함 */
    fun universeKey() = "$source|$kospiCount|$kosdaqCount|${customTickers.sorted().joinToString(",")}"
}

class SettingsStore(context: Context) {
    private val p = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    fun load(): Settings {
        val d = Settings()
        val idx = p.getInt("strategy", 0).coerceIn(STRATEGIES.indices)
        val weights = p.getString("weights", null)?.let(::parseWeights) ?: STRATEGIES[idx].weights
        return Settings(
            source = runCatching { DataSource.valueOf(p.getString("source", d.source.name)!!) }.getOrDefault(d.source),
            strategyIndex = idx,
            weights = weights,
            minAmountEok = p.getFloat("minAmountEok", d.minAmountEok.toFloat()).toDouble(),
            minDays = p.getInt("minDays", d.minDays),
            maxRsi = p.getFloat("maxRsi", -1f).takeIf { it > 0 }?.toDouble(),
            aboveMa120 = p.getBoolean("aboveMa120", d.aboveMa120),
            markets = p.getStringSet("markets", d.markets)!!.toSet(),
            topN = p.getInt("topN", d.topN),
            kospiCount = p.getInt("kospiCount", d.kospiCount),
            kosdaqCount = p.getInt("kosdaqCount", d.kosdaqCount),
            customTickers = p.getString("customTickers", "")!!.split(',').filter { it.isNotBlank() },
        )
    }

    fun save(s: Settings) {
        p.edit()
            .putString("source", s.source.name)
            .putInt("strategy", s.strategyIndex)
            .putString("weights", s.weights.entries.joinToString(",") { "${it.key.key}:${it.value}" })
            .putFloat("minAmountEok", s.minAmountEok.toFloat())
            .putInt("minDays", s.minDays)
            .putFloat("maxRsi", s.maxRsi?.toFloat() ?: -1f)
            .putBoolean("aboveMa120", s.aboveMa120)
            .putStringSet("markets", s.markets)
            .putInt("topN", s.topN)
            .putInt("kospiCount", s.kospiCount)
            .putInt("kosdaqCount", s.kosdaqCount)
            .putString("customTickers", s.customTickers.joinToString(","))
            .apply()
    }

    private fun parseWeights(s: String): Map<Factor, Double>? = runCatching {
        s.split(',').filter { it.isNotBlank() }.associate {
            val (k, v) = it.split(':')
            Factor.of(k) to v.toDouble()
        }
    }.getOrNull()
}
