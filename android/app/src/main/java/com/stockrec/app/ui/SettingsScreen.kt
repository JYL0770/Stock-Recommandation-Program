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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.stockrec.app.DataSource
import com.stockrec.app.Settings
import com.stockrec.core.Factor
import com.stockrec.core.STRATEGIES
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    s: Settings,
    needsReload: Boolean,
    loading: Boolean,
    onChange: ((Settings) -> Settings) -> Unit,
    onApplyUniverse: () -> Unit,
    onRefresh: () -> Unit,
    contentPadding: PaddingValues,
) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(contentPadding)
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        // -------------------------------------------------- 데이터
        SectionTitle("데이터")
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp)) {
                for (src in DataSource.entries) {
                    Row(
                        Modifier.fillMaxWidth().clickable { onChange { it.copy(source = src) } },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = s.source == src, onClick = { onChange { it.copy(source = src) } })
                        Text(src.label)
                    }
                }
                if (s.source == DataSource.LIVE) {
                    IntSlider("코스피 시가총액 상위", s.kospiCount, 0..300, 10, "개") { v -> onChange { it.copy(kospiCount = v) } }
                    IntSlider("코스닥 시가총액 상위", s.kosdaqCount, 0..300, 10, "개") { v -> onChange { it.copy(kosdaqCount = v) } }
                    var custom by rememberSaveable { mutableStateOf(s.customTickers.joinToString(", ")) }
                    OutlinedTextField(
                        value = custom,
                        onValueChange = { text ->
                            custom = text
                            val list = text.split(',', ' ', '\n').map { it.trim() }.filter { it.length == 6 }.distinct()
                            onChange { it.copy(customTickers = list) }
                        },
                        label = { Text("직접 추가할 종목코드 (쉼표 구분)") },
                        placeholder = { Text("예: 005930, 000660") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    )
                    Text("종목 수가 많을수록 처음 받는 시간이 길어집니다 (150종목 약 10~30초). 받은 데이터는 3시간 동안 저장해 둡니다.",
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                        Button(onClick = onApplyUniverse, enabled = needsReload && !loading) { Text("종목 범위 적용") }
                        TextButton(onClick = onRefresh, enabled = !loading) { Text("시세 새로 받기") }
                    }
                }
            }
        }

        // -------------------------------------------------- 전략 / 가중치
        SectionTitle("전략 · 팩터 가중치")
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            STRATEGIES.forEachIndexed { i, st ->
                FilterChip(
                    selected = s.strategyIndex == i,
                    onClick = { onChange { it.copy(strategyIndex = i, weights = st.weights) } },
                    label = { Text(st.name, style = MaterialTheme.typography.labelSmall) },
                )
            }
        }
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp)) {
                for (f in Factor.entries) {
                    val w = (s.weights[f] ?: 0.0).roundToInt()
                    IntSlider(f.label, w, 0..100, 5, "") { v -> onChange { it.copy(weights = it.weights + (f to v.toDouble())) } }
                    Text(f.help, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(6.dp))
                }
                if (s.isCustomWeights) {
                    TextButton(onClick = { onChange { it.copy(weights = it.strategy.weights) } }) { Text("'${s.strategy.name}' 기본값으로 되돌리기") }
                }
                if (s.source == DataSource.LIVE && (s.weights[Factor.VALUE] ?: 0.0) > 0) {
                    Text("※ 실시간 모드에는 PER·PBR·ROE 데이터가 없어 밸류 팩터는 제외되고 나머지 가중치로 계산됩니다.",
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                }
            }
        }

        // -------------------------------------------------- 필터
        SectionTitle("필터")
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp)) {
                IntSlider("20일 평균 거래대금 하한", s.minAmountEok.roundToInt(), 0..500, 5, "억원") { v ->
                    onChange { it.copy(minAmountEok = v.toDouble()) }
                }
                IntSlider("최소 데이터 일수", s.minDays, 60..500, 10, "일") { v -> onChange { it.copy(minDays = v) } }
                IntSlider("추천 종목 수", s.topN, 5..50, 1, "개") { v -> onChange { it.copy(topN = v) } }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("RSI 과열 종목 제외", Modifier.weight(1f))
                    Switch(checked = s.maxRsi != null, onCheckedChange = { on -> onChange { it.copy(maxRsi = if (on) 80.0 else null) } })
                }
                s.maxRsi?.let { r ->
                    IntSlider("RSI 상한", r.roundToInt(), 50..95, 1, "") { v -> onChange { it.copy(maxRsi = v.toDouble()) } }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("120일선 위 종목만", Modifier.weight(1f))
                    Switch(checked = s.aboveMa120, onCheckedChange = { on -> onChange { it.copy(aboveMa120 = on) } })
                }
                Text("시장", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 8.dp))
                Row {
                    for (m in listOf("KOSPI", "KOSDAQ", "직접추가")) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = m in s.markets, onCheckedChange = { on ->
                                onChange { it.copy(markets = if (on) it.markets + m else it.markets - m) }
                            })
                            Text(m, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun IntSlider(label: String, value: Int, range: IntRange, step: Int, unit: String, onChange: (Int) -> Unit) {
    Column(Modifier.padding(top = 8.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text("$value$unit", style = MaterialTheme.typography.bodyMedium)
        }
        Slider(
            value = value.toFloat().coerceIn(range.first.toFloat(), range.last.toFloat()),
            onValueChange = { v ->
                val snapped = (((v - range.first) / step).roundToInt() * step + range.first).coerceIn(range)
                if (snapped != value) onChange(snapped)
            },
            valueRange = range.first.toFloat()..range.last.toFloat(),
        )
    }
}
