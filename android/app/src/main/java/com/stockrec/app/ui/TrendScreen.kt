package com.stockrec.app.ui

import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.stockrec.app.UiState
import com.stockrec.core.Channel
import com.stockrec.core.ChannelStatus
import java.time.format.DateTimeFormatter
import kotlin.math.abs
import kotlin.math.roundToInt

val ChannelRed = Color(0xFFE53935)

private enum class TrendFilter(val label: String, val statuses: Set<ChannelStatus>) {
    IN("매수권", setOf(ChannelStatus.IN)),
    BELOW("지지선 이탈", setOf(ChannelStatus.BELOW)),
    ABOVE("채널 위", setOf(ChannelStatus.ABOVE)),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrendScreen(
    state: UiState,
    onWidth: (Double) -> Unit,
    onOpen: (String) -> Unit,
    contentPadding: PaddingValues,
) {
    var filter by rememberSaveable { mutableStateOf(TrendFilter.IN) }
    val list = state.channels.filter { it.status in filter.statuses }

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
            Text(
                "주봉에서 상승 추세의 저점들을 가장 많이 잇는 지지선(하단선)과 그 위 평행선(상단선)을 자동으로 긋고, " +
                    "현재가가 두 선 사이로 내려온 종목을 찾습니다. 접점이 많을수록 신뢰도가 높은 추세선입니다.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (f in TrendFilter.entries) {
                    val n = state.channels.count { it.status in f.statuses }
                    FilterChip(selected = filter == f, onClick = { filter = f }, label = { Text("${f.label} $n") })
                }
            }
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Text("채널 폭", style = MaterialTheme.typography.labelMedium)
                for (w in listOf(0.05, 0.08, 0.10, 0.15, 0.20)) {
                    FilterChip(
                        selected = abs(state.settings.trendWidth - w) < 1e-6,
                        onClick = { onWidth(w) },
                        label = { Text("${(w * 100).roundToInt()}%") },
                    )
                }
            }
        }

        if (state.loading) {
            item { Text("데이터를 불러오는 중입니다... (추천 탭에서 진행 상황 확인)", style = MaterialTheme.typography.bodySmall) }
        } else if (list.isEmpty()) {
            item {
                Text("조건에 맞는 종목이 없습니다. 채널 폭을 넓히거나 설정에서 종목 범위를 늘려 보세요.",
                    Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        items(list, key = { it.info.ticker }) { c -> ChannelCard(c) { onOpen(c.info.ticker) } }

        if (list.isNotEmpty()) {
            item {
                Text("※ 기계적으로 그은 추세선이며 투자 권유가 아닙니다. 지지선을 종가로 확실히 이탈하면 추세 훼손으로 보는 것이 일반적입니다.",
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun ChannelCard(c: Channel, onClick: () -> Unit) {
    val neutral = MaterialTheme.colorScheme.onSurface
    val fmt = DateTimeFormatter.ofPattern("yy.MM")
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(c.info.name, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("${c.info.ticker} · ${c.info.market}", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(
                    c.status.label,
                    color = if (c.status == ChannelStatus.IN) ChannelRed else MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = FontWeight.Bold,
                )
            }
            Spacer(Modifier.height(8.dp))
            ChannelPositionBar(c)
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                LabeledValue("현재가", fmtPrice(c.close))
                LabeledValue("지지선", fmtPrice(c.support))
                LabeledValue("상단선", fmtPrice(c.upper))
                LabeledValue("최근 고점比", fmtPct(c.fromRecentHigh), signColor(c.fromRecentHigh, neutral))
            }
            Text(
                "추세 시작 ${c.startDate.format(fmt)} · 저점 접점 ${c.touches}회 · 지지선 상승 속도 연 ${fmtPct(c.trendPerYear, 0)} · 지지선 대비 ${fmtPct(c.close / c.support - 1)}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}

/** 하단선(0) ~ 상단선(1) 사이 현재 위치 */
@Composable
fun ChannelPositionBar(c: Channel) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("지지", style = MaterialTheme.typography.labelSmall)
        Spacer(Modifier.width(6.dp))
        androidx.compose.foundation.Canvas(Modifier.weight(1f).height(14.dp)) {
            val y = size.height / 2
            // 밴드 구간은 가운데 60% 폭으로, 양쪽은 이탈/돌파 영역
            val x0 = size.width * 0.2f
            val x1 = size.width * 0.8f
            drawLine(Color.LightGray, androidx.compose.ui.geometry.Offset(0f, y), androidx.compose.ui.geometry.Offset(size.width, y), 4f)
            drawLine(ChannelRed.copy(alpha = 0.35f), androidx.compose.ui.geometry.Offset(x0, y), androidx.compose.ui.geometry.Offset(x1, y), 14f)
            drawLine(ChannelRed, androidx.compose.ui.geometry.Offset(x0, 0f), androidx.compose.ui.geometry.Offset(x0, size.height), 3f)
            drawLine(ChannelRed, androidx.compose.ui.geometry.Offset(x1, 0f), androidx.compose.ui.geometry.Offset(x1, size.height), 3f)
            val px = (x0 + (x1 - x0) * c.position.toFloat()).coerceIn(4f, size.width - 4f)
            drawCircle(Color.Black, radius = 6f * density / 2, center = androidx.compose.ui.geometry.Offset(px, y))
        }
        Spacer(Modifier.width(6.dp))
        Text("상단", style = MaterialTheme.typography.labelSmall)
    }
}

/** 주봉 종가 + 상단/하단 추세선 차트 */
@Composable
fun ChannelChart(c: Channel, modifier: Modifier = Modifier) {
    val n = c.weeks.size
    val fmt = DateTimeFormatter.ofPattern("yy.MM")
    val closes = DoubleArray(n) { c.weeks[it].close }
    val lows = DoubleArray(n) { c.weeks[it].low }
    val upper = DoubleArray(n) { c.upperAt(it) }
    val lower = DoubleArray(n) { c.supportAt(it) }
    Column(modifier) {
        Legend(listOf("주봉 종가" to MaterialTheme.colorScheme.onSurface, "주봉 저가" to Color.Gray, "추세선" to ChannelRed))
        LineChart(
            listOf(
                Line(lows, Color.Gray.copy(alpha = 0.6f), 1f),
                Line(closes, MaterialTheme.colorScheme.onSurface, 1.6f),
                Line(upper, ChannelRed, 2f),
                Line(lower, ChannelRed, 2f),
            ),
            Modifier.fillMaxWidth().height(240.dp),
            xLabels = listOf(0, n / 2, n - 1).distinct().map { it to c.weeks[it].date.format(fmt) },
        )
        Text(
            "${c.status.label} · 지지선 ${fmtPrice(c.support)} / 상단선 ${fmtPrice(c.upper)} · 현재가 ${fmtPrice(c.close)} · 저점 접점 ${c.touches}회",
            style = MaterialTheme.typography.labelSmall,
        )
    }
}
