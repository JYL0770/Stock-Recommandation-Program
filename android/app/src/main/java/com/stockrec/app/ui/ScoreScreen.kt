package com.stockrec.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.stockrec.app.DataSource
import com.stockrec.app.UiState
import com.stockrec.core.F
import com.stockrec.core.Recommendation
import com.stockrec.core.STRATEGIES
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScoreScreen(
    state: UiState,
    onSelectStrategy: (Int) -> Unit,
    onOpen: (String) -> Unit,
    onRetry: () -> Unit,
    onUseDemo: () -> Unit,
    contentPadding: PaddingValues,
) {
    val s = state.settings
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = 16.dp, end = 16.dp,
            top = contentPadding.calculateTopPadding() + 8.dp,
            bottom = contentPadding.calculateBottomPadding() + 16.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                itemsIndexed(STRATEGIES) { i, st ->
                    FilterChip(
                        selected = i == s.strategyIndex,
                        onClick = { onSelectStrategy(i) },
                        label = { Text(st.name) },
                    )
                }
            }
            Text(
                s.strategy.description + if (s.isCustomWeights) " · 가중치 직접 조정됨" else "",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        item { StatusCard(state, onRetry, onUseDemo) }

        if (!state.loading && state.error == null && state.recommendations.isEmpty() && state.nStocks > 0) {
            item {
                Text("조건을 만족하는 종목이 없습니다. 설정에서 필터를 완화해 보세요.",
                    Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        items(state.recommendations, key = { it.snap.ticker }) { r -> RecommendationCard(r) { onOpen(r.snap.ticker) } }

        if (state.recommendations.isNotEmpty()) {
            item {
                Text(
                    "※ 가격 지표만으로 계산한 참고용 순위이며 투자 권유가 아닙니다.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun StatusCard(state: UiState, onRetry: () -> Unit, onUseDemo: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
        Column(Modifier.padding(14.dp).fillMaxWidth()) {
            when {
                state.loading -> {
                    Text(state.progressText, style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(8.dp))
                    LinearProgressIndicator(progress = { state.progress }, modifier = Modifier.fillMaxWidth())
                }
                state.error != null -> {
                    Text("데이터를 불러오지 못했습니다", fontWeight = FontWeight.Bold)
                    Text(state.error, style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                        OutlinedButton(onClick = onRetry) { Text("다시 시도") }
                        OutlinedButton(onClick = onUseDemo) { Text("데모로 체험") }
                    }
                }
                else -> {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        LabeledValue("기준일", state.asOf?.toString() ?: "-")
                        LabeledValue("분석 종목", "${state.nStocks}")
                        LabeledValue("필터 통과", "${state.nCandidates}")
                    }
                    val src = if (state.settings.source == DataSource.DEMO) "데모(가상) 데이터" else "네이버 금융 시세"
                    val time = state.fetchedAt?.let { SimpleDateFormat("MM/dd HH:mm", Locale.KOREA).format(Date(it)) } ?: ""
                    Text(
                        "$src · ${state.universeNote} · $time 수신${if (state.fromCache) "(캐시)" else ""}" +
                            if (state.failed.isNotEmpty()) " · 실패 ${state.failed.size}" else "",
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
            }
        }
    }
}

@Composable
fun RecommendationCard(r: Recommendation, onClick: () -> Unit) {
    val snap = r.snap
    val neutral = MaterialTheme.colorScheme.onSurface
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(32.dp).clip(CircleShape).background(
                        if (r.rank <= 3) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary
                    ),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("${r.rank}", color = MaterialTheme.colorScheme.onPrimary, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(snap.info.name, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("${snap.ticker} · ${snap.info.market}", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(fmtNum(r.score), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text("점", style = MaterialTheme.typography.labelSmall)
                }
            }
            ScoreBar(r.score, Modifier.fillMaxWidth().padding(vertical = 8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                LabeledValue("종가", fmtPrice(snap.close))
                LabeledValue("1개월", fmtPct(snap[F.RET_20]), signColor(snap[F.RET_20], neutral))
                LabeledValue("3개월", fmtPct(snap[F.RET_60]), signColor(snap[F.RET_60], neutral))
                LabeledValue("RSI", fmtNum(snap[F.RSI14], 0))
            }
            Spacer(Modifier.height(8.dp))
            for (reason in r.reasons.take(2)) {
                Text("✓ $reason", style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (r.warnings.isNotEmpty()) {
                Text("⚠ ${r.warnings.first()}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}
