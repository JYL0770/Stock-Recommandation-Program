package com.stockrec.app

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.stockrec.core.Backtest
import com.stockrec.core.Channel
import com.stockrec.core.ChannelParams
import com.stockrec.core.TrendChannel
import com.stockrec.core.BacktestResult
import com.stockrec.core.FeatureSeries
import com.stockrec.core.Indicators
import com.stockrec.core.Recommendation
import com.stockrec.core.STRATEGIES
import com.stockrec.core.Scored
import com.stockrec.core.Scoring
import com.stockrec.core.Snapshot
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
    val recommendations: List<Recommendation> = emptyList(),
    val nCandidates: Int = 0,
    val backtest: BacktestResult? = null,
    val backtestRunning: Boolean = false,
    val backtestTopN: Int = 10,
    val backtestHold: Int = 20,
    /** 주봉 추세선 채널 (모든 상태) */
    val channels: List<Channel> = emptyList(),
)

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val store = SettingsStore(app)
    private val repo = StockRepository(app)

    private val _state = MutableStateFlow(UiState(settings = store.load()))
    val state: StateFlow<UiState> = _state.asStateFlow()

    /** 전체 지표 (ticker → 시계열) */
    var features: Map<String, FeatureSeries> = emptyMap()
        private set
    private var snaps: List<Snapshot> = emptyList()
    private var loadedKey: String? = null
    private var loadJob: Job? = null
    private var scoreJob: Job? = null

    init {
        reload(force = false)
    }

    // ------------------------------------------------------------ 데이터
    fun reload(force: Boolean) {
        loadJob?.cancel()
        val s = _state.value.settings
        loadJob = viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null, progress = 0f, progressText = "불러오는 중...") }
            try {
                val res = if (s.source == DataSource.DEMO) repo.demo() else repo.live(s, force) { done, total, msg ->
                    _state.update { it.copy(progress = done.toFloat() / total, progressText = "$msg ($done/$total)") }
                }
                _state.update { it.copy(progressText = "지표 계산 중...") }
                val feats = withContext(Dispatchers.Default) {
                    res.series.filter { it.bars.size >= 30 }.map(Indicators::compute)
                }
                features = feats.associateBy { it.info.ticker }
                snaps = feats.map { it.latest() }
                loadedKey = s.universeKey()
                _state.update {
                    it.copy(
                        loading = false, asOf = snaps.maxOfOrNull { s -> s.date }, nStocks = snaps.size,
                        failed = res.failed, fetchedAt = res.fetchedAt, fromCache = res.fromCache,
                        universeNote = res.universeNote, backtest = null,
                    )
                }
                rescore()
                recomputeChannels()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, error = e.message ?: e.toString()) }
            }
        }
    }

    private fun rescore() {
        scoreJob?.cancel()
        val s = _state.value.settings
        val current = snaps
        scoreJob = viewModelScope.launch {
            val (recs, n) = withContext(Dispatchers.Default) {
                val all = Scoring.scoreAll(current, s.weights, s.filters())
                all.take(s.topN).mapIndexed { i, sc ->
                    Recommendation(i + 1, sc.snap, sc.factors, sc.score, all.size, Scoring.reasons(sc), Scoring.warnings(sc.snap))
                } to all.size
            }
            _state.update { it.copy(recommendations = recs, nCandidates = n) }
        }
    }

    private var channelJob: Job? = null

    private fun recomputeChannels() {
        channelJob?.cancel()
        val width = _state.value.settings.trendWidth
        val feats = features.values.toList()
        channelJob = viewModelScope.launch {
            val chs = withContext(Dispatchers.Default) {
                val p = ChannelParams(width = width)
                TrendChannel.sort(feats.mapNotNull { TrendChannel.fit(it.info, TrendChannel.weekly(it.bars), p) })
            }
            _state.update { it.copy(channels = chs) }
        }
    }

    fun channelFor(ticker: String): Channel? = _state.value.channels.firstOrNull { it.info.ticker == ticker }

    /** 상세 화면용: 이 종목의 팩터 점수 (필터 통과 못 하면 전체 기준) */
    fun scoredFor(ticker: String): Pair<Scored?, Boolean> {
        val s = _state.value.settings
        val pool = Scoring.applyFilters(snaps, s.filters())
        val inPool = pool.any { it.ticker == ticker }
        val scored = Scoring.factorScores(if (inPool) pool else snaps).firstOrNull { it.snap.ticker == ticker }
        return scored?.copy(score = Scoring.composite(scored.factors, s.weights)) to inPool
    }

    // ------------------------------------------------------------ 설정
    fun updateSettings(transform: (Settings) -> Settings) {
        val old = _state.value.settings
        val new = transform(old)
        store.save(new)
        _state.update { it.copy(settings = new, backtest = if (new.weights != old.weights || new.filters() != old.filters()) null else it.backtest) }
        if (new.universeKey() != loadedKey && (new.source != old.source)) reload(force = false) else {
            rescore()
            if (new.trendWidth != old.trendWidth) recomputeChannels()
        }
    }

    fun selectStrategy(i: Int) = updateSettings { it.copy(strategyIndex = i, weights = STRATEGIES[i].weights) }

    /** 종목 범위(시총 상위 N, 직접 추가)를 바꾼 뒤 적용 */
    fun applyUniverse() {
        if (_state.value.settings.universeKey() != loadedKey) reload(force = true)
    }

    fun needsReload() = _state.value.settings.universeKey() != loadedKey

    // ------------------------------------------------------------ 백테스트
    fun setBacktestParams(topN: Int? = null, hold: Int? = null) =
        _state.update { it.copy(backtestTopN = topN ?: it.backtestTopN, backtestHold = hold ?: it.backtestHold) }

    fun runBacktest() {
        val st = _state.value
        val feats = features.values.toList()
        _state.update { it.copy(backtestRunning = true) }
        viewModelScope.launch {
            val res = withContext(Dispatchers.Default) {
                Backtest.run(feats, st.settings.weights, st.settings.filters(), st.backtestTopN, st.backtestHold)
            }
            _state.update { it.copy(backtestRunning = false, backtest = res) }
        }
    }

    /** CI 스크린샷 / 첫 실행 체험용: 저장하지 않고 데모 모드로 전환 */
    fun forceDemoSession() {
        if (_state.value.settings.source == DataSource.DEMO) return
        _state.update { it.copy(settings = it.settings.copy(source = DataSource.DEMO)) }
        reload(force = false)
    }
}
