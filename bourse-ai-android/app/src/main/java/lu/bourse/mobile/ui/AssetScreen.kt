package lu.bourse.mobile.ui

import lu.bourse.mobile.ui.theme.Rising
import lu.bourse.mobile.ui.theme.Falling
import lu.bourse.mobile.ui.theme.RiskAccent
import lu.bourse.mobile.ui.theme.ResearchAccent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
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
                val dates = remember(chart.candles) { chart.candles.map { it.closesAt } }
                val prices = remember(chart.candles) { chart.candles.map { it.close.toDouble() } }
                val indicators = remember(prices) { indicatorHistory(prices) }
                val cyan = MaterialTheme.colorScheme.primary
                Panel("Cours et moyenne mobile · $timeframe", accent = cyan) {
                    Text("Pincez pour zoomer, glissez horizontalement ou choisissez une date avec le curseur.")
                    MarketPlot("Cours et moyenne mobile", dates,
                        listOf(PlotLine("Clôture", prices, cyan), PlotLine("SMA 20", indicators.sma, RiskAccent)), chart.currency)
                }
                Panel("MACD · 12, 26, 9", accent = ResearchAccent) {
                    Text("Violet · MACD   Orange · signal   Vert / rouge · écart au signal")
                    MarketPlot("MACD", dates, listOf(
                        PlotLine("MACD", indicators.macd, ResearchAccent),
                        PlotLine("Signal MACD", indicators.signal, RiskAccent),
                        PlotLine("Histogramme", indicators.histogram, cyan, bars = true),
                    ), chart.currency, guides = listOf(0.0))
                }
                Panel("RSI · 14 périodes", accent = Rising) {
                    Text("Échelle de 0 à 100, avec les repères 30 et 70.")
                    MarketPlot("RSI", dates, listOf(PlotLine("RSI 14", indicators.rsi, Rising)),
                        bounds = 0.0..100.0, guides = listOf(30.0, 70.0))
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
        RelatedStocks(store, config, symbol)
        Button(onClick = explain, modifier = Modifier.fillMaxWidth()) { Text("Comprendre le score") }
        OutlinedButton(onClick = calendar, modifier = Modifier.fillMaxWidth()) { Text("Calendrier et données attendues") }
        TextButton(onClick = { refresh() }) { Text("Actualiser l’historique") }
    }
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
