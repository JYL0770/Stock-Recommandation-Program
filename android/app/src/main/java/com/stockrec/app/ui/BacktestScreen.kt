package com.stockrec.app.ui

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.stockrec.app.UiState
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BacktestScreen(
    state: UiState,
    nameOf: (String) -> String,
    onParams: (topN: Int?, hold: Int?) -> Unit,
    onRun: () -> Unit,
    contentPadding: PaddingValues,
) {
    val neutral = MaterialTheme.colorScheme.onSurface
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(contentPadding)
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Text(
            "전략: ${state.settings.strategy.name}${if (state.settings.isCustomWeights) " (가중치 조정)" else ""}\n" +
                "매 리밸런싱일마다 그날까지의 데이터만으로 점수를 계산해 상위 종목을 동일비중 보유합니다. " +
                "벤치마크는 필터를 통과한 전체 후보의 동일비중, 거래비용 0.3% 반영. 밸류 팩터는 제외됩니다.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))
        Text("보유 종목 수: ${state.backtestTopN}", style = MaterialTheme.typography.labelLarge)
        Slider(
            value = state.backtestTopN.toFloat(),
            onValueChange = { onParams(it.roundToInt(), null) },
            valueRange = 3f..30f, steps = 26,
        )
        Text("리밸런싱 주기 (거래일)", style = MaterialTheme.typography.labelLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (h in listOf(5, 10, 20, 40, 60)) {
                FilterChip(selected = state.backtestHold == h, onClick = { onParams(null, h) }, label = { Text("$h") })
            }
        }
        Spacer(Modifier.height(8.dp))
        Button(onClick = onRun, enabled = !state.backtestRunning && !state.loading && state.nStocks > 0,
            modifier = Modifier.fillMaxWidth()) {
            if (state.backtestRunning) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                Spacer(Modifier.width(8.dp))
                Text("계산 중...")
            } else Text("백테스트 실행")
        }

        val res = state.backtest ?: return@Column
        Spacer(Modifier.height(12.dp))
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    LabeledValue("전략 연수익률", fmtPct(res.cagr), signColor(res.cagr, neutral))
                    LabeledValue("벤치마크", fmtPct(res.benchCagr), signColor(res.benchCagr, neutral))
                    LabeledValue("초과", fmtPct(res.cagr - res.benchCagr), signColor(res.cagr - res.benchCagr, neutral))
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    LabeledValue("전략 MDD", fmtPct(res.mdd))
                    LabeledValue("벤치 MDD", fmtPct(res.benchMdd))
                    LabeledValue("초과 승률", "${(res.winRate * 100).roundToInt()}%")
                    LabeledValue("샤프", fmtNum(res.sharpe, 2))
                }
                Text("기간 ${fmtNum(res.years, 1)}년 · ${res.periods.size}회 리밸런싱 · 평균 회전율 ${(res.avgTurnover * 100).roundToInt()}%",
                    style = MaterialTheme.typography.labelSmall)
            }
        }

        Spacer(Modifier.height(12.dp))
        val strat = MaterialTheme.colorScheme.tertiary
        val bench = Color.Gray
        Legend(listOf("전략" to strat, "벤치마크" to bench))
        val fmt = DateTimeFormatter.ofPattern("yy.MM")
        val eq = res.equity
        LineChart(
            listOf(
                Line(eq.map { it.third }.toDoubleArray(), bench, 1.5f, dashed = true),
                Line(eq.map { it.second }.toDoubleArray(), strat, 2.2f),
            ),
            Modifier.fillMaxWidth().height(220.dp),
            xLabels = listOf(0, eq.size / 2, eq.size - 1).distinct().map { it to eq[it].first.format(fmt) },
            yFormat = { fmtNum(it, 2) },
        )

        SectionTitle("구간별 결과")
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp)) {
                res.periods.asReversed().forEachIndexed { i, p ->
                    Column(Modifier.padding(vertical = 6.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically) {
                            Text("${p.start} ~ ${p.end}", style = MaterialTheme.typography.bodySmall)
                            Text(fmtPct(p.strategy), color = signColor(p.strategy, neutral), fontWeight = FontWeight.Bold,
                                style = MaterialTheme.typography.bodySmall)
                            Text("벤치 ${fmtPct(p.benchmark)}", style = MaterialTheme.typography.bodySmall)
                        }
                        Text(p.picks.joinToString(", ") { nameOf(it) }, style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (i < res.periods.size - 1) HorizontalDivider()
                }
            }
        }
        Text("⚠ 상장폐지 종목이 빠진 생존편향이 있어 실제보다 좋게 나올 수 있습니다. 과거 성과가 미래 수익을 보장하지 않습니다.",
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(vertical = 12.dp))
    }
}
