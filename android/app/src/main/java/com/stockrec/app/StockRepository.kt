package com.stockrec.app

import android.content.Context
import com.stockrec.core.Bar
import com.stockrec.core.DemoData
import com.stockrec.core.NaverClient
import com.stockrec.core.StockInfo
import com.stockrec.core.StockSeries
import com.stockrec.core.Universe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDate
import java.util.concurrent.atomic.AtomicInteger

data class LoadResult(
    val series: List<StockSeries>,
    val failed: List<String>,
    val fetchedAt: Long,
    val fromCache: Boolean,
    val universeNote: String,
)

/** 시세 데이터 로드 + 휴대폰 내부 저장소 캐시 */
class StockRepository(context: Context) {
    private val dir = File(context.filesDir, "prices").apply { mkdirs() }
    private val metaFile = File(dir, "_meta.txt")
    private val client = NaverClient()

    companion object {
        const val HISTORY_DAYS = 520          // 약 2년 (12개월 모멘텀 + 백테스트용)
        const val CACHE_TTL_MS = 3 * 60 * 60 * 1000L
        private const val PARALLEL = 6
    }

    fun demo(): LoadResult =
        LoadResult(DemoData.generate(), emptyList(), System.currentTimeMillis(), false, "가상 데모종목 80개")

    suspend fun live(
        s: Settings,
        force: Boolean,
        progress: (done: Int, total: Int, msg: String) -> Unit,
    ): LoadResult = withContext(Dispatchers.IO) {
        val key = s.universeKey()
        if (!force) readCache(key)?.let { return@withContext it }

        progress(0, 1, "종목 목록 받는 중...")
        val (universe, note) = resolveUniverse(s)
        val total = universe.size
        val done = AtomicInteger(0)
        val sem = Semaphore(PARALLEL)

        val results = coroutineScope {
            universe.map { info ->
                async {
                    sem.withPermit {
                        val r = runCatching { client.fetchDaily(info.ticker, HISTORY_DAYS) }
                        progress(done.incrementAndGet(), total, "시세 받는 중 ${info.name}")
                        info to r
                    }
                }
            }.awaitAll()
        }
        val ok = results.mapNotNull { (info, r) ->
            r.getOrNull()?.let { StockSeries(info.copy(name = it.info.name.takeIf { n -> n != info.ticker } ?: info.name), it.bars) }
        }
        val failed = results.filter { it.second.isFailure }.map { it.first.ticker }
        if (ok.isEmpty()) {
            // 네트워크 실패 시 오래된 캐시라도 사용
            readCache(key, ignoreTtl = true)?.let { return@withContext it.copy(universeNote = "오프라인: 이전 데이터 사용") }
            throw IllegalStateException("시세를 받지 못했습니다. 인터넷 연결을 확인하세요.\n(${results.firstOrNull()?.second?.exceptionOrNull()?.message})")
        }
        val now = System.currentTimeMillis()
        writeCache(key, ok, now, note)
        LoadResult(ok, failed, now, false, note)
    }

    private fun resolveUniverse(s: Settings): Pair<List<StockInfo>, String> {
        val notes = mutableListOf<String>()
        val list = mutableListOf<StockInfo>()
        for ((market, n) in listOf("KOSPI" to s.kospiCount, "KOSDAQ" to s.kosdaqCount)) {
            if (n <= 0) continue
            val live = runCatching { client.fetchTopByMarketCap(market, n) }.getOrDefault(emptyList())
            if (live.size >= minOf(n, 20)) {
                list += live
                notes += "$market 시총 상위 ${live.size}"
            } else {
                val fallback = Universe.DEFAULT.filter { it.market == market }.take(n)
                list += fallback
                notes += "$market 기본목록 ${fallback.size}"
            }
        }
        val known = list.map { it.ticker }.toSet()
        val custom = s.customTickers.map { it.trim() }.filter { it.length == 6 && it !in known }
        list += custom.map { StockInfo(it, it, "직접추가") }
        if (custom.isNotEmpty()) notes += "직접추가 ${custom.size}"
        return list.distinctBy { it.ticker } to notes.joinToString(", ")
    }

    // ------------------------------------------------------------ 캐시 (종목별 CSV)
    private fun readCache(key: String, ignoreTtl: Boolean = false): LoadResult? = runCatching {
        if (!metaFile.exists()) return null
        val meta = metaFile.readLines()
        if (meta.getOrNull(0) != key) return null
        val fetchedAt = meta[1].toLong()
        if (!ignoreTtl && System.currentTimeMillis() - fetchedAt > CACHE_TTL_MS) return null
        val note = meta.getOrElse(2) { "" }
        val series = dir.listFiles { f -> f.name.endsWith(".csv") }!!.mapNotNull { f ->
            val lines = f.readLines()
            val head = lines.firstOrNull()?.removePrefix("#")?.split('\t') ?: return@mapNotNull null
            val bars = lines.drop(1).map {
                val p = it.split(',')
                Bar(LocalDate.parse(p[0]), p[1].toDouble(), p[2].toDouble(), p[3].toDouble(), p[4].toDouble(),
                    p[5].toDouble(), p[4].toDouble() * p[5].toDouble() / 1e6)
            }
            StockSeries(StockInfo(head[0], head.getOrElse(1) { head[0] }, head.getOrElse(2) { "" }), bars)
        }
        if (series.isEmpty()) null else LoadResult(series, emptyList(), fetchedAt, true, note)
    }.getOrNull()

    private fun writeCache(key: String, series: List<StockSeries>, at: Long, note: String) {
        dir.listFiles()?.forEach { it.delete() }
        for (s in series) {
            File(dir, "${s.info.ticker}.csv").bufferedWriter().use { w ->
                w.write("#${s.info.ticker}\t${s.info.name}\t${s.info.market}\n")
                for (b in s.bars) w.write("${b.date},${b.open},${b.high},${b.low},${b.close},${b.volume}\n")
            }
        }
        metaFile.writeText("$key\n$at\n$note\n")
    }
}
