package com.stockrec.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

data class Line(val values: DoubleArray, val color: Color, val width: Float = 2f, val dashed: Boolean = false)

/**
 * 간단한 꺾은선 차트. 모든 선은 같은 x 인덱스를 공유.
 * hLines: 가로 기준선 (예: RSI 30/70), yMin/yMax 로 범위 고정 가능.
 * xLabels: (인덱스, 라벨) 아래쪽 날짜 표시
 */
@Composable
fun LineChart(
    lines: List<Line>,
    modifier: Modifier = Modifier,
    yMin: Double? = null,
    yMax: Double? = null,
    hLines: List<Double> = emptyList(),
    xLabels: List<Pair<Int, String>> = emptyList(),
    yFormat: (Double) -> String = { fmtPrice(it) },
) {
    val measurer = rememberTextMeasurer()
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    val style = TextStyle(fontSize = 10.sp, color = labelColor)

    Canvas(modifier) {
        val n = lines.maxOfOrNull { it.values.size } ?: 0
        if (n < 2) return@Canvas
        val all = lines.flatMap { l -> l.values.filter { !it.isNaN() } }
        if (all.isEmpty()) return@Canvas
        var lo = yMin ?: all.min()
        var hi = yMax ?: all.max()
        if (hi == lo) { hi += 1; lo -= 1 }
        val pad = if (yMin == null && yMax == null) (hi - lo) * 0.05 else 0.0
        lo -= pad; hi += pad

        val ticks = if (hLines.isNotEmpty()) hLines else List(4) { lo + (hi - lo) * (it + 0.5) / 4 }
        val tickLayouts = ticks.map { measurer.measure(yFormat(it), style) }
        val left = 0f
        val right = size.width - (tickLayouts.maxOf { it.size.width } + 6.dp.toPx())  // 오른쪽에 y 라벨
        val top = 4.dp.toPx()
        val bottom = size.height - if (xLabels.isEmpty()) 4.dp.toPx() else 16.dp.toPx()
        fun x(i: Int) = left + (right - left) * i / (n - 1)
        fun y(v: Double) = (bottom - (bottom - top) * ((v - lo) / (hi - lo))).toFloat()

        // 그리드 + y 라벨
        ticks.forEachIndexed { i, t ->
            drawLine(gridColor, Offset(left, y(t)), Offset(right, y(t)), 1f,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f)))
            val layout = tickLayouts[i]
            drawText(layout, topLeft = Offset(right + 4.dp.toPx(), y(t) - layout.size.height / 2))
        }
        for ((i, label) in xLabels) {
            val layout = measurer.measure(label, style)
            val lx = (x(i) - layout.size.width / 2).coerceIn(0f, right - layout.size.width)
            drawText(layout, topLeft = Offset(lx, bottom + 2.dp.toPx()))
        }

        for (l in lines) drawSeries(l, ::x, ::y)
    }
}

private fun DrawScope.drawSeries(l: Line, x: (Int) -> Float, y: (Double) -> Float) {
    val path = Path()
    var started = false
    l.values.forEachIndexed { i, v ->
        if (v.isNaN()) { started = false; return@forEachIndexed }
        if (!started) { path.moveTo(x(i), y(v)); started = true } else path.lineTo(x(i), y(v))
    }
    drawPath(
        path, l.color,
        style = Stroke(width = l.width.dp.toPx(), pathEffect = if (l.dashed) PathEffect.dashPathEffect(floatArrayOf(10f, 8f)) else null),
    )
}

/** 거래량 막대 */
@Composable
fun BarChart(values: DoubleArray, colors: List<Color>, modifier: Modifier = Modifier, rightPadding: androidx.compose.ui.unit.Dp = 56.dp) {
    Canvas(modifier) {
        val n = values.size
        if (n == 0) return@Canvas
        val max = values.filter { !it.isNaN() }.maxOrNull()?.takeIf { it > 0 } ?: return@Canvas
        val right = size.width - rightPadding.toPx()
        val w = right / n
        values.forEachIndexed { i, v ->
            if (v.isNaN()) return@forEachIndexed
            val h = (size.height * v / max).toFloat()
            drawRect(colors[i], topLeft = Offset(i * w, size.height - h), size = androidx.compose.ui.geometry.Size(maxOf(w * 0.7f, 1f), h))
        }
    }
}
