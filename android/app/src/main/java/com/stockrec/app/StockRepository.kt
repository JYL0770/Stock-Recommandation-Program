package com.stockrec.app

import android.content.Context
import com.stockrec.core.Bar
import com.stockrec.core.BuyZone
import com.stockrec.core.DemoData
import com.stockrec.core.NaverClient
import com.stockrec.core.StockInfo
import com.stockrec.core.StockSeries
import com.stockrec.core.Universe
import com.stockrec.core.YahooClient
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

/** 시세 데이터 로드 + 휴대폰 내부 저장소 캐시 (국내/해외 따로) */
class StockRepository(context: Context) {
    private val root = File(context.filesDir, "prices")
    private val naver = NaverClient()
    private val yahoo = YahooClient()

    companion object {
        const val KR_HISTORY_DAYS = 800       // 거래일 수 (약 3년)
        const val US_HISTORY_DAYS = 1200      // 달력 일수 (약 3.3년)
        const val CACHE_TTL_MS = 3 * 60 * 60 * 1000L
        private const val PARALLEL = 4
        const val INDEX_MARKET = "INDEX"

        fun indexesOf(r: Region) = if (r == Region.KR) BuyZone.KR_INDEXES else BuyZone.US_INDEXES

        /** 해외 직접 입력 심볼 정리: 영문·숫자·. - ^ = 만 */
        fun cleanUsSymbol(s: String) = s.trim().uppercase().filter { it.isLetterOrDigit() || it in ".-^=" }
    }

    private fun dir(r: Region) = File(root, r.name).apply { mkdirs() }

    // ------------------------------------------------------------ 데모
    fun demo(r: Region): LoadResult {
        val stocks = if (r == Region.KR) DemoData.generate(nDays = 780) else
            DemoData.generate(nStocks = 60, nDays = 780, seed = 21).mapIndexed { i, s ->
                // 해외 데모: 달러 가격대, NASDAQ / NYSE / OTC 로 나눔
                val m = listOf("NASDAQ", "NYSE", "OTC")[i % 3]
                StockSeries(
                    s.info.copy(ticker = "DEMO${i + 1}", name = "Demo Stock ${i + 1} ($m)", market = m),
                    s.bars.map { b -> b.copy(open = b.open / 1000, high = b.high / 1000, low = b.low / 1000, close = b.close / 1000) },
                )
            }
        val idx = indexesOf(r).map { (code, name) ->
            val members = stocks.filter { BuyZone.indexFor(it.info.market) == code }
            BuyZone.makeIndex(code, members).copy(info = StockInfo(code, name, INDEX_MARKET))
        }
        val note = if (r == Region.KR) "가상 데모종목 80개 + 데모 지수" else "가상 해외 데모종목 60개 + 데모 지수"
        return LoadResult(stocks + idx, emptyList(), System.currentTimeMillis(), false, note)
    }

    // ------------------------------------------------------------ 실시간
    suspend fun live(
        s: Settings,
        force: Boolean,
        progress: (done: Int, total: Int, msg: String) -> Unit,
    ): LoadResult = withContext(Dispatchers.IO) {
        val r = s.region
        val key = s.universeKey()
        if (!force) readCache(r, key)?.let { return@withContext it }

        progress(0, 1, "종목 목록 받는 중...")
        val (universe, note) = if (r == Region.KR) resolveKr(s) else resolveUs(s)
        val total = universe.size
        val done = AtomicInteger(0)
        val sem = Semaphore(PARALLEL)
        fun fetch(t: String) = if (r == Region.KR) naver.fetchDaily(t, KR_HISTORY_DAYS) else yahoo.fetchDaily(t, US_HISTORY_DAYS)

        val results = coroutineScope {
            universe.map { info ->
                async {
                    sem.withPermit {
                        val res = runCatching { fetch(info.ticker) }
                        progress(done.incrementAndGet(), total, "시세 받는 중 ${info.name}")
                        info to res
                    }
                }
            }.awaitAll()
        }
        val ok = results.mapNotNull { (info, res) ->
            res.getOrNull()?.let {
                if (r == Region.KR) StockSeries(info.copy(name = it.info.name.takeIf { n -> n != info.ticker } ?: info.name), it.bars)
                else it   // 해외: 이름·시장(NYSE/NASDAQ/OTC)은 시세 응답 기준
            }
        }
        val failed = results.filter { it.second.isFailure }.map { it.first.ticker }.toMutableList()

        progress(total, total, "지수 받는 중")
        val indexes = indexesOf(r).mapNotNull { (code, name) ->
            runCatching { fetch(code) }
                .onFailure { failed += code }
                .getOrNull()?.let { StockSeries(StockInfo(code, name, INDEX_MARKET), it.bars) }
        }
        if (ok.isEmpty()) {
            // 네트워크 실패 시 오래된 캐시라도 사용
            readCache(r, key, ignoreTtl = true)?.let { return@withContext it.copy(universeNote = "오프라인: 이전 데이터 사용") }
            throw IllegalStateException("시세를 받지 못했습니다. 인터넷 연결을 확인하세요.\n(${results.firstOrNull()?.second?.exceptionOrNull()?.message})")
        }
        val now = System.currentTimeMillis()
        writeCache(r, key, ok + indexes, now, note)
        LoadResult(ok + indexes, failed, now, false, note)
    }

    private fun resolveKr(s: Settings): Pair<List<StockInfo>, String> {
        val notes = mutableListOf<String>()
        val list = mutableListOf<StockInfo>()
        for ((market, n) in listOf("KOSPI" to s.kospiCount, "KOSDAQ" to s.kosdaqCount)) {
            if (n <= 0) continue
            val live = runCatching { naver.fetchTopByMarketCap(market, n) }.getOrDefault(emptyList())
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

    private fun resolveUs(s: Settings): Pair<List<StockInfo>, String> {
        val notes = mutableListOf<String>()
        val syms = mutableListOf<String>()
        if (s.usLarge) { syms += Universe.US_LARGE; notes += "미국 대형주 ${Universe.US_LARGE.size}" }
        if (s.usOtc) { syms += Universe.US_OTC; notes += "OTC·핑크시트 ${Universe.US_OTC.size}" }
        val custom = s.usCustom.map(::cleanUsSymbol).filter { it.isNotEmpty() && it !in syms }
        syms += custom
        if (custom.isNotEmpty()) notes += "직접추가 ${custom.size}"
        return syms.distinct().map { StockInfo(it, it, "US") } to notes.joinToString(", ")
    }

    // ------------------------------------------------------------ 캐시 (종목별 CSV)
    private fun fileName(ticker: String) = ticker.map { if (it.isLetterOrDigit()) it else '_' }.joinToString("") + ".csv"

    private fun readCache(r: Region, key: String, ignoreTtl: Boolean = false): LoadResult? = runCatching {
        val dir = dir(r)
        val metaFile = File(dir, "_meta.txt")
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

    private fun writeCache(r: Region, key: String, series: List<StockSeries>, at: Long, note: String) {
        val dir = dir(r)
        dir.listFiles()?.forEach { it.delete() }
        for (s in series) {
            File(dir, fileName(s.info.ticker)).bufferedWriter().use { w ->
                w.write("#${s.info.ticker}\t${s.info.name.replace('\t', ' ')}\t${s.info.market}\n")
                for (b in s.bars) w.write("${b.date},${b.open},${b.high},${b.low},${b.close},${b.volume}\n")
            }
        }
        File(dir, "_meta.txt").writeText("$key\n$at\n$note\n")
    }
}
