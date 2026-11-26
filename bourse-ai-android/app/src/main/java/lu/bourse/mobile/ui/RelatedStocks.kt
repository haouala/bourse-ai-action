package lu.bourse.mobile.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.google.gson.JsonObject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import lu.bourse.mobile.AppStore
import lu.bourse.mobile.BuildConfig
import lu.bourse.mobile.api
import lu.bourse.mobile.data.*

@Composable
fun RelatedStocks(store: AppStore, config: ConnectionConfig, symbol: String) {
    if (config.demo) return
    var opened by rememberSaveable(symbol) { mutableStateOf(false) }
    val base = "related:$symbol"
    val route = api("relationships", symbol)
    fun refresh() {
        store.load("$base:edges", route)
        store.load("$base:suggestions", route + "suggestions")
        store.load("$base:watchlist", api("watchlist"))
    }
    OutlinedButton(onClick = { opened = !opened }, modifier = Modifier.fillMaxWidth()) {
        Text(if (opened) "Masquer les actions liées" else "Actions liées")
    }
    if (!opened) return

    LaunchedEffect(config, symbol) { refresh() }
    var selected by remember(config, symbol) { mutableStateOf<JsonObject?>(null) }
    var token by remember(config, symbol) { mutableStateOf("") }
    var working by remember(config, symbol) { mutableStateOf(false) }
    var error by remember(config, symbol) { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    Panel("Actions liées", accent = MaterialTheme.colorScheme.tertiary) {
        DataBlock(store, "$base:edges", decode = { it.rows() }) { edges ->
            if (edges.isEmpty()) Text("Aucun lien confirmé.")
            edges.forEach { edge ->
                Text("${display(edge.text("fromSymbol"))} ↔ ${display(edge.text("toSymbol"))}")
                Text(display(edge.text("evidenceSummary")), style = MaterialTheme.typography.bodySmall)
                Evidence(edge.text("evidenceUrl"))
            }
            if (edges.isNotEmpty()) {
                OutlinedButton(onClick = { store.load("$base:comparison", api("contagion", symbol)) }) {
                    Text("Comparer les rendements journaliers")
                }
            }
        }
        DataBlock(store, "$base:suggestions", decode = { it.rows() }) { candidates ->
            if (candidates.isEmpty()) Text("Aucune suggestion sourcée disponible.")
            candidates.forEach { candidate ->
                val evidence = candidate.child("evidence") ?: return@forEach
                val peer = if (evidence.text("fromSymbol") == symbol) evidence.text("toSymbol") else evidence.text("fromSymbol")
                Text("${peer.orEmpty()} · Concurrent proposé", style = MaterialTheme.typography.titleSmall)
                Text(display(evidence.text("evidenceSummary")))
                Evidence(evidence.text("evidenceUrl"))
                val known = candidate.flag("peerExists") == true
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(enabled = known && !working, onClick = { token = ""; error = null; selected = evidence }) {
                        Text("Confirmer le lien")
                    }
                }
                if (peer != null) DataBlock(store, "$base:watchlist", decode = ::assets) { watched ->
                    val entry = watched.firstOrNull { it.symbol == peer }
                    if (entry?.enabled != true) TextButton(enabled = !working, onClick = {
                        error = null
                        val payload = entry?.payload(true) ?: watchPayload(mapOf(
                            "symbol" to peer, "name" to peer, "type" to "STOCK", "currency" to "USD",
                            "provider" to peer, "region" to "US", "zone" to "America/New_York",
                            "opens" to "09:30", "closes" to "16:00"))
                        working = true
                        scope.launch {
                            try {
                                HttpTransport(config, BuildConfig.DEBUG).request("PUT",api("watchlist"), body = payload)
                                refresh()
                            } catch (cancel: CancellationException) {
                                throw cancel
                            } catch (failure: Exception) {
                                error = failure.message ?: "Ajout indisponible."
                            } finally {
                                working = false
                            }
                        }
                    }) { Text("Ajouter $peer au suivi") }
                }
            }
        }
        error?.let { Note(it, true) }
        val resources by store.resources.collectAsState()
        if (resources.containsKey("$base:comparison")) {
            DataBlock(store, "$base:comparison", decode = { it.objectValue() }) { report ->
                Text("Comparaison historique · cours journaliers", style = MaterialTheme.typography.titleSmall)
                val comparisons = report.list("comparisons")
                if (comparisons.isEmpty()) Text("Aucune comparaison disponible.")
                comparisons.forEach { pair ->
                    Text("${display(pair.text("target"))} · ${pair.id("commonReturnDates") ?: 0} dates communes")
                    val zeroLag = pair.list("leadingCorrelations").firstOrNull { it.id("lagSessions") == 0L }
                    val coefficient = zeroLag?.decimal("coefficient")
                    Text(if (coefficient != null) "Corrélation historique ${amount(coefficient)}" else "Données insuffisantes ou non qualifiées.")
                }
            }
        }
    }

    selected?.let { evidence ->
        AlertDialog(
            onDismissRequest = { if (!working) { selected = null; token = "" } },
            title = { Text("Confirmer la relation") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(display(evidence.text("evidenceSummary")))
                    OutlinedTextField(token, { token = it.take(512) }, enabled = !working, singleLine = true,
                        label = { Text("Jeton administrateur") }, visualTransformation = PasswordVisualTransformation())
                    error?.let { Note(it, true) }
                }
            },
            confirmButton = {
                Button(enabled = !working && token.trim().length >= 32, onClick = {
                    val id = evidence.text("id") ?: return@Button
                    working = true
                    error = null
                    scope.launch {
                        try {
                            HttpTransport(config,BuildConfig.DEBUG,token.trim())
                                .request("POST",route + listOf("suggestions", id, "confirm"))
                            token = ""
                            selected = null
                            refresh()
                        } catch (cancel: CancellationException) {
                            throw cancel
                        } catch (failure: Exception) {
                            error = failure.message ?: "Confirmation indisponible."
                        } finally {
                            working = false
                        }
                    }
                }) { Text("Enregistrer le lien") }
            },
            dismissButton = { TextButton(enabled = !working, onClick = { selected = null; token = ""; error = null }) { Text("Annuler") } },
        )
    }
}
