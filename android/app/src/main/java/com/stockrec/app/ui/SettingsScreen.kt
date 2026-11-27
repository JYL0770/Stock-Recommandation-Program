package com.stockrec.app.ui

import androidx.compose.foundation.clickable
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
import androidx.compose.material3.ExperimentalMaterial3Api
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
import com.stockrec.app.Region
import com.stockrec.app.Settings
import com.stockrec.app.StockRepository
import com.stockrec.core.Universe
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
                    RadioRow(src.label, s.source == src) { onChange { it.copy(source = src) } }
                }
                Text("시장", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 8.dp))
                for (r in Region.entries) {
                    RadioRow("${r.label} — ${r.source}", s.region == r) { onChange { it.copy(region = r) } }
                }
            }
        }

        // -------------------------------------------------- 종목 범위
        if (s.source == DataSource.LIVE) {
            SectionTitle(if (s.region == Region.KR) "국내 종목 범위" else "해외 종목 범위")
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    if (s.region == Region.KR) {
                        IntSlider("코스피 시가총액 상위", s.kospiCount, 0..300, 10, "개") { v -> onChange { it.copy(kospiCount = v) } }
                        IntSlider("코스닥 시가총액 상위", s.kosdaqCount, 0..300, 10, "개") { v -> onChange { it.copy(kosdaqCount = v) } }
                        CustomField(
                            initial = s.customTickers.joinToString(", "),
                            label = "직접 추가할 종목코드 (쉼표 구분)", placeholder = "예: 005930, 000660",
                            parse = { t -> t.split(',', ' ', '\n').map { it.trim() }.filter { it.length == 6 }.distinct() },
                        ) { list -> onChange { it.copy(customTickers = list) } }
                    } else {
                        SwitchRow("미국 대형주 (NYSE·NASDAQ) ${Universe.US_LARGE.size}개", s.usLarge) { on -> onChange { it.copy(usLarge = on) } }
                        SwitchRow("장외시장 OTC·핑크시트 ${Universe.US_OTC.size}개 (해외 대형주 ADR 등)", s.usOtc) { on -> onChange { it.copy(usOtc = on) } }
                        CustomField(
                            initial = s.usCustom.joinToString(", "),
                            label = "직접 추가할 심볼 (Yahoo 기준, 쉼표 구분)", placeholder = "예: AAPL, NSRGY, BRK-B",
                            parse = { t -> t.split(',', ' ', '\n').map(StockRepository::cleanUsSymbol).filter { it.isNotEmpty() }.distinct() },
                        ) { list -> onChange { it.copy(usCustom = list) } }
                        Text("핑크시트 종목도 심볼만 알면 추가할 수 있습니다. 시장(NYSE/NASDAQ/OTC)은 시세에서 자동으로 구분합니다.",
                            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text("종목 수가 많을수록 처음 받는 시간이 길어집니다. 받은 데이터는 3시간 동안 저장해 둡니다.",
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                        Button(onClick = onApplyUniverse, enabled = needsReload && !loading) { Text("종목 범위 적용") }
                        TextButton(onClick = onRefresh, enabled = !loading) { Text("시세 새로 받기") }
                    }
                }
            }
        }

        // -------------------------------------------------- 추세선
        SectionTitle("추세선 채널")
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp)) {
                IntSlider("채널 폭 (상단선 = 지지선 × (1+폭))", (s.trendWidth * 100).roundToInt(), 5..20, 1, "%") { v ->
                    onChange { it.copy(trendWidth = v / 100.0) }
                }
                Text("지지선은 일봉 최근 1년·주봉 최근 3년에서, 연 20% 이상 오르며 저점이 3번 이상 닿은 선을 자동으로 찾습니다.",
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun RadioRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick), verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected = selected, onClick = onClick)
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun CustomField(initial: String, label: String, placeholder: String, parse: (String) -> List<String>, onList: (List<String>) -> Unit) {
    var text by rememberSaveable(label) { mutableStateOf(initial) }
    OutlinedTextField(
        value = text,
        onValueChange = { t -> text = t; onList(parse(t)) },
        label = { Text(label) },
        placeholder = { Text(placeholder) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
    )
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
