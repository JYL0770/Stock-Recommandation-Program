package com.stockrec.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.stockrec.app.DataSource
import com.stockrec.app.UiState
import com.stockrec.app.Region
import com.stockrec.app.StockRepository
import com.stockrec.core.BuyZone
import com.stockrec.core.BuyZoneResult
import com.stockrec.core.Channel
import com.stockrec.core.Check
import com.stockrec.core.IndexState
import java.text.SimpleDateFormat
import java.time.format.DateTimeFormatter
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

val ChannelRed = Color(0xFFE53935)
private val OkGreen = Color(0xFF2E7D32)

private enum class BzFilter(val label: String) { BUY("매수권 (4/4)"), STOCK("종목 조건 충족") }

private val INDEX_NAMES = (BuyZone.KR_INDEXES + BuyZone.US_INDEXES).toMap()

/** 추천 탭: 스크린샷 방식 추세선 채널 — 종목 일봉·주봉 + 지수 일봉·주봉 모두 매수권 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BuyZoneScreen(
    state: UiState,
    onWidth: (Double) -> Unit,
    onRegion: (Region) -> Unit,
    onOpen: (String) -> Unit,
    onRetry: () -> Unit,
    onUseDemo: () -> Unit,
    contentPadding: PaddingValues,
) {
    var filter by rememberSaveable { mutableStateOf(BzFilter.BUY) }
    val buy = state.buyZone.filter { it.buy }
    val stockOnly = state.buyZone.filter { it.stockOk && !it.buy }
    val list = if (filter == BzFilter.BUY) buy else stockOnly
    val region = state.settings.region
    val indexCodes = StockRepository.indexesOf(region).map { it.first }

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
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (r in Region.entries) {
                    FilterChip(selected = region == r, onClick = { onRegion(r) },
                        label = { Text(r.label, fontWeight = if (region == r) FontWeight.Bold else FontWeight.Normal) })
                }
            }
            Text(
                "상승 추세의 저점을 가장 많이 잇는 지지선과 그 위 평행선 사이에 마지막 종가가 있으면 그 차트는 매수권입니다. " +
                    "종목 일봉·주봉과 지수 일봉·주봉, 4개 차트가 모두 매수권이어야 추천합니다.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        item { LoadStatus(state, onRetry, onUseDemo) }

        if (state.indexes.isNotEmpty() || (!state.loading && state.nStocks > 0)) {
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (code in indexCodes) {
                        IndexCard(code, state.indexes[code], Modifier.weight(1f)) { onOpen("IDX:$code") }
                    }
                }
            }
        }

        item {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(selected = filter == BzFilter.BUY, onClick = { filter = BzFilter.BUY },
                    label = { Text("${BzFilter.BUY.label} ${buy.size}") })
                FilterChip(selected = filter == BzFilter.STOCK, onClick = { filter = BzFilter.STOCK },
                    label = { Text("${BzFilter.STOCK.label} ${stockOnly.size}") })
            }
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Text("채널 폭", style = MaterialTheme.typography.labelMedium)
                for (w in listOf(0.05, 0.08, 0.10, 0.15, 0.20)) {
                    FilterChip(selected = abs(state.settings.trendWidth - w) < 1e-6, onClick = { onWidth(w) },
                        label = { Text("${(w * 100).roundToInt()}%") })
                }
            }
        }

        if (!state.loading && state.nStocks > 0 && !state.buyZoneComputing && list.isEmpty()) {
            item { EmptyExplanation(filter == BzFilter.BUY, state, stockOnly.size) }
        }
        if (filter == BzFilter.STOCK && stockOnly.isNotEmpty()) {
            item {
                Text("※ 종목 일봉·주봉은 매수권이지만 지수 조건을 만족하지 못해 추천에서 제외된 종목입니다 (참고용).",
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
            }
        }

        items(list, key = { it.info.ticker }) { r -> ResultCard(r) { onOpen(r.info.ticker) } }

        item {
            Text("※ 기계적으로 그은 추세선이며 투자 권유가 아닙니다. 지지선을 종가로 확실히 이탈하면 추세 훼손으로 보는 것이 일반적입니다.",
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun LoadStatus(state: UiState, onRetry: () -> Unit, onUseDemo: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
        Column(Modifier.padding(12.dp).fillMaxWidth()) {
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
                        LabeledValue("분석 종목", state.scan?.let { "%,d".format(it.analyzed) } ?: "${state.nStocks}")
                        LabeledValue("매수권", if (state.buyZoneComputing) "계산 중" else "${state.buyZone.count { it.buy }}")
                    }
                    val src = if (state.settings.source == DataSource.DEMO) "데모(가상) 데이터" else "${state.settings.region.source} 시세"
                    val time = state.fetchedAt?.let { SimpleDateFormat("MM/dd HH:mm", Locale.KOREA).format(Date(it)) } ?: ""
                    Text("$src · ${state.universeNote} · $time 수신${if (state.fromCache) "(캐시)" else ""}" +
                        if (state.failed.isNotEmpty()) " · 실패 ${state.failed.size}" else "",
                        style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 6.dp))
                    state.scan?.let { sc ->
                        val c = sc.counts
                        val at = runCatching {
                            java.time.Instant.parse(sc.generatedAt).atZone(java.time.ZoneId.systemDefault())
                                .format(java.time.format.DateTimeFormatter.ofPattern("MM/dd HH:mm"))
                        }.getOrDefault(sc.generatedAt)
                        Text("서버 전체 스캔 $at (${sc.asOf} 종가) · 종목 일봉 ${c["stock_daily"] ?: 0} · 주봉 ${c["stock_weekly"] ?: 0} · " +
                            "둘 다 ${c["stock_both"] ?: 0} → 최신 시세로 재확인",
                            style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 2.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyExplanation(buyTab: Boolean, state: UiState, nStockOnly: Int) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (!buyTab) {
                Text("종목 일봉·주봉을 모두 만족하는 종목이 없습니다.", fontWeight = FontWeight.Bold)
                return@Column
            }
            Text("오늘은 4개 조건을 모두 만족하는 종목이 없습니다.", fontWeight = FontWeight.Bold)
            val bad = StockRepository.indexesOf(state.settings.region).map { it.first }.filter { state.indexes[it]?.ok != true }
            if (bad.isNotEmpty()) {
                Text("지수 조건 미충족: " + bad.joinToString(", ") { code ->
                    val st = state.indexes[code]
                    "${INDEX_NAMES[code]} (일봉 ${statusText(st?.daily)}, 주봉 ${statusText(st?.weekly)})"
                }, style = MaterialTheme.typography.bodySmall)
                Text(if (state.settings.region == Region.KR) "해당 시장의 종목은 지수가 다시 상승 추세 채널 안으로 들어올 때까지 추천되지 않습니다."
                    else "나스닥 상장 종목은 나스닥 종합, NYSE·OTC(핑크시트) 종목은 S&P 500 지수를 기준으로 판정합니다.",
                    style = MaterialTheme.typography.bodySmall)
            }
            if (nStockOnly > 0) {
                Text("종목 차트 조건만 충족한 종목 ${nStockOnly}개는 위 '종목 조건 충족'에서 볼 수 있습니다.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
            }
        }
    }
}

fun statusText(c: Channel?) = c?.status?.label ?: "추세선 없음"

@Composable
private fun IndexCard(code: String, st: IndexState?, modifier: Modifier, onClick: () -> Unit) {
    Card(modifier.clickable(onClick = onClick)) {
        Column(Modifier.padding(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(INDEX_NAMES[code] ?: code, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Text(
                    when { st == null -> "데이터 없음"; st.ok -> "충족"; else -> "미충족" },
                    color = if (st?.ok == true) OkGreen else MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold,
                )
            }
            if (st != null) {
                Text(fmtIndex(st.series.bars.last().close), style = MaterialTheme.typography.bodyMedium)
                CheckLine("일봉", st.daily)
                CheckLine("주봉", st.weekly)
            }
        }
    }
}

@Composable
private fun CheckLine(label: String, c: Channel?) {
    val ok = c?.ok == true
    Text("${if (ok) "✓" else "✗"} $label ${statusText(c)}", style = MaterialTheme.typography.labelSmall,
        color = if (ok) OkGreen else MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
}

/** 4개 차트 충족 여부 배지 (2×2) */
@Composable
fun CheckBadges(r: BuyZoneResult) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        for (row in Check.entries.chunked(2)) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (c in row) {
                    val ok = r.ok(c)
                    Box(
                        Modifier.weight(1f).clip(RoundedCornerShape(6.dp))
                            .background(if (ok) OkGreen.copy(alpha = 0.12f) else MaterialTheme.colorScheme.surfaceVariant)
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                    ) {
                        Text("${if (ok) "✓" else "✗"} ${c.label} · ${statusText(r.channels[c])}",
                            style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            color = if (ok) OkGreen else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
private fun ResultCard(r: BuyZoneResult, onClick: () -> Unit) {
    val d = r.channels[Check.STOCK_DAILY]
    val w = r.channels[Check.STOCK_WEEKLY]
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(r.info.name, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("${r.info.ticker} · ${r.info.market}", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(fmtPrice(r.close), fontWeight = FontWeight.Bold)
                    Text(if (r.buy) "매수권" else "${r.nOk}/4 충족", fontWeight = FontWeight.Bold,
                        color = if (r.buy) ChannelRed else MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.labelMedium)
                }
            }
            Spacer(Modifier.height(8.dp))
            CheckBadges(r)
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                LabeledValue("일봉 지지~상단", d?.let { "${fmtPrice(it.support)}~${fmtPrice(it.upper)}" } ?: "-")
                LabeledValue("주봉 지지~상단", w?.let { "${fmtPrice(it.support)}~${fmtPrice(it.upper)}" } ?: "-")
            }
            if (w != null) {
                Text("주봉 저점 접점 ${w.touches}회 · 26주 고점 대비 ${fmtPct(w.fromRecentHigh)}",
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp))
            }
        }
    }
}

