package lu.bourse.mobile.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.Alignment
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import lu.bourse.mobile.AppStore
import lu.bourse.mobile.api
import lu.bourse.mobile.data.*
import lu.bourse.mobile.ui.theme.ResearchAccent
import lu.bourse.mobile.ui.theme.Rising

@Composable
fun HomeScreen(store: AppStore, refresh: Int, markets: () -> Unit, portfolio: () -> Unit, intelligence: () -> Unit, guide: () -> Unit, openAsset: (String) -> Unit) {
    val config by store.config.collectAsStateWithLifecycle()
    val id = config.portfolioId
    val resources by store.resources.collectAsStateWithLifecycle()
    val tracked = remember(resources["watchlist"]?.value) {
        runCatching { resources["watchlist"]?.value?.let(::assets).orEmpty() }.getOrDefault(emptyList())
            .filter { it.enabled }.take(3)
    }
    LaunchedEffect(tracked.map { it.symbol }, config, refresh) {
        tracked.forEach { asset ->
            store.load("home-chart:${asset.symbol}", api("charts", asset.symbol), mapOf("timeframe" to "1d", "limit" to "20"))
            store.load("home-score:${asset.symbol}", api("scoring", asset.symbol), mapOf("horizonDays" to "30"))
        }
    }
    LaunchedEffect(config, refresh) {
        store.load("watchlist", api("watchlist"))
        id?.let { store.load("home-risk:$it", api("portfolios", it, "evaluate"), method = "POST") }
    }
    Page("Tableau de bord") {
        Text("Bonjour !", style = MaterialTheme.typography.headlineMedium)
        Panel("Mon portefeuille", accent = Rising) {
            if (id == null) Text("Aucun portefeuille sélectionné.")
            else DataBlock(store, "home-risk:$id", decode = { it.objectValue() }) { risk ->
                Text(amount(risk.decimal("knownEquity"), risk.text("baseCurrency")), style = MaterialTheme.typography.headlineLarge)
                KeyValue("Valorisation", if (config.demo) "Simulation" else statusLabel(risk.text("status")))
                KeyValue("Budget de perte restant", amount(risk.decimal("remainingLossBudget"), risk.text("baseCurrency")))
            }
            OutlinedButton(onClick = portfolio) { Text("Ouvrir le portefeuille") }
        }
        Panel("Marchés suivis", accent = MaterialTheme.colorScheme.primary) {
            DataBlock(store, "watchlist", decode = ::assets) { all ->
                val active = all.filter { it.enabled }
                KeyValue("Actifs suivis", active.size.toString())
                if (active.isNotEmpty()) Text(active.take(5).joinToString(" · ") { it.symbol })
            }
            OutlinedButton(onClick = markets) { Text("Voir les marchés") }
        }
        Panel("Analyse IA", accent = ResearchAccent) {
            Text("Consulter les scénarios, les prévisions et les résultats ML disponibles.")
            Button(intelligence, Modifier.fillMaxWidth()) { Text("Choisir un actif à analyser") }
        }
        if (tracked.isNotEmpty()) {
            Text("Actifs à suivre", style = MaterialTheme.typography.titleLarge)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                tracked.forEach { asset ->
                    val chart = runCatching { resources["home-chart:${asset.symbol}"]?.value?.let(::chartData) }.getOrNull()
                    val score = resources["home-score:${asset.symbol}"]?.value?.takeIf { it.isJsonObject }?.asJsonObject
                    OutlinedCard(onClick = { openAsset(asset.symbol) }, modifier = Modifier.width(158.dp)) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(asset.symbol, style = MaterialTheme.typography.titleMedium)
                            Text(asset.name, maxLines = 1, style = MaterialTheme.typography.labelMedium)
                            Text(priceText(chart?.candles?.lastOrNull()?.close, asset.currency), color = Rising)
                            ScoreRing("Opportunité", score?.decimal("opportunityScore")?.toFloat(), Rising)
                        }
                    }
                }
            }
        }
        Panel("Guide de démarrage", accent = ResearchAccent) {
            OutlinedButton(onClick = guide) { Text("Lire le guide") }
            FinancialGlossaryButton()
        }
    }
}

@Composable
fun IntelligencePicker(store: AppStore, open: (String) -> Unit) {
    val config by store.config.collectAsStateWithLifecycle()
    LaunchedEffect(config) { store.load("watchlist", api("watchlist")) }
    Page("Intelligence") {
        DataBlock(store, "watchlist", decode = ::assets) { all ->
            if (all.isEmpty()) Note("Aucun actif disponible dans la liste de suivi.")
            all.forEach { asset ->
                OutlinedButton({ open(asset.symbol) }, Modifier.fillMaxWidth()) { Text("${asset.symbol} · ${asset.name}") }
            }
        }
    }
}

@Composable
internal fun ScoreRing(label: String, score: Float?, tint: androidx.compose.ui.graphics.Color) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(46.dp), contentAlignment = Alignment.Center) {
            if (score != null) CircularProgressIndicator(progress = { (score / 100f).coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxSize(), color = tint, trackColor = tint.copy(alpha = .18f), strokeWidth = 4.dp)
            Text(score?.toInt()?.toString() ?: "—", style = MaterialTheme.typography.labelLarge)
        }
        Text(label, style = MaterialTheme.typography.labelSmall, color = tint)
    }
}
