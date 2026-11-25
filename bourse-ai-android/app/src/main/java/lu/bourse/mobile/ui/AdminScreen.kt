package lu.bourse.mobile.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.google.gson.JsonObject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import lu.bourse.mobile.BuildConfig
import lu.bourse.mobile.api
import lu.bourse.mobile.data.*
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

@Composable
fun AdminScreen(config: ConnectionConfig) {
    var token by remember(config.server) { mutableStateOf("") }
    var report by remember(config.server) { mutableStateOf<JsonObject?>(null) }
    var loading by remember(config.server) { mutableStateOf(false) }
    var error by remember(config.server) { mutableStateOf<String?>(null) }
    var server by remember(config.server) { mutableStateOf("À vérifier") }
    val scope = rememberCoroutineScope()

    Page("Administration") {
        Panel("Accès") {
            OutlinedTextField(
                token, { token = it.take(512) }, singleLine = true, enabled = !loading,
                label = { Text("Jeton administrateur") }, visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth().testTag("admin-token")
            )
            Button(enabled = !loading && token.trim().length >= 32 && !config.demo,
                modifier = Modifier.testTag("admin-refresh"), onClick = {
                    loading = true
                    report = null
                    error = null
                    server = "Vérification…"
                    scope.launch {
                        try {
                            report = HttpTransport(config, BuildConfig.DEBUG, token.trim())
                                .request("GET", api("admin", "status")).objectValue()
                            server = "Joignable"
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (failure: ApiFailure) {
                            server = if (failure.status == null) "Injoignable" else "Joignable"
                            error = when (failure.status) {
                                401, 403 -> "Jeton administrateur refusé."
                                503 -> "Administration indisponible. Vérifier la configuration du backend."
                                else -> failure.message
                            }
                            if (failure.status == 401 || failure.status == 403) token = ""
                        } catch (_: Exception) {
                            server = "État indéterminé"
                            error = "Le diagnostic n’a pas pu être lu."
                        } finally {
                            loading = false
                        }
                    }
                }) { Text("Actualiser le diagnostic") }
            if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            error?.let { Note(it, true) }
        }

        Panel("Disponibilité") {
            KeyValue("Application Android", "En cours d’exécution sur cet appareil")
            KeyValue("Serveur depuis cet appareil", server)
            KeyValue("Backend", adminState(report?.text("backend")))
            KeyValue("PostgreSQL", adminState(report?.text("database")))
            report?.text("checkedAt")?.let { KeyValue("Vérifié le", localTime(it)) }
        }

        report?.let { data ->
            RepairPanel(data.child("repair"))
            CoveragePanel(data.child("market"))
            Panel("Batchs et files de travail") {
                val batches = data.list("batches")
                if (batches.isEmpty()) Text("Indisponible")
                batches.forEach { row ->
                    val label = when (row.text("name")) {
                        "MARKET_SYNC" -> "Synchronisation marché"
                        "RESEARCH" -> "Analyse de recherche"
                        "INFORMATION_AI" -> "Analyse des informations"
                        "GDELT" -> "Collecte GDELT"
                        else -> display(row.text("name"))
                    }
                    KeyValue(label, adminState(row.text("state")))
                    Text("Dernier succès : ${localTime(row.text("lastSuccess"))}")
                    Text("Dernière activité : ${localTime(row.text("lastActivity"))}")
                    if (row.text("state") != "UNKNOWN") {
                        Text("${number(row, "due")} en retard · ${number(row, "expired")} exécutions expirées · ${number(row, "failed24h")} échecs / 24 h")
                    }
                    HorizontalDivider()
                }
            }
            Panel("Imports fournisseurs") {
                val providers = data.field("providers")
                when {
                    providers == null -> Text("Indisponible")
                    data.list("providers").isEmpty() -> Text("Aucun import enregistré.")
                    else -> data.list("providers").forEach { row ->
                        KeyValue(display(row.text("code")), "${number(row, "successful24h")} réussis · ${number(row, "failed24h")} en échec / 24 h")
                        Text("Dernier succès : ${localTime(row.text("lastSuccess"))}")
                        Text("Dernière activité : ${localTime(row.text("lastActivity"))}")
                    }
                }
            }
            Panel("Dernier entraînement ML") {
                val ml = data.child("ml")
                if (ml == null) Text("Indisponible") else {
                    KeyValue("Déclenchement", "Manuel")
                    KeyValue("Dernier calcul", localTime(ml.text("lastRun")))
                    ml.text("lastRun")?.let {
                        Text("Horizon ${display(ml.text("horizon"))} · ${number(ml, "predictions")} estimations")
                        Text("Date limite des données : ${localTime(ml.text("dataCutoff"))}")
                    }
                }
            }
        }
    }
}

@Composable
private fun RepairPanel(repair: JsonObject?) {
    Panel("Réparation programmée des cours") {
        if (repair == null) Text("Indisponible") else {
            KeyValue("État", adminState(repair.text("state")))
            KeyValue("Dernier passage attendu", localTime(repair.text("expectedAt")))
            KeyValue("Dernier début observé", localTime(repair.text("startedAt")))
            KeyValue("Dernière fin observée", localTime(repair.text("finishedAt")))
            KeyValue("Prochain passage", localTime(repair.text("nextAt")))
            if (repair.field("missing") != null) {
                Text("${number(repair, "missing")} manquantes au départ · ${number(repair, "attempted")} tentatives · ${number(repair, "inserted")} insérées")
            }
        }
    }
}

@Composable
private fun CoveragePanel(coverage: JsonObject?) {
    var onlyIssues by remember { mutableStateOf(true) }
    Panel("Qualité des cours journaliers") {
        if (coverage == null) Text("Indisponible") else {
            Text("Jusqu’à ${number(coverage, "maxSessions")} séances attendues par titre actif de la watchlist.")
            val rows = coverage.list("details")
            if (coverage.id("expected") == 0L) Text("Aucune séance évaluable.")
            else {
                KeyValue("Bougies manquantes", "${number(coverage, "missing")} / ${number(coverage, "expected")}")
                KeyValue("Bougies incohérentes", number(coverage, "invalid"))
            }
            KeyValue("Titres sans contrôle concluant", "${number(coverage, "unknownAssets")} / ${number(coverage, "assets")}")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(onlyIssues, { onlyIssues = true }, label = { Text("Anomalies") })
                FilterChip(!onlyIssues, { onlyIssues = false }, label = { Text("Tous les titres") })
            }
            rows.filter { !onlyIssues || it.text("state") !in setOf("OK", "NOT_APPLICABLE", "HALTED") }.let { visible ->
                if (visible.isEmpty()) Text(if (rows.isEmpty()) "Watchlist vide." else "Aucune anomalie détectée.")
                visible.forEach { row ->
                    HorizontalDivider()
                    KeyValue(display(row.text("symbol")), adminState(row.text("state")))
                    Text("Fournisseur qualifié : ${display(row.text("provider"))}")
                    if (row.field("expected") != null) {
                        Text("${number(row, "missing")} manquantes / ${number(row, "expected")} · ${number(row, "invalid")} incohérentes")
                        Text("Dernière attendue : ${display(row.text("latestExpected"))}")
                        Text("Dernière disponible : ${display(row.text("latestAvailable"))}")
                        val missing = row.field("missingDates")?.takeIf { it.isJsonArray }?.asJsonArray
                            ?.mapNotNull { it.takeIf { date -> date.isJsonPrimitive }?.asString }.orEmpty()
                        if (missing.isNotEmpty()) Text("Séances manquantes : ${missing.joinToString(", ")}",
                            color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
    }
}

private fun number(row: JsonObject, field: String) = row.id(field)?.toString() ?: "Indisponible"

private fun adminState(state: String?): String = when (state) {
    "UP", "OK", "SUCCESS" -> "Opérationnel"
    "DOWN" -> "Indisponible"
    "DISABLED" -> "Désactivé"
    "LATE" -> "En retard"
    "WAITING" -> "Attendu"
    "RUNNING" -> "En cours"
    "NEVER_RUN" -> "Aucune exécution observée"
    "PARTIAL", "INCOMPLETE" -> "Incomplet"
    "FAILED" -> "Échec"
    "ISSUES" -> "Échecs récents"
    "NO_BACKLOG" -> "Aucune tâche en retard détectée"
    "UNQUALIFIED" -> "Fournisseur non qualifié"
    "CALENDAR_UNAVAILABLE" -> "Calendrier indisponible ou expiré"
    "NO_SESSIONS" -> "Aucune séance évaluable"
    "NOT_APPLICABLE" -> "Hors périmètre actions"
    "HALTED" -> "Cotation suspendue"
    else -> "État inconnu"
}

private fun localTime(value: String?): String = value?.let {
    runCatching {
        DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM)
            .withZone(ZoneId.systemDefault()).format(Instant.parse(it))
    }.getOrDefault("Indisponible")
} ?: "Non observé"
