package com.stockrec.app

import android.content.Context

enum class DataSource(val label: String) { LIVE("실시간 시세"), DEMO("데모 (가상 종목)") }

enum class Region(val label: String, val source: String) {
    KR("국내", "네이버 금융"),
    US("해외 (미국·핑크시트)", "Yahoo Finance"),
}

enum class Scope(val label: String, val desc: String) {
    ALL("전체 종목", "매일 장 마감 후 서버가 시장 전체를 스캔한 결과를 받아, 후보 종목만 최신 시세로 다시 확인"),
    CUSTOM("직접 선택", "아래 범위의 종목을 휴대폰에서 직접 받아 분석"),
}

data class Settings(
    val source: DataSource = DataSource.LIVE,
    val region: Region = Region.KR,
    val scope: Scope = Scope.ALL,
    // 국내 종목 범위
    val kospiCount: Int = 100,          // 시가총액 상위 N
    val kosdaqCount: Int = 50,
    val customTickers: List<String> = emptyList(),
    // 해외 종목 범위
    val usLarge: Boolean = true,        // 미국 대형주 (NYSE·NASDAQ)
    val usOtc: Boolean = true,          // 장외시장 (OTC·핑크시트)
    val usCustom: List<String> = emptyList(),
    val trendWidth: Double = 0.10,      // 추세선 채널 폭: 상단선 = 지지선 × (1 + 폭)
) {
    /** 이 값이 바뀌면 시세를 다시 받아야 함 (지역별로 따로 캐시) */
    fun universeKey(r: Region = region) = when (r) {
        Region.KR -> "v5|KR|$source|$scope|$kospiCount|$kosdaqCount|${customTickers.sorted().joinToString(",")}"
        Region.US -> "v5|US|$source|$scope|$usLarge|$usOtc|${usCustom.sorted().joinToString(",")}"
    }
}

class SettingsStore(context: Context) {
    private val p = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    fun load(): Settings {
        val d = Settings()
        fun list(k: String) = p.getString(k, "")!!.split(',').filter { it.isNotBlank() }
        return Settings(
            source = runCatching { DataSource.valueOf(p.getString("source", d.source.name)!!) }.getOrDefault(d.source),
            region = runCatching { Region.valueOf(p.getString("region", d.region.name)!!) }.getOrDefault(d.region),
            scope = runCatching { Scope.valueOf(p.getString("scope", d.scope.name)!!) }.getOrDefault(d.scope),
            kospiCount = p.getInt("kospiCount", d.kospiCount),
            kosdaqCount = p.getInt("kosdaqCount", d.kosdaqCount),
            customTickers = list("customTickers"),
            usLarge = p.getBoolean("usLarge", d.usLarge),
            usOtc = p.getBoolean("usOtc", d.usOtc),
            usCustom = list("usCustom"),
            trendWidth = Math.round(p.getFloat("trendWidth", d.trendWidth.toFloat()) * 100) / 100.0,
        )
    }

    fun save(s: Settings) {
        p.edit()
            .putString("source", s.source.name)
            .putString("region", s.region.name)
            .putString("scope", s.scope.name)
            .putInt("kospiCount", s.kospiCount)
            .putInt("kosdaqCount", s.kosdaqCount)
            .putString("customTickers", s.customTickers.joinToString(","))
            .putBoolean("usLarge", s.usLarge)
            .putBoolean("usOtc", s.usOtc)
            .putString("usCustom", s.usCustom.joinToString(","))
            .putFloat("trendWidth", s.trendWidth.toFloat())
            .apply()
    }
}
