package lu.bourse.mobile.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import lu.bourse.mobile.data.*
import lu.bourse.mobile.ui.theme.Falling
import lu.bourse.mobile.ui.theme.Rising
import java.math.BigDecimal
import java.math.RoundingMode

@Composable
fun CandlePatternsPanel(chart: ChartData) {
    var expanded by rememberSaveable(chart.symbol, chart.timeframe) { mutableStateOf(false) }
    Panel("Figures de bougies", accent = MaterialTheme.colorScheme.tertiary) {
        val report = chart.patterns
        if (report == null) {
            Text("Détection indisponible. Le serveur doit fournir l’analyse des figures pour cet historique.")
        } else {
            Text("${report.inspected} dernières bougies clôturées · ${chart.timeframe}", style = MaterialTheme.typography.labelMedium)
            if (report.patterns.isEmpty()) Text("Aucune figure reconnue par ces règles sur cette fenêtre.")
            (if (expanded) report.patterns else report.patterns.take(3)).forEach { pattern ->
                HorizontalDivider()
                val term = when (pattern.code) {
                    "HAMMER" -> FinancialTerm.HAMMER
                    "DOJI" -> FinancialTerm.DOJI
                    "BULLISH_ENGULFING" -> FinancialTerm.BULLISH_ENGULFING
                    else -> FinancialTerm.BEARISH_ENGULFING
                }
                val candle = chart.candles.first { it.opensAt == pattern.opensAt }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CandleGlyph(candle)
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(term.title, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                            FinancialHelp(term)
                        }
                        Text(dateLabel(pattern.closesAt.toString()), style = MaterialTheme.typography.labelSmall)
                    }
                }
                Text(when (pattern.code) {
                    "HAMMER" -> "Marteau détecté après une baisse. Rebond possible, à confirmer par la suite des prix et l’activité échangée."
                    "DOJI" -> "Le prix termine près de son ouverture : hésitation possible, sans direction annoncée."
                    "BULLISH_ENGULFING" -> "Après une baisse, une bougie recouvre le corps de la précédente : reprise possible des achats."
                    else -> "Après une hausse, une bougie recouvre le corps de la précédente : pression possible des ventes."
                })
                if (pattern.code != "DOJI") {
                    KeyValue("Confirmation par le prix", when (pattern.confirmation) {
                        "PRICE_CONFIRMED" -> "Condition observée sur la bougie suivante"
                        "NOT_CONFIRMED" -> "Condition non observée sur la bougie suivante"
                        "UNAVAILABLE" -> "Indisponible : continuité ou source non vérifiable"
                        else -> "À confirmer : prochaine bougie clôturée attendue"
                    })
                    pattern.confirmationAt?.let { Text("Vérification au ${dateLabel(it.toString())}", style = MaterialTheme.typography.labelSmall) }
                }
                KeyValue("Volume de la figure", when (pattern.volumeStatus) {
                    "ABOVE_USUAL" -> "Supérieur à l’habitude · ×${amount(pattern.volumeRatio)}"
                    "NOT_ELEVATED" -> "Pas de volume élevé · ×${amount(pattern.volumeRatio)}"
                    else -> "Comparaison indisponible"
                })
            }
            if (report.patterns.size > 3) TextButton(onClick = { expanded = !expanded }) {
                Text(if (expanded) "Réduire les figures" else "Voir les ${report.patterns.size} figures détectées")
            }
            Text("Lecture historique des données importées. Une figure ne garantit pas la suite. Les règles V2 peuvent contribuer aux scores selon leur intervalle et leur horizon ; les points appliqués sont détaillés dans Intelligence.", style = MaterialTheme.typography.bodySmall)
        }
    }
}

/** Exact OHLC proportions; decimal arithmetic until the final conversion to screen coordinates. */
@Composable
private fun CandleGlyph(candle: Candle) {
    val rising = candle.close >= candle.open
    val color = if (rising) Rising else Falling
    Canvas(Modifier.width(42.dp).height(72.dp).semantics { contentDescription = "Forme de la bougie détectée" }) {
        val range = (candle.high - candle.low).takeIf { it.signum() > 0 } ?: BigDecimal.ONE
        fun y(v: BigDecimal) = size.height * (0.9f - 0.8f * (v - candle.low).divide(range, 10, RoundingMode.HALF_UP).toFloat())
        val x = size.width / 2
        drawLine(color, Offset(x, y(candle.high)), Offset(x, y(candle.low)), 2.dp.toPx())
        val top = minOf(y(candle.open), y(candle.close))
        val bottom = maxOf(y(candle.open), y(candle.close))
        drawRect(color, Offset(x - 7.dp.toPx(), top), Size(14.dp.toPx(), maxOf(2.dp.toPx(), bottom - top)))
    }
}
