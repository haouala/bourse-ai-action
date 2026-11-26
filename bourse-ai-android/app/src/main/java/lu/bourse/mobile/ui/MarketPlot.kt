package lu.bourse.mobile.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import lu.bourse.mobile.data.dateLabel
import lu.bourse.mobile.ui.theme.Falling
import lu.bourse.mobile.ui.theme.Rising
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

internal data class PlotLine(val label: String, val values: List<Double?>, val color: Color, val bars: Boolean = false)

@Composable
internal fun MarketPlot(
    title: String,
    dates: List<Instant>,
    lines: List<PlotLine>,
    unit: String = "",
    bounds: ClosedFloatingPointRange<Double>? = null,
    guides: List<Double> = emptyList(),
) {
    if (dates.isEmpty() || lines.none { line -> line.values.any { it != null } }) {
        Text("Historique insuffisant pour ce graphique.")
        return
    }
    val total = dates.size
    val minimum = minOf(total, 5)
    var span by remember(dates) { mutableIntStateOf(total) }
    var start by remember(dates) { mutableFloatStateOf(0f) }
    var selected by remember(dates) { mutableIntStateOf(dates.lastIndex) }
    val first = start.roundToInt().coerceIn(0, total - span)
    val last = first + span - 1
    val view = first..last
    fun zoom(scale: Float, anchor: Float) {
        if (!scale.isFinite() || scale <= 0) return
        val next = (span / scale).roundToInt().coerceIn(minimum, total)
        start = (start + (span - next) * anchor).coerceIn(0f, (total - next).toFloat())
        span = next
        selected = selected.coerceIn(start.roundToInt(), start.roundToInt() + span - 1)
    }
    fun select(index: Int) {
        selected = index.coerceIn(dates.indices)
        if (selected !in view) start = (selected - span / 2).coerceIn(0, total - span).toFloat()
    }
    fun format(value: Double): String = String.format(Locale.FRANCE, if (value != 0.0 && kotlin.math.abs(value) < 0.01) "%.3g" else "%.2f", value)

    val visible = lines.flatMap { line -> view.mapNotNull { line.values[it] } } + guides
    val low = bounds?.start ?: visible.minOrNull() ?: 0.0
    val high = bounds?.endInclusive ?: visible.maxOrNull() ?: 1.0
    val margin = if (bounds != null) 0.0 else if (high == low) maxOf(kotlin.math.abs(high) * .01, .01) else (high - low) * .06
    val bottomValue = low - margin
    val topValue = high + margin
    val grid = MaterialTheme.colorScheme.outlineVariant
    val ink = MaterialTheme.colorScheme.onSurfaceVariant
    val measure = rememberTextMeasurer()
    val datePattern = if (dates[last].epochSecond - dates[first].epochSecond < 86400) "HH:mm" else "dd/MM/yy"
    val axisDate = remember(datePattern) { DateTimeFormatter.ofPattern(datePattern).withZone(ZoneId.systemDefault()) }

    Surface(color = MaterialTheme.colorScheme.background, shape = RoundedCornerShape(16.dp)) {
        Canvas(Modifier.fillMaxWidth().height(208.dp)
            .pointerInput(dates) {
                detectTapGestures { tap ->
                    val ratio = ((tap.x - 66.dp.toPx()) / (size.width - 78.dp.toPx()).coerceAtLeast(1f)).coerceIn(0f, 1f)
                    val currentFirst = start.roundToInt()
                    selected = currentFirst + (ratio * (span - 1)).roundToInt()
                }
            }
            .pointerInput(dates) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    var accumulated = 1f
                    do {
                        val event = awaitPointerEvent()
                        if (event.changes.count { it.pressed && it.previousPressed } >= 2) {
                            accumulated *= event.calculateZoom()
                            if (kotlin.math.abs(accumulated - 1f) > .08f) {
                                val anchor = ((event.calculateCentroid().x - 66.dp.toPx()) /
                                    (size.width - 78.dp.toPx()).coerceAtLeast(1f)).coerceIn(0f, 1f)
                                zoom(accumulated, anchor)
                                accumulated = 1f
                            }
                            event.changes.forEach { it.consume() }
                        }
                    } while (event.changes.any { it.pressed })
                }
            }
            .pointerInput(dates) {
                detectHorizontalDragGestures { change, drag ->
                    change.consume()
                    start = (start - drag * span / (size.width - 78.dp.toPx()).coerceAtLeast(1f))
                        .coerceIn(0f, (total - span).toFloat())
                    selected = selected.coerceIn(start.roundToInt(), start.roundToInt() + span - 1)
                }
            }.semantics { contentDescription = title }) {
            val left = 66.dp.toPx()
            val right = size.width - 12.dp.toPx()
            val top = 13.dp.toPx()
            val bottom = size.height - 28.dp.toPx()
            val width = (right - left).coerceAtLeast(1f)
            fun x(i: Int) = left + width * if (span == 1) .5f else (i - first).toFloat() / (span - 1)
            fun y(value: Double) = bottom - (bottom - top) * ((value - bottomValue) / (topValue - bottomValue)).toFloat()
            val axis = TextStyle(color = ink, fontSize = 10.sp)
            repeat(if (bounds == null) 4 else 2) { tick ->
                val value = topValue - (topValue - bottomValue) * tick / (if (bounds == null) 3 else 1)
                val row = y(value)
                drawLine(grid, Offset(left, row), Offset(right, row))
                val text = measure.measure(format(value), axis)
                drawText(text, topLeft = Offset((left - text.size.width - 6.dp.toPx()).coerceAtLeast(0f), row - text.size.height / 2f))
            }
            drawLine(ink, Offset(left, top), Offset(left, bottom))
            drawLine(ink, Offset(left, bottom), Offset(right, bottom))
            listOf(first, (first + last) / 2, last).distinct().forEach { i ->
                val label = measure.measure(axisDate.format(dates[i]), axis)
                drawText(label, topLeft = Offset((x(i) - label.size.width / 2f).coerceIn(0f, (size.width - label.size.width).coerceAtLeast(0f)), bottom + 5.dp.toPx()))
            }
            for (guide in guides) {
                val row = y(guide)
                drawLine(ink.copy(alpha = .55f), Offset(left, row), Offset(right, row))
                val label = measure.measure(format(guide), axis)
                drawText(label, topLeft = Offset((left - label.size.width - 6.dp.toPx()).coerceAtLeast(0f), row - label.size.height / 2f))
            }
            lines.forEach { line ->
                val path = Path()
                var connected = false
                for (i in view) {
                    val value = line.values[i]
                    if (value == null) { connected = false; continue }
                    val point = Offset(x(i), y(value))
                    if (line.bars) drawLine(if (value >= 0) Rising else Falling, Offset(point.x, y(0.0)), point,
                        (width / span * .65f).coerceIn(1f, 10.dp.toPx()))
                    else if (connected) path.lineTo(point.x, point.y)
                    else { path.moveTo(point.x, point.y); drawCircle(line.color, 2.dp.toPx(), point) }
                    connected = true
                }
                if (!line.bars) drawPath(path, line.color, style = Stroke(2.dp.toPx()))
            }
            if (selected in view) {
                drawLine(ink, Offset(x(selected), top), Offset(x(selected), bottom))
                lines.forEach { line -> line.values[selected]?.let { drawCircle(line.color, 3.dp.toPx(), Offset(x(selected), y(it))) } }
            }
        }
    }
    Text(dateLabel(dates[selected].toString()), style = MaterialTheme.typography.labelLarge)
    lines.forEach { line ->
        val value = line.values[selected]?.let { "${format(it)} $unit".trim() } ?: "—"
        KeyValue(line.label, value)
    }
    if (total > 1) {
        Slider(value = selected.toFloat(), onValueChange = { select(it.roundToInt()) },
            valueRange = 0f..dates.lastIndex.toFloat(), modifier = Modifier.semantics { contentDescription = "Choisir une date pour $title" })
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedButton(onClick = { zoom(0.67f, .5f) }, enabled = span < total) { Text("−", Modifier.semantics { contentDescription = "Dézoomer" }) }
            OutlinedButton(onClick = { zoom(1.5f, (selected - first).toFloat() / (span - 1).coerceAtLeast(1)) },
                enabled = span > minimum) { Text("+", Modifier.semantics { contentDescription = "Zoomer" }) }
            TextButton(onClick = { start = 0f; span = total; selected = dates.lastIndex }) { Text("Tout afficher") }
        }
    }
}
