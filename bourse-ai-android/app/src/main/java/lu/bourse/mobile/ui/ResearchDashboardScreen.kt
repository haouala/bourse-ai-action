package lu.bourse.mobile.ui

import androidx.compose.foundation.Canvas
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.math.BigDecimal
import lu.bourse.mobile.AppStore
import lu.bourse.mobile.api
import lu.bourse.mobile.data.*

/**
 * Integrated module-9 charts, not replacements for the existing intraday charts.
 * Shows only backend-observed dates and estimated future horizon ENDPOINTS.
 */
@Composable
fun ResearchDashboardScreen(store: AppStore, symbol: String) {
    val config by store.config.collectAsStateWithLifecycle()
    var horizon by rememberSaveable(symbol) { mutableIntStateOf(30) }
    val prefix = "research:$symbol"
    LaunchedEffect(config, symbol) {
        store.load("$prefix:history", api("charts", symbol, "history"), mapOf("limit" to "250"))
        store.load("$prefix:scenarios", api("charts", symbol, "scenarios"))
        store.load("$prefix:predictions", api("charts", symbol, "predictions"), mapOf("limit" to "100"))
        store.load("$prefix:ml", api("charts", symbol, "ml"), mapOf("limit" to "100"))
        store.load("$prefix:market-ml", api("ml", "market", symbol))
        store.load("$prefix:regime", api("regimes", symbol))
        store.load("$prefix:bottleneck", api("ai-bottlenecks", symbol))
    }
    LaunchedEffect(config, symbol, horizon) {
        store.load("$prefix:score", api("scoring", symbol), mapOf("horizonDays" to horizon.toString()))
    }
    Page("Intelligence · $symbol", "Données de marché, scénarios, évaluation et modèles du backend.") {
        Note(if (config.demo) "MODE DÉMO : toutes les données sont fictives." else
            "MODE SERVEUR : rien n'est inventé si les données sont absentes. Les scores ne sont pas des probabilités.")
        Panel("Historique quotidien observé") {
            DataBlock(store, "$prefix:history", decode = ::historicalResearchChart) { result ->
                if (result.points.isEmpty()) Text("Aucune clôture importée.")
                else {
                    val prices = result.points.map { it.close }
                    ResearchLineChart(prices, "Historique quotidien observé")
                    KeyValue("Premier / dernier prix",
                        priceText(prices.first(), result.currency) + " / " + priceText(prices.last(), result.currency))
                    KeyValue("Dernière observation", dateLabel(result.points.last().date))
                }
            }
        }
        Panel("Scénarios futurs · 7, 30, 90, 180 jours") {
            DataBlock(store, "$prefix:scenarios", decode = ::scenarioResearchChart) { result ->
                if (result.scenarios.isEmpty()) Text("Historique insuffisant pour calculer ces horizons.")
                else {
                    result.reference?.let { KeyValue("Cours de référence observé", priceText(it, result.currency)) }
                    ScenarioEndpointChart(result.scenarios)
                    result.scenarios.forEach { p ->
                        Text(p.horizon.toString() + " jours · " + p.date, style = MaterialTheme.typography.titleSmall)
                        KeyValue("Défavorable / central / favorable", priceText(p.low, result.currency) + " / " +
                            priceText(p.central, result.currency) + " / " + priceText(p.high, result.currency))
                        Text("Taille du jeu de validation : " + p.holdout, style = MaterialTheme.typography.labelSmall)
                    }
                }
                if (result.unavailable.isNotEmpty()) Text("Horizons indisponibles : " +
                    result.unavailable.joinToString() + " jours")
                Note("Chaque marqueur est une échéance calculée. Les prix intermédiaires ne sont PAS prédits. La fourchette historique n'est pas un intervalle de confiance.")
            }
        }
        Panel("Scores et régime de marché") {
            Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                listOf(7, 30, 90, 180).forEach { h ->
                    FilterChip(selected = horizon == h, onClick = { horizon = h }, label = { Text(h.toString() + " j") })
                }
            }
            DataBlock(store, "$prefix:score", decode = { it.objectValue() }) { r ->
                KeyValue("Opportunité", (r.text("opportunityScore") ?: "Indisponible") + " / 100")
                KeyValue("Risque (100 = élevé)", (r.text("riskScore") ?: "Indisponible") + " / 100")
                KeyValue("Fiabilité", r.text("reliabilityScore")?.plus(" / 100") ?: "Non établie")
                KeyValue("État du calcul", display(r.text("status")))
            }
            DataBlock(store, "$prefix:regime", decode = { it.objectValue() }) { r ->
                val signal = r.child("signal")
                KeyValue("Régime historique", signal?.text("regime") ?: display(r.text("status")))
                signal?.let {
                    KeyValue("Volatilité annualisée", display(it.text("annualizedVolatilityPct")) + " %")
                    KeyValue("Drawdown historique", display(it.text("drawdownPct")) + " %")
                }
            }
        }
        Panel("Prévisions vs observations") {
            DataBlock(store, "$prefix:predictions", decode = ::predictionResearchChart) { result ->
                val completed = result.points.filter { it.actual != null && it.status == "EVALUATED" }
                if (result.points.isEmpty()) Text("Pas de prédictions enregistrées.")
                else {
                    KeyValue("Prévisions évaluées", completed.size.toString() + " / " + result.points.size)
                    if (completed.isNotEmpty()) {
                        val current = completed.last()
                        val comparable = completed.filter { it.model == current.model && it.horizon == current.horizon }
                        KeyValue("Série comparable", current.model + " / " + current.horizon + " jours")
                        PredictionDots(comparable)
                        comparable.takeLast(5).forEach { p ->
                            KeyValue(p.issuedAt.take(10), "Prévu " + priceText(p.expected) +
                                " · observé " + priceText(p.actual) + " · erreur " + priceText(p.errorPct) + " %")
                        }
                    }
                    if (completed.size != result.points.size)
                        Text("Les prévisions en attente n'ont aucun prix réalisé affiché.")
                }
            }
        }
        Panel("Précision du Machine Learning vs référence") {
            DataBlock(store, "$prefix:ml", decode = ::modelResearchChart) { result ->
                if (result.points.isEmpty()) Text("Aucun entraînement validé disponible.")
                else {
                    val last = result.points.last()
                    val matched = result.points.filter { it.model == last.model && it.horizon == last.horizon }
                    KeyValue("Modèle / horizon", last.model + " / " + last.horizon + " séances")
                    KeyValue("Dernier test", last.count.toString() + " observations")
                    ModelAccuracyDots(matched)
                    KeyValue("Précision mesurée / baseline",
                        priceText(last.accuracy) + " % / " + priceText(last.baseline) + " %")
                    Note("Précision sur jeu de test ≠ probabilité de gain ou rendement futur.")
                }
            }
        }
        Panel("ML sur cours historiques") {
            DataBlock(store, "$prefix:market-ml", decode = ::marketModelResults) { results ->
                if (results.isEmpty()) Text("Aucun résultat de recherche disponible.")
                results.forEach { result ->
                    val horizonLabel = when (result.horizon) {
                        "DAY" -> "1 séance"
                        "MONTH" -> "21 séances"
                        "YEAR" -> "252 séances"
                        else -> result.horizon
                    }
                    KeyValue(horizonLabel, result.model)
                    KeyValue("Clôture de référence", dateLabel(result.asOf))
                    val estimate = result.estimate
                    if (estimate != null) KeyValue("Estimation expérimentale de hausse nette",
                        amount(estimate.movePointRight(2)) + " %")
                    else Text("Estimation indisponible pour la dernière clôture vérifiée.")
                    KeyValue("Brier modèle / référence", amount(result.brier) + " / " + amount(result.constantBrier))
                    KeyValue("Observations de test", result.testCount.toString())
                }
            }
        }
        Panel("AI Bottleneck · éléments sourcés") {
            DataBlock(store, "$prefix:bottleneck", decode = { it.objectValue() }) { r ->
                val result = r.child("assessment")
                KeyValue("Score exploratoire", result?.text("score")?.plus(" / 100") ?: "Preuves insuffisantes")
                KeyValue("État", display(result?.text("status")))
                r.list("evidence").take(4).forEach { evidence ->
                    Text(display(evidence.text("summary")), style = MaterialTheme.typography.bodySmall)
                    Evidence(evidence.text("sourceUrl"), "Vérifier la source")
                }
                Note("Ces liens sont enregistrés mais le système ne certifie pas automatiquement leur contenu.")
            }
        }
        OutlinedButton(
            onClick = {
                for (part in listOf("history", "scenarios", "predictions", "ml", "regime", "bottleneck")) {
                    val endpoint = when (part) {
                        "regime" -> api("regimes", symbol)
                        "bottleneck" -> api("ai-bottlenecks", symbol)
                        else -> api("charts", symbol, part)
                    }
                    val options = when (part) {
                        "history" -> mapOf("limit" to "250")
                        "predictions", "ml" -> mapOf("limit" to "100")
                        else -> emptyMap()
                    }
                    store.load("$prefix:$part", endpoint, options)
                }
                store.load("$prefix:market-ml", api("ml", "market", symbol))
                store.load("$prefix:score", api("scoring", symbol), mapOf("horizonDays" to horizon.toString()))
            }, modifier = Modifier.fillMaxWidth().testTag("refresh-research")
        ) { Text("Actualiser analyses et graphiques") }
    }
}

