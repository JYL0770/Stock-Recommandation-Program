package com.stockrec.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.ThumbUp
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.stockrec.app.ui.AppTheme
import com.stockrec.app.ui.BacktestScreen
import com.stockrec.app.ui.DetailScreen
import com.stockrec.app.ui.HelpScreen
import com.stockrec.app.ui.ScoreScreen
import com.stockrec.app.ui.SettingsScreen
import com.stockrec.app.ui.BuyZoneScreen
import com.stockrec.app.ui.IndexDetailScreen

enum class Tab(val label: String, val icon: ImageVector) {
    RECOMMEND("추천", Icons.Filled.Star),
    SCORE("점수순위", Icons.Filled.ThumbUp),
    BACKTEST("백테스트", Icons.Filled.DateRange),
    SETTINGS("설정", Icons.Filled.Settings),
    HELP("도움말", Icons.Filled.Info),
}

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // 자동 스크린샷(CI)용 실행 옵션: --ez demo true --es tab backtest --es open 900000 --ez run_backtest true
        val ex = intent?.extras
        if (savedInstanceState == null && ex?.getBoolean("demo") == true) vm.forceDemoSession()
        val startTab = ex?.getString("tab")?.let { t -> Tab.entries.firstOrNull { it.name.equals(t, true) } } ?: Tab.RECOMMEND
        val startOpen = ex?.getString("open")
        val autoBacktest = ex?.getBoolean("run_backtest") == true

        setContent {
            AppTheme { App(vm, startTab, startOpen, autoBacktest) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun App(vm: MainViewModel, startTab: Tab, startOpen: String?, autoBacktest: Boolean) {
    val state by vm.state.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableStateOf(startTab) }
    var openTicker by rememberSaveable { mutableStateOf(startOpen) }

    // CI 스크린샷용: --es open TREND_FIRST → 매수권(없으면 종목 조건 충족) 첫 종목 상세
    LaunchedEffect(state.buyZone) {
        if (startOpen == "TREND_FIRST" && openTicker == "TREND_FIRST") {
            (state.buyZone.firstOrNull { it.buy } ?: state.buyZone.firstOrNull { it.stockOk })?.let { openTicker = it.info.ticker }
        }
    }

    LaunchedEffect(state.nStocks, autoBacktest) {
        if (autoBacktest && state.nStocks > 0 && state.backtest == null && !state.backtestRunning) vm.runBacktest()
    }

    val detail = openTicker?.let { vm.features[it] }
    val indexDetail = openTicker?.takeIf { it.startsWith("IDX:") }?.let { state.indexes[it.removePrefix("IDX:")] }
    val inDetail = detail != null || indexDetail != null
    BackHandler(enabled = inDetail) { openTicker = null }
    BackHandler(enabled = !inDetail && tab != Tab.RECOMMEND) { tab = Tab.RECOMMEND }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            detail?.info?.name ?: indexDetail?.let { "${it.series.info.name} 지수" }
                                ?: when (tab) { Tab.RECOMMEND -> "주식 추천"; Tab.SCORE -> "지표 점수 순위"; else -> tab.label },
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                        if (!inDetail && tab == Tab.RECOMMEND) {
                            Text("추세선 채널 · 종목·지수 일봉/주봉 4개 차트", style = MaterialTheme.typography.labelSmall)
                        }
                        if (!inDetail && tab == Tab.SCORE) {
                            Text("가격 지표 점수 · ${state.settings.strategy.name}", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                },
                navigationIcon = {
                    if (inDetail) IconButton(onClick = { openTicker = null }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "뒤로")
                    }
                },
                actions = {
                    if (!inDetail && (tab == Tab.RECOMMEND || tab == Tab.SCORE)) {
                        IconButton(onClick = { vm.reload(force = true) }, enabled = !state.loading) {
                            Icon(Icons.Filled.Refresh, contentDescription = "새로고침")
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary,
                    navigationIconContentColor = MaterialTheme.colorScheme.onPrimary,
                    actionIconContentColor = MaterialTheme.colorScheme.onPrimary,
                ),
            )
        },
        bottomBar = {
            if (!inDetail) {
                NavigationBar {
                    for (t in Tab.entries) {
                        NavigationBarItem(
                            selected = tab == t,
                            onClick = { tab = t },
                            icon = { Icon(t.icon, contentDescription = t.label) },
                            label = { Text(t.label) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        if (detail != null) {
            val (scored, inPool) = remember(detail, state.settings, state.nStocks) { vm.scoredFor(detail.info.ticker) }
            DetailScreen(detail, scored, inPool, state.settings.weights, vm.buyZoneFor(detail.info.ticker), padding)
            return@Scaffold
        }
        if (indexDetail != null) {
            IndexDetailScreen(indexDetail, padding)
            return@Scaffold
        }
        when (tab) {
            Tab.RECOMMEND -> BuyZoneScreen(
                state,
                onWidth = { w -> vm.updateSettings { it.copy(trendWidth = w) } },
                onOpen = { openTicker = it },
                onRetry = { vm.reload(force = true) },
                onUseDemo = { vm.updateSettings { it.copy(source = DataSource.DEMO) } },
                contentPadding = padding,
            )
            Tab.SCORE -> ScoreScreen(
                state,
                onSelectStrategy = vm::selectStrategy,
                onOpen = { openTicker = it },
                onRetry = { vm.reload(force = true) },
                onUseDemo = { vm.updateSettings { it.copy(source = DataSource.DEMO) } },
                contentPadding = padding,
            )
            Tab.BACKTEST -> BacktestScreen(
                state,
                nameOf = { t -> vm.features[t]?.info?.name ?: t },
                onParams = { n, h -> vm.setBacktestParams(n, h) },
                onRun = vm::runBacktest,
                contentPadding = padding,
            )
            Tab.SETTINGS -> SettingsScreen(
                state.settings,
                needsReload = vm.needsReload() && !state.loading,
                loading = state.loading,
                onChange = vm::updateSettings,
                onApplyUniverse = vm::applyUniverse,
                onRefresh = { vm.reload(force = true) },
                contentPadding = padding,
            )
            Tab.HELP -> HelpScreen(padding)
        }
    }
}
