package lu.bourse.mobile.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
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
    val preferences = LocalContext.current.getSharedPreferences("bourse-design", 0)
    var favorites by remember(preferences) { mutableStateOf(preferences.getStringSet("favorites", emptySet()).orEmpty().toSet()) }
    var onlyFavorites by rememberSaveable { mutableStateOf(false) }
    var region by rememberSaveable { mutableStateOf("ALL") }
    var adding by rememberSaveable { mutableStateOf(false) }
    var manual by rememberSaveable { mutableStateOf(false) }
    fun refresh() = store.load("watchlist", api("watchlist"))
    LaunchedEffect(config) { refresh() }
    Page("Marchés") {
        OutlinedTextField(search, { search = it }, label = { Text("Rechercher un nom ou un symbole") }, modifier = Modifier.fillMaxWidth().testTag("market-search"), singleLine = true)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("ALL" to "Tous", "STOCK" to "Actions", "CRYPTO" to "Cryptos").forEach { (key, label) -> FilterChip(selected = type == key, onClick = { type = key }, label = { Text(label) }) }
            FilterChip(onlyFavorites, { onlyFavorites = !onlyFavorites }, label = { Text("Favoris") })
        }
        Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
            Button(onClick = { store.clear("watch-write"); manual = false; adding = true }) { Text("Ajouter un actif") }
            OutlinedButton(onClick = { refresh() }) { Text("Actualiser") }
        }
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("ALL" to "Toutes régions", "US" to "États-Unis", "EU" to "Europe", "GLOBAL" to "Global").forEach { (key, label) ->
                FilterChip(region == key, { region = key }, label = { Text(label) })
            }
        }
        states["watch-write"]?.error?.let { Note(it, true) }
        DataBlock(store, "watchlist", decode = ::assets) { all ->
            Text("${all.count { it.type == "STOCK" && it.enabled }}/100 actions · ${all.count { it.type == "CRYPTO" && it.enabled }}/30 cryptos actives", style = MaterialTheme.typography.labelLarge)
            val visible = remember(all, search, type, onlyFavorites, favorites, region) {
                filterAssets(all, search, type).filter { (!onlyFavorites || it.symbol in favorites) && (region == "ALL" || it.region == region) }
            }
            Text("Liste des actifs (${visible.size})", style = MaterialTheme.typography.titleLarge)
            if (visible.isEmpty()) Note(if (all.isEmpty()) "Aucun actif. Ajouter un premier symbole." else "Aucun actif ne correspond à la recherche.")
            visible.forEach { asset -> Panel("${asset.symbol} · ${asset.name}", accent = MaterialTheme.colorScheme.primary.takeIf { asset.enabled }, trailing = {
                IconButton(onClick = {
                    favorites = if (asset.symbol in favorites) favorites - asset.symbol else favorites + asset.symbol
                    preferences.edit().putStringSet("favorites", favorites).apply()
                }) {
                    Icon(if (asset.symbol in favorites) Icons.Filled.Star else Icons.Outlined.StarOutline,
                        if (asset.symbol in favorites) "Retirer ${asset.symbol} des favoris" else "Ajouter ${asset.symbol} aux favoris")
                }
            }) {
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
    if (adding && !manual) AssetPicker(store, { adding = false }, { manual = true }) { chosen ->
        store.mutate("watch-write", api("watchlist"), chosen, "PUT") { adding = false; refresh() }
    }
    if (adding && manual) EditorDialog("Ajouter au suivi", listOf(
        InputField("symbol", "Symbole, ex. NVDA"), InputField("name", "Nom de l’actif"), InputField("type", "Type", "STOCK", options = listOf("STOCK", "CRYPTO")),
        InputField("currency", "Devise", "USD"), InputField("provider", "Symbole du fournisseur, ex. BTC/USD"),
        InputField("region", "Région", "US", options = listOf("US", "EU", "GLOBAL")), InputField("zone", "Fuseau du marché", "America/New_York"), InputField("opens", "Ouverture locale (HH:mm)", "09:30"), InputField("closes", "Clôture locale (HH:mm)", "16:00")
    ), store, "watch-write", { adding = false }) { values ->
        val body = watchPayload(values)
        store.mutate("watch-write", api("watchlist"), body, "PUT") { adding = false; refresh() }
    }
}