/** 하단선(0) ~ 상단선(1) 사이 현재 위치 */
@Composable
fun ChannelPositionBar(c: Channel) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("지지", style = MaterialTheme.typography.labelSmall)
        Spacer(Modifier.width(6.dp))
        Canvas(Modifier.weight(1f).height(14.dp)) {
            val y = size.height / 2
            val x0 = size.width * 0.2f
            val x1 = size.width * 0.8f
            drawLine(Color.LightGray, Offset(0f, y), Offset(size.width, y), 4f)
            drawLine(ChannelRed.copy(alpha = 0.35f), Offset(x0, y), Offset(x1, y), 14f)
            drawLine(ChannelRed, Offset(x0, 0f), Offset(x0, size.height), 3f)
            drawLine(ChannelRed, Offset(x1, 0f), Offset(x1, size.height), 3f)
            val px = (x0 + (x1 - x0) * c.position.toFloat()).coerceIn(4f, size.width - 4f)
            drawCircle(Color.Black, radius = 3f * density, center = Offset(px, y))
        }
        Spacer(Modifier.width(6.dp))
        Text("상단", style = MaterialTheme.typography.labelSmall)
    }
}

/** 봉 종가·저가 + 지지선/상단선 차트 */
@Composable
fun ChannelChart(c: Channel, title: String, modifier: Modifier = Modifier) {
    val n = c.bars.size
    val fmt = DateTimeFormatter.ofPattern("yy.MM")
    Column(modifier) {
        Text("$title · ${c.status.label}", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium,
            color = if (c.ok) ChannelRed else MaterialTheme.colorScheme.onSurface)
        ChannelPositionBar(c)
        Legend(listOf("종가" to MaterialTheme.colorScheme.onSurface, "저가" to Color.Gray, "추세선" to ChannelRed))
        LineChart(
            listOf(
                Line(DoubleArray(n) { c.bars[it].low }, Color.Gray.copy(alpha = 0.6f), 1f),
                Line(DoubleArray(n) { c.bars[it].close }, MaterialTheme.colorScheme.onSurface, 1.6f),
                Line(DoubleArray(n) { c.supportAt(it) }, ChannelRed, 2f),
                Line(DoubleArray(n) { c.upperAt(it) }, ChannelRed, 2f),
            ),
            Modifier.fillMaxWidth().height(220.dp),
            xLabels = listOf(0, n / 2, n - 1).distinct().map { it to c.bars[it].date.format(fmt) },
        )
        Text("지지선 ${fmtPrice(c.support)} / 상단선 ${fmtPrice(c.upper)} · 종가 ${fmtPrice(c.close)} · 저점 접점 ${c.touches}회 · 추세 시작 ${c.startDate}",
            style = MaterialTheme.typography.labelSmall)
    }
}

