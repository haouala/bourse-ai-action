package lu.bourse.mobile.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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

    AlertDialog(onDismissRequest = { if (!saving) close() }, title = { Text("Ajouter au suivi") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(query, {
                val next = it.take(80)
                if (next.trim() != query.trim()) store.clear(searchKey)
                query = next
                selected = null
            }, singleLine = true,
                label = { Text("Symbole ou nom") }, modifier = Modifier.fillMaxWidth().testTag("asset-symbol-search"))
            val choice = selected
            if (choice != null) {
                KeyValue(choice.required("symbol"), choice.required("name"))
                Text("${choice.required("currency")} · ${choice.required("region")} · ${choice.required("sessionOpen")}–${choice.required("sessionClose")}")
            } else DataBlock(store, searchKey, "Recherche…", { it.rows() }) { results ->
                if (results.isEmpty()) Text("Aucun symbole trouvé.")
                Column(Modifier.heightIn(max = 200.dp).verticalScroll(rememberScrollState())) {
                    results.forEach { row ->
                        TextButton(onClick = { selected = row }, modifier = Modifier.fillMaxWidth()) {
                            Text("${row.required("symbol")} · ${row.required("name")} · ${row.required("currency")}")
                        }
                    }
                }
            }
            resources["watch-write"]?.error?.let { Note(it, true) }
        }
    }, confirmButton = {
        Button(enabled = selected != null && !saving, onClick = {
            val item = selected ?: return@Button
            add(obj("symbol" to item.required("symbol"), "name" to item.required("name"),
                "assetType" to item.required("assetType"), "currency" to item.required("currency"),
                "providerSymbol" to item.required("providerSymbol"), "region" to item.required("region"),
                "marketZone" to item.required("marketZone"), "sessionOpen" to item.required("sessionOpen"),
                "sessionClose" to item.required("sessionClose"), "enabled" to true))
        }) { Text("Ajouter") }
    }, dismissButton = {
        TextButton(onClick = manual, enabled = !saving) { Text("Saisie manuelle") }
    })
}
