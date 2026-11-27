package com.stockrec.app.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.stockrec.core.F
import com.stockrec.core.Factor
import com.stockrec.core.FeatureSeries
import com.stockrec.core.Scored
import com.stockrec.core.Scoring
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetailScreen(
    fs: FeatureSeries,
    scored: Scored?,
    inPool: Boolean,
    weights: Map<Factor, Double>,
    contentPadding: PaddingValues,
) {
    var period by rememberSaveable { mutableIntStateOf(250) }
    val n = fs.size
    val from = maxOf(0, n - period)
    val last = fs.latest()
    val prevClose = if (n >= 2) fs.bars[n - 2].close else Double.NaN
    val change = last.close / prevClose - 1
    val neutral = MaterialTheme.colorScheme.onSurface

    fun slice(key: String) = fs[key].copyOfRange(from, n)
    val closes = DoubleArray(n - from) { fs.bars[from + it].close }
    val fmt = DateTimeFormatter.ofPattern("yy.MM")
    val xLabels = remember(from, n) {
        val m = n - from
        listOf(0, m / 2, m - 1).distinct().map { it to fs.bars[from + it].date.format(fmt) }
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(contentPadding)
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Text("${fs.info.ticker} · ${fs.info.market}", style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(verticalAlignment = Alignment.Bottom) {
            Text(fmtPrice(last.close), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.width(8.dp))
            Text("${fmtPct(change, 2)} (전일 대비)", color = signColor(change, neutral), modifier = Modifier.padding(bottom = 4.dp))
        }
        Text("${last.date} 기준", style = MaterialTheme.typography.labelSmall)

        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(top = 8.dp).horizontalScroll(rememberScrollState()),
        ) {
            for ((p, label) in listOf(60 to "3개월", 120 to "6개월", 250 to "1년", 500 to "2년")) {
                FilterChip(selected = period == p, onClick = { period = p }, label = { Text(label, maxLines = 1) })
            }
        }

        val ma20c = Color(0xFF2CA02C)
        val ma60c = Color(0xFF9467BD)
        val ma120c = Color(0xFF8C564B)
        Legend(listOf("종가" to MaterialTheme.colorScheme.onSurface, "MA20" to ma20c, "MA60" to ma60c, "MA120" to ma120c))
        LineChart(
            listOf(
                Line(slice(F.MA120), ma120c, 1.2f), Line(slice(F.MA60), ma60c, 1.2f),
                Line(slice(F.MA20), ma20c, 1.2f), Line(closes, MaterialTheme.colorScheme.onSurface, 1.8f),
            ),
            Modifier.fillMaxWidth().height(220.dp),
            xLabels = xLabels,
        )
        val vols = DoubleArray(n - from) { fs.bars[from + it].volume }
        val volColors = List(n - from) { i ->
            val b = fs.bars[from + i]
            if (b.close >= b.open) UpColor.copy(alpha = 0.6f) else DownColor.copy(alpha = 0.6f)
        }
        BarChart(vols, volColors, Modifier.fillMaxWidth().height(50.dp).padding(top = 4.dp))
        Text("RSI(14)", style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 8.dp))
        LineChart(
            listOf(Line(slice(F.RSI14), Color(0xFFE377C2), 1.5f)),
            Modifier.fillMaxWidth().height(90.dp),
            yMin = 0.0, yMax = 100.0, hLines = listOf(30.0, 70.0), yFormat = { fmtNum(it, 0) },
        )

        if (scored != null) {
            SectionTitle("팩터 점수 ${if (!scored.score.isNaN()) "· 종합 ${fmtNum(scored.score)}점" else ""}")
            if (!inPool) {
                Text("※ 현재 필터를 통과하지 못해 전체 종목 기준으로 계산했습니다.",
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
            }
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (f in Factor.entries) {
                        val v = scored.factors[f] ?: Double.NaN
                        if (v.isNaN()) continue
                        val w = weights[f] ?: 0.0
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(f.label + if (w > 0) " (${w.toInt()})" else "", Modifier.width(120.dp),
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = if (w > 0) FontWeight.Bold else FontWeight.Normal)
                            ScoreBar(v, Modifier.weight(1f),
                                if (w > 0) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.outline)
                            Text(fmtNum(v, 0), Modifier.width(36.dp).padding(start = 8.dp), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }

            val reasons = Scoring.reasons(scored)
            val warnings = Scoring.warnings(scored.snap)
            SectionTitle("판단 근거")
            for (r in reasons) Text("✓ $r", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = 2.dp))
            for (w in warnings) Text("⚠ $w", style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(vertical = 2.dp))
        }

        SectionTitle("지표")
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp)) {
                val keys = F.DESCRIPTIONS.keys.toList()
                keys.forEachIndexed { i, k ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(F.DESCRIPTIONS.getValue(k), style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                        Text(fmtFeature(k, last[k]), style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
                    }
                    if (i < keys.size - 1) HorizontalDivider()
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}