/** 추세선이 없을 때: 가격만 표시 */
@Composable
fun NoChannelChart(title: String, bars: List<com.stockrec.core.Bar>, modifier: Modifier = Modifier) {
    val fmt = DateTimeFormatter.ofPattern("yy.MM")
    Column(modifier) {
        Text("$title · 추세선 없음", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium)
        Text("연 20% 이상 오르며 저점이 3번 이상 닿은 상승 지지선이 없습니다 (하락·횡보 추세).",
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (bars.size >= 2) {
            LineChart(
                listOf(Line(DoubleArray(bars.size) { bars[it].close }, MaterialTheme.colorScheme.onSurface, 1.6f)),
                Modifier.fillMaxWidth().height(160.dp),
                xLabels = listOf(0, bars.size / 2, bars.size - 1).distinct().map { it to bars[it].date.format(fmt) },
            )
        }
    }
}

/** 지수 상세: 일봉·주봉 채널 */
@Composable
fun IndexDetailScreen(st: IndexState, contentPadding: PaddingValues) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(contentPadding)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(fmtIndex(st.series.bars.last().close), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Text(if (st.ok) "지수 조건 충족 (일봉·주봉 모두 매수권)" else "지수 조건 미충족 → 이 시장 종목은 추천되지 않음",
            color = if (st.ok) OkGreen else MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
        st.daily?.let { ChannelChart(it, "일봉") } ?: NoChannelChart("일봉", st.series.bars.takeLast(250))
        st.weekly?.let { ChannelChart(it, "주봉") }
            ?: NoChannelChart("주봉", com.stockrec.core.TrendChannel.weekly(st.series.bars).takeLast(156))
        Spacer(Modifier.height(16.dp))
    }
}

private val indexFmt = java.text.DecimalFormat("#,##0.00")
fun fmtIndex(x: Double): String = if (x.isNaN()) "-" else indexFmt.format(x)
