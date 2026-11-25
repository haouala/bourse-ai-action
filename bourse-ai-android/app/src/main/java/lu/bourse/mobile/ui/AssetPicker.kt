package lu.bourse.mobile.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.gson.JsonObject
import kotlinx.coroutines.delay
import lu.bourse.mobile.AppStore
import lu.bourse.mobile.api
import lu.bourse.mobile.data.*

@Composable
internal fun AssetPicker(store: AppStore, close: () -> Unit, manual: () -> Unit, add: (JsonObject) -> Unit) {
    var query by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf<JsonObject?>(null) }
    val config by store.config.collectAsStateWithLifecycle()
    val resources by store.resources.collectAsStateWithLifecycle()
    val saving = resources["watch-write"]?.loading == true
    val searchKey = "watch-suggestions"
    LaunchedEffect(config, query.trim()) {
        if (!config.demo && query.isNotBlank()) delay(250)
        store.load(searchKey, api("watchlist", "suggestions"), mapOf("q" to query.trim()))
    }

    Panel("Ajouter au suivi", accent = MaterialTheme.colorScheme.primary) {
        OutlinedTextField(query, {
            val next = it.take(80)
            if (next.trim() != query.trim()) store.clear(searchKey)
            query = next
            selected = null
        }, singleLine = true, label = { Text("Symbole ou nom") },
            modifier = Modifier.fillMaxWidth().testTag("asset-symbol-search"))
        val choice = selected
        if (choice != null) {
            KeyValue(choice.required("symbol"), choice.required("name"))
            Text("${choice.required("currency")} · ${choice.required("region")} · ${choice.required("sessionOpen")}–${choice.required("sessionClose")}")
            Button(enabled = !saving, onClick = {
                add(obj("symbol" to choice.required("symbol"), "name" to choice.required("name"),
                    "assetType" to choice.required("assetType"), "currency" to choice.required("currency"),
                    "providerSymbol" to choice.required("providerSymbol"), "region" to choice.required("region"),
                    "marketZone" to choice.required("marketZone"), "sessionOpen" to choice.required("sessionOpen"),
                    "sessionClose" to choice.required("sessionClose"), "enabled" to true))
            }) { Text("Ajouter") }
        } else DataBlock(store, searchKey, "Recherche…", { it.rows() }) { results ->
            if (results.isEmpty()) Text("Aucun symbole trouvé.")
            results.take(6).forEach { row ->
                TextButton(onClick = { selected = row }) {
                    Text("${row.required("symbol")} · ${row.required("name")} · ${row.required("currency")}")
                }
            }
            if (results.size > 6) Text("Affiner la recherche pour afficher d’autres symboles.")
        }
        resources["watch-write"]?.error?.let { Note(it, true) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = manual, enabled = !saving) { Text("Saisie manuelle") }
            TextButton(onClick = close, enabled = !saving) { Text("Annuler") }
        }
    }
}
