package lu.bourse.mobile.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.Brush
import kotlin.math.roundToInt
import lu.bourse.mobile.ui.theme.Rising
import lu.bourse.mobile.ui.theme.Falling
import lu.bourse.mobile.ui.theme.RiskAccent
import lu.bourse.mobile.ui.theme.ResearchAccent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import lu.bourse.mobile.*
import lu.bourse.mobile.data.*

@Composable
fun AssetScreen(store: AppStore, symbol: String, explain: () -> Unit, calendar: () -> Unit, research: () -> Unit = {}) {
    var timeframe by rememberSaveable { mutableStateOf("15m") }
    var table by rememberSaveable { mutableStateOf(false) }
    val config by store.config.collectAsStateWithLifecycle()
    val key = "chart:$symbol:$timeframe"
    fun refresh() = store.load(key, api("charts", symbol), mapOf("timeframe" to timeframe, "limit" to "250"))
    LaunchedEffect(symbol, timeframe, config) { refresh() }
    val scoreKey = "asset-score:$symbol"
    LaunchedEffect(symbol, config) { store.load(scoreKey, api("scoring", symbol), mapOf("horizonDays" to "30")) }
    Page(symbol, "Historique des cours clôturés") {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { chartFrames.forEach { frame -> FilterChip(selected = timeframe == frame, onClick = { timeframe = frame }, label = { Text(frame) }) } }
        DataBlock(store, key, decode = ::chartData) { chart ->
            if (chart.candles.isEmpty()) Note("Aucune bougie importée pour cet intervalle. Le backend doit récupérer les données de ce symbole.")
            else {
                val last = chart.candles.last()
                Panel("Dernière clôture disponible", accent = Rising) {
                    Text(priceText(last.close, chart.currency), style = MaterialTheme.typography.headlineLarge)
                    chart.candles.getOrNull(chart.candles.lastIndex - 1)?.close?.takeIf { it.signum() > 0 }?.let { previous ->
                        val change = (last.close - previous).movePointRight(2).divide(previous, 2, java.math.RoundingMode.HALF_UP)
                        Text("${if (change.signum() > 0) "+" else ""}${amount(change)} % · vs clôture précédente",
                            color = if (change.signum() >= 0) Rising else Falling)
                    }
                    Text(dateLabel(last.closesAt.toString()))
                    Text("Source : ${display(last.provider)} · ${chart.candles.size} bougies")
                }
                Panel("Historique · $timeframe") {
                    PriceChart(chart)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text(dateLabel(chart.candles.first().opensAt.toString()), style = MaterialTheme.typography.labelSmall); Text(dateLabel(last.closesAt.toString()), style = MaterialTheme.typography.labelSmall) }
                    Note("Cours importés : vérifier les délais, les ajustements et le calendrier avant toute décision.")
                }
                Panel("Indicateurs calculés par le backend") {
                    val indicators = chart.indicators
                    if (indicators == null) Text("Historique insuffisant : au moins 35 bougies closes sont nécessaires.") else {
                        listOf("sma20" to "Moyenne SMA20", "rsi14" to "RSI14", "macd" to "MACD", "macdSignal" to "Signal MACD", "macdHistogram" to "Histogramme MACD").forEach { (key, title) -> KeyValue(title, amount(indicators.decimal(key))) }
                    }
                }
                TextButton(onClick = { table = !table }) { Text(if (table) "Masquer les bougies" else "Voir les 12 dernières bougies") }
                if (table) chart.candles.takeLast(12).forEach { KeyValue(dateLabel(it.opensAt.toString()), "O ${priceText(it.open)} · H ${priceText(it.high)} · B ${priceText(it.low)} · C ${priceText(it.close)} ${chart.currency}") }
            }
        }
        Panel("Résumé des scores", accent = ResearchAccent) {
            DataBlock(store, scoreKey, decode = { it.objectValue() }) { score ->
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    ScoreRing("Opportunité / 100", score.decimal("opportunityScore")?.toFloat(), Rising)
                    ScoreRing("Risque / 100", score.decimal("riskScore")?.toFloat(), RiskAccent)
                    ScoreRing("Fiabilité / 100", score.decimal("reliabilityScore")?.toFloat(), ResearchAccent)
                }
            }
            Button(onClick = research, modifier = Modifier.fillMaxWidth()) { Text("Intelligence : prévisions, graphiques, ML et risque") }
        }
        Button(onClick = explain, modifier = Modifier.fillMaxWidth()) { Text("Comprendre le score") }
        OutlinedButton(onClick = calendar, modifier = Modifier.fillMaxWidth()) { Text("Calendrier et données attendues") }
        TextButton(onClick = { refresh() }) { Text("Actualiser l’historique") }
    }
}