/** Connects consecutive ACTUALLY OBSERVED daily closing prices. */
@Composable
private fun ResearchLineChart(prices: List<BigDecimal>, label: String) {
    if (prices.isEmpty()) return
    val yValues = normalizedPoints(prices)
    val lineColor = MaterialTheme.colorScheme.primary
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    Canvas(Modifier.fillMaxWidth().height(170.dp).semantics { contentDescription = label }) {
        repeat(4) { i ->
            val y = size.height * (i + 1) / 5f
            drawLine(gridColor, Offset(0f, y), Offset(size.width, y), strokeWidth = 1.dp.toPx())
        }
        val path = Path()
        yValues.forEachIndexed { i, v ->
            val x = size.width * (i + 1) / (yValues.size + 1f)
            val y = size.height * (.9f - .8f * v)
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        if (yValues.size == 1) drawCircle(lineColor, 4.dp.toPx(), Offset(size.width / 2, size.height / 2))
        else drawPath(path, lineColor, style = Stroke(2.5.dp.toPx()))
    }
}

/** Independent vertical scenarios per horizon; no invented intermediate daily trajectory. */
@Composable
private fun ScenarioEndpointChart(points: List<ScenarioPrice>) {
    if (points.isEmpty()) return
    val values = points.flatMap { listOf(it.low, it.central, it.high) }
    val minimum = values.min()
    val spread = (values.max() - minimum).takeIf { it.signum() > 0 } ?: BigDecimal.ONE
    val center = MaterialTheme.colorScheme.primary
    val edges = MaterialTheme.colorScheme.tertiary
    Canvas(Modifier.fillMaxWidth().height(170.dp).semantics {
        contentDescription = "Quatre scénarios par échéance sans interpolation de cours"
    }) {
        points.forEachIndexed { i, p ->
            val x = size.width * (i + 1) / (points.size + 1f)
            fun y(v: BigDecimal) = size.height * (.9f - .8f *
                (v - minimum).divide(spread, 10, java.math.RoundingMode.HALF_UP).toFloat())
            drawLine(edges, Offset(x, y(p.low)), Offset(x, y(p.high)), strokeWidth = 3.dp.toPx())
            drawCircle(center, radius = 5.dp.toPx(), center = Offset(x, y(p.central)))
        }
    }
}

/** Plot only settled predictions; pending ones remain absent. */
@Composable
private fun PredictionDots(points: List<TrackedPrice>) {
    if (points.isEmpty()) return
    val all = points.flatMap { listOfNotNull(it.expected, it.actual) }
    val low = all.min(); val spread = (all.max() - low).takeIf { it.signum() > 0 } ?: BigDecimal.ONE
    val predictedColor = MaterialTheme.colorScheme.primary
    val actualColor = MaterialTheme.colorScheme.tertiary
    Canvas(Modifier.fillMaxWidth().height(140.dp).semantics { contentDescription = "Points prévus vs points réalisés" }) {
        points.forEachIndexed { i, p ->
            val x = size.width * (i + 1) / (points.size + 1f)
            fun y(v: BigDecimal) = size.height * (.9f - .8f *
                (v - low).divide(spread, 10, java.math.RoundingMode.HALF_UP).toFloat())
            drawCircle(predictedColor, 4.dp.toPx(), Offset(x - 5.dp.toPx(), y(p.expected)))
            p.actual?.let { drawCircle(actualColor, 4.dp.toPx(), Offset(x + 5.dp.toPx(), y(it))) }
        }
    }
}

/** Paired points for held-out directional accuracy and the naive model baseline. */
@Composable
private fun ModelAccuracyDots(points: List<ModelRunChart>) {
    if (points.isEmpty()) return
    val trainedColor = MaterialTheme.colorScheme.primary
    val baselineColor = MaterialTheme.colorScheme.tertiary
    Canvas(Modifier.fillMaxWidth().height(140.dp).semantics { contentDescription = "Précision du modèle et baseline" }) {
        points.forEachIndexed { i, p ->
            val x = size.width * (i + 1) / (points.size + 1f)
            val measured = p.accuracy.toFloat().coerceIn(0f, 100f) / 100f
            val reference = p.baseline.toFloat().coerceIn(0f, 100f) / 100f
            drawCircle(trainedColor, 4.dp.toPx(), Offset(x - 5.dp.toPx(), size.height * (1 - measured)))
            drawCircle(baselineColor, 4.dp.toPx(), Offset(x + 5.dp.toPx(), size.height * (1 - reference)))
        }
    }
}
