package com.stockrec.app.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.stockrec.core.F
import java.text.DecimalFormat
import java.util.Locale

// 한국 증시 관례: 상승 빨강, 하락 파랑
val UpColor = Color(0xFFE53935)
val DownColor = Color(0xFF1E6FD9)

private val Light = lightColorScheme(
    primary = Color(0xFF1E3A5F), onPrimary = Color.White,
    primaryContainer = Color(0xFFD6E3FF), onPrimaryContainer = Color(0xFF0B1D36),
    secondary = Color(0xFF5B8DEF), tertiary = Color(0xFFE53935),
    surfaceVariant = Color(0xFFEFF1F5),
)
private val Dark = darkColorScheme(
    primary = Color(0xFFA9C7FF), onPrimary = Color(0xFF0B1D36),
    primaryContainer = Color(0xFF2A4670), onPrimaryContainer = Color(0xFFD6E3FF),
    secondary = Color(0xFF8DB0F5), tertiary = Color(0xFFFF6B6B),
)

@Composable
fun AppTheme(content: @Composable () -> Unit) =
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) Dark else Light, content = content)

// ------------------------------------------------------------------ 포맷
private val priceFmt = DecimalFormat("#,##0")
private val usdFmt = DecimalFormat("#,##0.00")
private val pennyFmt = DecimalFormat("0.0000")

/** 가격 표시 방식: 국내 = 원 단위 정수, 해외 = 달러 소수 (1달러 미만 페니주는 소수 4자리) */
object PriceFormat {
    @Volatile var foreign: Boolean = false
}

fun fmtPrice(x: Double) = when {
    x.isNaN() -> "-"
    !PriceFormat.foreign -> priceFmt.format(x)
    kotlin.math.abs(x) < 1 -> pennyFmt.format(x)
    else -> usdFmt.format(x)
}
fun fmtPct(x: Double, digits: Int = 1) = if (x.isNaN()) "-" else String.format(Locale.US, "%+.${digits}f%%", x * 100)
fun fmtNum(x: Double, digits: Int = 1) = if (x.isNaN()) "-" else String.format(Locale.US, "%.${digits}f", x)
fun signColor(x: Double, neutral: Color) = when {
    x.isNaN() || x == 0.0 -> neutral
    x > 0 -> UpColor
    else -> DownColor
}

fun fmtFeature(key: String, v: Double): String = when {
    v.isNaN() -> "-"
    key == F.AMOUNT_20 && PriceFormat.foreign -> "${usdFmt.format(v)}백만$"
    key == F.AMOUNT_20 -> "${priceFmt.format(v / 100)}억"
    key == F.HIGH_52W -> fmtPct(v - 1)
    key == F.N_DAYS -> "${v.toInt()}일"
    key in F.PERCENT -> fmtPct(v)
    else -> fmtNum(v, 2)
}

// ------------------------------------------------------------------ 공통 위젯
@Composable
fun ScoreBar(value: Double, modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.secondary) {
    Box(
        modifier
            .height(8.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
    ) {
        if (!value.isNaN()) {
            Box(
                Modifier
                    .fillMaxHeight()
                    .fillMaxWidth((value / 100).toFloat().coerceIn(0f, 1f))
                    .clip(RoundedCornerShape(4.dp))
                    .background(color)
            )
        }
    }
}

@Composable
fun LabeledValue(label: String, value: String, color: Color = Color.Unspecified, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = color)
    }
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) =
    Text(text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
        modifier = modifier.padding(top = 16.dp, bottom = 8.dp))

@Composable
fun Legend(items: List<Pair<String, Color>>) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        for ((label, c) in items) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.width(12.dp).height(3.dp).background(c))
                Spacer(Modifier.width(4.dp))
                Text(label, style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}
