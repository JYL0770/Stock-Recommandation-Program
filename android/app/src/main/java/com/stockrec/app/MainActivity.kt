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
import com.stockrec.app.ui.RecommendScreen
import com.stockrec.app.ui.SettingsScreen

enum class Tab(val label: String, val icon: ImageVector) {
    RECOMMEND("추천", Icons.Filled.Star),
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

    LaunchedEffect(state.nStocks, autoBacktest) {
        if (autoBacktest && state.nStocks > 0 && state.backtest == null && !state.backtestRunning) vm.runBacktest()
    }

    val detail = openTicker?.let { vm.features[it] }
    BackHandler(enabled = detail != null) { openTicker = null }
    BackHandler(enabled = detail == null && tab != Tab.RECOMMEND) { tab = Tab.RECOMMEND }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(detail?.info?.name ?: if (tab == Tab.RECOMMEND) "주식 추천" else tab.label,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (detail == null && tab == Tab.RECOMMEND) {
                            Text("가격 지표 기반 · ${state.settings.strategy.name}", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                },
                navigationIcon = {
                    if (detail != null) IconButton(onClick = { openTicker = null }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "뒤로")
                    }
                },
                actions = {
                    if (detail == null && tab == Tab.RECOMMEND) {
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
            if (detail == null) {
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
            DetailScreen(detail, scored, inPool, state.settings.weights, padding)
            return@Scaffold
        }
        when (tab) {
            Tab.RECOMMEND -> RecommendScreen(
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
