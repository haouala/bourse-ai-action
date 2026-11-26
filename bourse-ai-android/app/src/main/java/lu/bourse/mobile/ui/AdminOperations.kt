package lu.bourse.mobile.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import lu.bourse.mobile.BuildConfig
import lu.bourse.mobile.api
import lu.bourse.mobile.data.*

private data class AdminField(val key: String, val title: String, val initial: String = "")
private data class AdminAction(val id: String, val label: String, val writes: Boolean, val fields: List<AdminField> = emptyList())
private val symbol = AdminField("symbol", "Symbole")
private val analysis = AdminField("number", "Identifiant de l’analyse")
private val review = listOf(analysis, AdminField("reason", "Motif"), AdminField("reviewToken", "Jeton de validation IA"))
private val adminGroups = linkedMapOf(
    "Cours et fondamentaux" to listOf(
        AdminAction("DAILY_IMPORT", "Importer les clôtures journalières", true, listOf(symbol, AdminField("number", "Années (1 à 10)", "5"))),
        AdminAction("QUALIFY_DAILY", "Qualifier une série journalière", true, listOf(symbol,
            AdminField("provider", "Fournisseur"), AdminField("adjustment", "SPLIT_ADJUSTED ou TOTAL_RETURN"),
            AdminField("evidenceUrl", "URL HTTPS de la preuve"))),
        AdminAction("DAILY_REPAIR", "Réparer les clôtures manquantes", true, listOf(
            AdminField("symbol", "Symbole facultatif"), AdminField("number", "Nombre maximum (1 à 20)", "5"))),
        AdminAction("SEC_SYNC", "Importer les comptes SEC", true, listOf(symbol, AdminField("number", "CIK SEC"))),
        AdminAction("SEC_FACTS", "Consulter les comptes SEC", false, listOf(symbol)),
        AdminAction("FMP_SYNC", "Importer le profil FMP", true, listOf(symbol)),
        AdminAction("FMP_PROFILE", "Consulter le profil FMP", false, listOf(symbol)),
    ),
    "Analyses IA" to listOf(
        AdminAction("AI_ANALYSES", "Lire les analyses d’un article", false, listOf(AdminField("number", "Identifiant de l’article"))),
        AdminAction("AI_REVIEWS", "Lire l’historique de validation", false, listOf(analysis)),
        AdminAction("AI_APPROVE", "Approuver une analyse", true, review),
        AdminAction("AI_REJECT", "Rejeter une analyse", true, review),
    ),
    "Batchs et diagnostic" to listOf(
        AdminAction("QUOTAS", "Voir les quotas fournisseurs", false),
        AdminAction("PRICE_QUALITY", "Contrôler les cours journaliers", false, listOf(symbol)),
        AdminAction("MODEL_MONITORING", "Suivre les résultats du modèle", false, listOf(symbol)),
        AdminAction("MARKET_TASKS", "Voir les tâches de marché", false),
        AdminAction("MARKET_ENQUEUE", "Préparer les tâches de marché", true),
        AdminAction("MARKET_RUN_NEXT", "Traiter la prochaine tâche de marché", true),
        AdminAction("ORCHESTRATOR_STATUS", "Voir l’état de l’orchestrateur", false),
        AdminAction("ORCHESTRATOR_RUNS", "Voir l’historique de l’orchestrateur", false),
        AdminAction("ORCHESTRATOR_ENQUEUE", "Préparer les évaluations", true),
        AdminAction("ORCHESTRATOR_RUN", "Exécuter les évaluations", true, listOf(AdminField("number", "Actifs (1 à 20)", "5"))),
    ),
)