@Composable
fun PriceChart(chart: ChartData) {
    val prices = remember(chart.candles) { chart.candles.map { it.close } }
    if (prices.isEmpty()) return
    val points = remember(prices) { normalizedPoints(prices) }
    var selected by remember(chart.candles) { mutableIntStateOf(prices.lastIndex) }
    val line = if (prices.last() >= prices.first()) Rising else Falling
    val candle = chart.candles[selected]
    KeyValue(dateLabel(candle.closesAt.toString()), priceText(candle.close, chart.currency))
    val grid = MaterialTheme.colorScheme.outlineVariant
    Text("Haut ${priceText(prices.max(), chart.currency)}", style = MaterialTheme.typography.labelSmall)
    Canvas(Modifier.fillMaxWidth().height(190.dp).pointerInput(points.size) {
        detectTapGestures { tap ->
            if (size.width > 0) selected = (tap.x / size.width * points.lastIndex).roundToInt().coerceIn(0, points.lastIndex)
        }
    }.semantics { contentDescription = "Graphique historique de ${chart.symbol}, ${points.size} clôtures, de ${prices.first()} à ${prices.last()} ${chart.currency}" }) {
        val top = 12.dp.toPx(); val height = size.height - 2 * top
        repeat(4) { val y = top + height * it / 3; drawLine(grid, Offset(0f, y), Offset(size.width, y), 1.dp.toPx()) }
        if (points.size == 1) drawCircle(line, 4.dp.toPx(), Offset(size.width / 2, top + height * (1 - points.first())))
        else {
            val path = Path()
            points.forEachIndexed { i, value -> val x = size.width * i / (points.size - 1); val y = top + height * (1 - value); if (i == 0) path.moveTo(x, y) else path.lineTo(x, y) }
            val area = Path().apply { addPath(path); lineTo(size.width, top + height); lineTo(0f, top + height); close() }
            drawPath(area, Brush.verticalGradient(listOf(line.copy(alpha = .32f), line.copy(alpha = 0f)), startY = top, endY = top + height))
            drawPath(path, line, style = Stroke(2.5.dp.toPx()))
            val x = size.width * selected / points.lastIndex
            val y = top + height * (1 - points[selected])
            drawLine(grid, Offset(x, top), Offset(x, top + height), 1.dp.toPx())
            drawCircle(line, 4.dp.toPx(), Offset(x, y))
        }
    }
    Text("Bas ${priceText(prices.min(), chart.currency)}", style = MaterialTheme.typography.labelSmall)
}

@Composable
fun CalendarScreen(store: AppStore, symbol: String) {
    val config by store.config.collectAsStateWithLifecycle()
    LaunchedEffect(symbol, config) { store.load("calendar:$symbol", api("calendars", "assets", symbol, "state"), mapOf("timeframe" to "15m")) }
    Page("Calendrier · $symbol", "La fraîcheur dépend des séances et des données réellement attendues.") {
        DataBlock(store, "calendar:$symbol", decode = { it.objectValue() }) { row ->
            Panel("État du marché") {
                KeyValue("Calendrier", display(row.text("calendarCode")))
                KeyValue("État", display(row.text("marketState")))
                KeyValue("Dernière clôture attendue", dateLabel(row.text("expectedClose")))
                KeyValue("Prochaine ouverture", dateLabel(row.text("nextOpen")))
                row.text("reason")?.let { Text(it) }
            }
        }
        Note("Un marché fermé peut conserver une clôture de référence datée. Une clôture manquante ou un calendrier inconnu ne devient pas une donnée fiable. Le FX possède son propre horodatage.")
        TextButton(onClick = { store.load("calendar:$symbol", api("calendars", "assets", symbol, "state")) }) { Text("Actualiser l’état") }
    }
}
