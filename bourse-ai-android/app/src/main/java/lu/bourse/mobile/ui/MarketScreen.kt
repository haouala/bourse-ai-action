package lu.bourse.mobile.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import lu.bourse.mobile.*
import lu.bourse.mobile.data.*

@Composable
fun MarketScreen(store: AppStore, open: (String) -> Unit) {
    val config by store.config.collectAsStateWithLifecycle()
    val states by store.resources.collectAsStateWithLifecycle()
    var search by rememberSaveable { mutableStateOf("") }
    var type by rememberSaveable { mutableStateOf("ALL") }
    var adding by rememberSaveable { mutableStateOf(false) }
    fun refresh() = store.load("watchlist", api("watchlist"))
    LaunchedEffect(config) { refresh() }
    Page("Liste de suivi", "Choisir les actifs à analyser ; les cours et les sources sont visibles dans leur détail.") {
        OutlinedTextField(search, { search = it }, label = { Text("Rechercher un nom ou un symbole") }, modifier = Modifier.fillMaxWidth().testTag("market-search"), singleLine = true)
        Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
            listOf("ALL" to "Tous", "STOCK" to "Actions", "CRYPTO" to "Cryptos").forEach { (key, label) -> FilterChip(selected = type == key, onClick = { type = key }, label = { Text(label) }) }
        }
        Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
            Button(onClick = { store.clear("watch-write"); adding = true }) { Text("Ajouter un actif") }
            OutlinedButton(onClick = { refresh() }) { Text("Actualiser") }
        }
        states["watch-write"]?.error?.let { Note(it, true) }
        DataBlock(store, "watchlist", decode = ::assets) { all ->
            Text("${all.count { it.type == "STOCK" && it.enabled }}/100 actions · ${all.count { it.type == "CRYPTO" && it.enabled }}/30 cryptos actives", style = MaterialTheme.typography.labelLarge)
            val visible = filterAssets(all, search, type)
            if (visible.isEmpty()) Note(if (all.isEmpty()) "Aucun actif. Ajouter un premier symbole." else "Aucun actif ne correspond à la recherche.")
            visible.forEach { asset -> Panel("${asset.symbol} · ${asset.name}") {
                Text("${if (asset.type == "CRYPTO") "Crypto" else "Action"} · ${asset.currency} · ${asset.region}")
                Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                    TextButton(onClick = { open(asset.symbol) }, modifier = Modifier.testTag("asset-${asset.symbol}")) { Text("Voir l’analyse") }
                    Row { Text(if (asset.enabled) "Suivi actif" else "En pause"); Switch(checked = asset.enabled, enabled = states["watch-write"]?.loading != true, onCheckedChange = { active ->
                        store.mutate("watch-write", api("watchlist"), asset.payload(active), "PUT") { refresh() }
                    }) }
                }
            } }
        }
    }
    if (adding) EditorDialog("Ajouter au suivi", listOf(
        InputField("symbol", "Symbole, ex. NVDA"), InputField("name", "Nom de l’actif"), InputField("type", "Type", "STOCK", options = listOf("STOCK", "CRYPTO")),
        InputField("currency", "Devise", "USD"), InputField("provider", "Symbole du fournisseur, ex. BTC/USD"),
        InputField("region", "Région", "US", options = listOf("US", "EU", "GLOBAL")), InputField("zone", "Fuseau du marché", "America/New_York"), InputField("opens", "Ouverture locale (HH:mm)", "09:30"), InputField("closes", "Clôture locale (HH:mm)", "16:00")
    ), store, "watch-write", { adding = false }) { values ->
        val body = watchPayload(values)
        store.mutate("watch-write", api("watchlist"), body, "PUT") { adding = false; refresh() }
    }
}