@Composable
internal fun AdminOperations(config: ConnectionConfig, token: String) {
    var opened by remember { mutableStateOf(false) }
    var group by remember { mutableStateOf<String?>(null) }
    var dashboard by remember { mutableStateOf<JsonObject?>(null) }
    var selected by remember { mutableStateOf<AdminAction?>(null) }
    var result by remember { mutableStateOf<JsonElement?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun request(method: String, path: List<String>, body: JsonObject? = null, receive: (JsonElement) -> Unit) {
        loading = true
        error = null
        scope.launch {
            try {
                receive(HttpTransport(config, BuildConfig.DEBUG, token.trim()).request(method, path, body = body))
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (failure: Exception) {
                error = failure.message ?: "Le serveur n’a pas renvoyé de résultat."
            } finally {
                loading = false
            }
        }
    }
    fun refresh() = request("GET", api("admin", "dashboard")) { dashboard = it.objectValue() }

    Panel("Pilotage et audit", accent = MaterialTheme.colorScheme.tertiary) {
        OutlinedButton(enabled = !loading && token.trim().length >= 32, onClick = {
            opened = !opened
            if (opened) refresh()
        }) { Text(if (opened) "Masquer le pilotage" else "Ouvrir le tableau de bord") }
        if (opened) {
            OutlinedButton(enabled = !loading, onClick = { refresh() }) { Text("Actualiser les statistiques") }
            if (loading && selected == null) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (selected == null) error?.let { Note(it, true) }
            dashboard?.let { AdminAudit(it) }
            adminGroups.forEach { (name, actions) ->
                TextButton(onClick = { group = if (group == name) null else name }) { Text(name) }
                if (group == name) actions.forEach { action ->
                    OutlinedButton(
                        enabled = !loading && token.trim().length >= 32,
                        modifier = Modifier.fillMaxWidth(),
                        onClick = { error = null; result = null; selected = action },
                    ) { Text(action.label) }
                }
            }
        }
    }

    selected?.let { action ->
        key(action.id) {
            val values = remember { mutableStateMapOf<String, String>().apply {
                action.fields.forEach { put(it.key, it.initial) }
            } }
            fun close() {
                selected = null
                result = null
                error = null
            }
            AlertDialog(
                onDismissRequest = { if (!loading) close() },
                title = { Text(action.label) },
                properties = DialogProperties(usePlatformDefaultWidth = false),
                modifier = Modifier.widthIn(max = 600.dp).fillMaxWidth(.94f),
                text = {
                    Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        action.fields.forEach { field ->
                            val secret = field.key == "reviewToken"
                            OutlinedTextField(values[field.key].orEmpty(), { values[field.key] = it.take(1500) },
                                label = { Text(field.title) }, enabled = !loading, singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                                keyboardOptions = KeyboardOptions(keyboardType = when {
                                    field.key == "number" -> KeyboardType.Number
                                    secret -> KeyboardType.Password
                                    else -> KeyboardType.Text
                                }),
                                visualTransformation = if (secret) PasswordVisualTransformation()
                                    else androidx.compose.ui.text.input.VisualTransformation.None)
                        }
                        if (action.writes && result == null) Text("Cette action modifie des données ou déclenche un traitement.")
                        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                        error?.let { Note(it, true) }
                        result?.let {
                            Text("Résultat reçu", style = MaterialTheme.typography.titleSmall)
                            JsonDetails(it, "Voir la réponse du serveur")
                        }
                    }
                },
                confirmButton = {
                    Button(enabled = !loading && result == null, onClick = {
                        val payload = JsonObject()
                        values.forEach { (name, raw) ->
                            val value = raw.trim()
                            if (name == "number") value.toIntOrNull()?.let { payload.addProperty(name, it) }
                            else if (value.isNotEmpty()) payload.addProperty(name, value)
                        }
                        request("POST", api("admin", "operations", action.id), payload) {
                            values.remove("reviewToken")
                            result = it
                        }
                    }) { Text(if (action.writes) "Confirmer l’exécution" else "Consulter") }
                },
                dismissButton = { TextButton(enabled = !loading, onClick = { close() }) { Text("Fermer") } },
            )
        }
    }
}

@Composable
private fun AdminAudit(dashboard: JsonObject) {
    KeyValue("Actifs suivis", dashboard.id("activeAssets")?.toString() ?: "—")
    KeyValue("Analyses IA enregistrées · 24 h", dashboard.id("analyses24h")?.toString() ?: "—")
    val analyses = dashboard.list("recentAnalyses")
    val jobs = dashboard.list("recentAiJobs")
    val imports = dashboard.list("imports24h")
    val operations = dashboard.list("recentOperations")
    if (analyses.isEmpty()) Text("Aucune analyse IA enregistrée.")
    else {
        val last = analyses.first()
        KeyValue("Dernière analyse IA", dateLabel(last.text("finishedAt")))
        Text("Article ${last.id("itemId")} · analyse ${last.id("analysisId")} · ${display(last.text("reviewStatus"))}")
    }
    var expanded by remember { mutableStateOf(false) }
    TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "Masquer le journal" else "Voir le journal d’audit") }
    if (expanded) {
        Text("Imports des dernières 24 h", style = MaterialTheme.typography.titleSmall)
        if (imports.isEmpty()) Text("Aucun import.")
        imports.forEach { KeyValue("${display(it.text("provider"))} · ${display(it.text("status"))}", it.id("count")?.toString() ?: "—") }
        Text("Analyses IA récentes", style = MaterialTheme.typography.titleSmall)
        analyses.forEach {
            KeyValue("Article ${it.id("itemId")} · analyse ${it.id("analysisId")}", dateLabel(it.text("finishedAt")))
        }
        Text("Tâches IA récentes", style = MaterialTheme.typography.titleSmall)
        if (jobs.isEmpty()) Text("Aucune tâche IA.")
        jobs.forEach {
            KeyValue("Tâche ${it.id("id")} · ${display(it.text("status"))}", dateLabel(it.text("updatedAt")))
            it.text("errorCode")?.let { code -> Text(code, style = MaterialTheme.typography.bodySmall) }
        }
        Text("Dernières actions administratives", style = MaterialTheme.typography.titleSmall)
        if (operations.isEmpty()) Text("Aucune action enregistrée.")
        operations.forEach {
            KeyValue("${display(it.text("operation"))} · ${display(it.text("status"))}", dateLabel(it.text("startedAt")))
        }
    }
}
