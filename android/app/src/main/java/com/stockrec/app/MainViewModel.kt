package com.stockrec.app

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.stockrec.core.BuyZone
import com.stockrec.core.BuyZoneResult
import com.stockrec.core.FeatureSeries
import com.stockrec.core.IndexState
import com.stockrec.core.Indicators
import com.stockrec.core.StockSeries
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate

data class UiState(
    val settings: Settings = Settings(),
    val loading: Boolean = false,
    val progress: Float = 0f,
    val progressText: String = "",
    val error: String? = null,
    val asOf: LocalDate? = null,
    val nStocks: Int = 0,
    val failed: List<String> = emptyList(),
    val fetchedAt: Long? = null,
    val fromCache: Boolean = false,
    val universeNote: String = "",
    /** 지금 화면에 있는 데이터의 지역 */
    val loadedRegion: Region? = null,
    /** 종목별 4개 차트 매수권 판정 (정렬됨) */
    val buyZone: List<BuyZoneResult> = emptyList(),
    /** 지수별 일봉·주봉 채널 */
    val indexes: Map<String, IndexState> = emptyMap(),
    val buyZoneComputing: Boolean = false,
    /** 전체 종목 모드: 서버 스캔 요약 (분석 종목 수, 스캔 시각 등) */
    val scan: com.stockrec.core.ScanSummary? = null,
)

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val store = SettingsStore(app)
    private val repo = StockRepository(app)

    private val _state = MutableStateFlow(UiState(settings = store.load()))
    val state: StateFlow<UiState> = _state.asStateFlow()

    /** 종목별 일봉 + 이동평균 등 (상세 화면 차트용) */
    var features: Map<String, FeatureSeries> = emptyMap()
        private set
    private var indexData: List<StockSeries> = emptyList()
    private var loadedKey: String? = null
    private var loadJob: Job? = null
    private var buyZoneJob: Job? = null

    init {
        reload(force = false)
    }

    // ------------------------------------------------------------ 데이터
    fun reload(force: Boolean) {
        loadJob?.cancel()
        buyZoneJob?.cancel()
        val s = _state.value.settings
        loadJob = viewModelScope.launch {
            _state.update {
                it.copy(loading = true, error = null, progress = 0f, progressText = "불러오는 중...",
                    buyZone = if (it.loadedRegion == s.region) it.buyZone else emptyList(),
                    indexes = if (it.loadedRegion == s.region) it.indexes else emptyMap())
            }
            try {
                val res = if (s.source == DataSource.DEMO) repo.demo(s.region) else repo.live(s, force) { done, total, msg ->
                    _state.update { it.copy(progress = done.toFloat() / total, progressText = "$msg ($done/$total)") }
                }
                _state.update { it.copy(progressText = "추세선 계산 중...") }
                val (idx, stocks) = res.series.partition { it.info.market == StockRepository.INDEX_MARKET }
                val feats = withContext(Dispatchers.Default) {
                    stocks.filter { it.bars.size >= 30 }.map(Indicators::compute)
                }
                features = feats.associateBy { it.info.ticker }
                indexData = idx
                loadedKey = s.universeKey()
                _state.update {
                    it.copy(
                        loading = false, asOf = feats.maxOfOrNull { f -> f.bars.last().date }, nStocks = feats.size,
                        failed = res.failed, fetchedAt = res.fetchedAt, fromCache = res.fromCache,
                        universeNote = res.universeNote, loadedRegion = s.region, scan = res.scan,
                    )
                }
                recomputeBuyZone()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, error = e.message ?: e.toString()) }
            }
        }
    }

    /** 종목 일봉·주봉 + 지수 일봉·주봉 4개 차트 판정 */
    private fun recomputeBuyZone() {
        buyZoneJob?.cancel()
        val width = _state.value.settings.trendWidth
        val stocks = features.values.map { StockSeries(it.info, it.bars) }
        val idxSeries = indexData
        _state.update { it.copy(buyZoneComputing = true) }
        buyZoneJob = viewModelScope.launch {
            val (idx, results) = withContext(Dispatchers.Default) {
                val idx = idxSeries.associate { it.info.ticker to BuyZone.index(it.info.ticker, it, width) }
                idx to BuyZone.sort(stocks.map { BuyZone.evaluate(it, idx[BuyZone.indexFor(it.info.market)], width) })
            }
            _state.update { it.copy(buyZone = results, indexes = idx, buyZoneComputing = false) }
        }
    }

    fun buyZoneFor(ticker: String): BuyZoneResult? = _state.value.buyZone.firstOrNull { it.info.ticker == ticker }

    // ------------------------------------------------------------ 설정
    fun updateSettings(transform: (Settings) -> Settings) {
        val old = _state.value.settings
        val new = transform(old)
        store.save(new)
        _state.update { it.copy(settings = new) }
        when {
            new.region != old.region || new.source != old.source || new.scope != old.scope -> reload(force = false)
            new.trendWidth != old.trendWidth -> recomputeBuyZone()
        }
    }

    /** 종목 범위(시총 상위 N, 직접 추가 등)를 바꾼 뒤 적용 */
    fun applyUniverse() {
        if (needsReload()) reload(force = true)
    }

    fun needsReload() = _state.value.settings.universeKey() != loadedKey

    /** CI 스크린샷 / 첫 실행 체험용: 저장하지 않고 데모 모드로 전환 */
    fun forceDemoSession() {
        if (_state.value.settings.source == DataSource.DEMO) return
        _state.update { it.copy(settings = it.settings.copy(source = DataSource.DEMO)) }
        reload(force = false)
    }
}
