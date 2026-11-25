package lu.bourse.mobile.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import lu.bourse.mobile.AppStore
import lu.bourse.mobile.api
import lu.bourse.mobile.data.*
import lu.bourse.mobile.ui.theme.ResearchAccent

@Composable
fun HomeScreen(store: AppStore, markets: () -> Unit, portfolio: () -> Unit, intelligence: () -> Unit) {
    val config by store.config.collectAsStateWithLifecycle()
    val id = config.portfolioId
    LaunchedEffect(config) {
        store.load("watchlist", api("watchlist"))
        id?.let { store.load("home-risk:$it", api("portfolios", it, "evaluate"), method = "POST") }
    }
    Page("Tableau de bord") {
        Panel("Mon portefeuille", accent = MaterialTheme.colorScheme.primary) {
            if (id == null) Text("Aucun portefeuille sélectionné.")
            else DataBlock(store, "home-risk:$id", decode = { it.objectValue() }) { risk ->
                Text(amount(risk.decimal("knownEquity"), risk.text("baseCurrency")), style = MaterialTheme.typography.headlineLarge)
                KeyValue("Valorisation", if (config.demo) "Simulation" else statusLabel(risk.text("status")))
                KeyValue("Budget de perte restant", amount(risk.decimal("remainingLossBudget"), risk.text("baseCurrency")))
            }
            OutlinedButton(onClick = portfolio) { Text("Ouvrir le portefeuille") }
        }
        Panel("Marchés suivis") {
            DataBlock(store, "watchlist", decode = ::assets) { all ->
                val active = all.filter { it.enabled }
                KeyValue("Actifs suivis", active.size.toString())
                if (active.isNotEmpty()) Text(active.take(5).joinToString(" · ") { it.symbol })
            }
            OutlinedButton(onClick = markets) { Text("Voir les marchés") }
        }
        Panel("Intelligence", accent = ResearchAccent) {
            Text("Consulter les scénarios, les prévisions et les résultats ML disponibles.")
            Button(intelligence, Modifier.fillMaxWidth()) { Text("Choisir un actif à analyser") }
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
