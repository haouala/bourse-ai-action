package lu.bourse.mobile.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import lu.bourse.mobile.ui.theme.Rising
import lu.bourse.mobile.ui.theme.RiskAccent
import lu.bourse.mobile.ui.theme.Falling
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
    var section by remember { mutableStateOf<String?>(null) }
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
                    section = null
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

        val data = report
        val market = data?.child("market")
        val batches = data?.list("batches").orEmpty()
        val providers = data?.list("providers").orEmpty()
        val repair = data?.child("repair")
        val ml = data?.child("ml")
        val tiles = listOf(
            AdminTileModel("system", "Serveur et base",
                when {
                    server == "Injoignable" || data?.text("database") == "DOWN" -> "DOWN"
                    data?.text("backend") == "UP" && data?.text("database") == "UP" -> "UP"
                    else -> "UNKNOWN"
                }),
            AdminTileModel("market", "Qualité des cours", when {
                market == null || market.id("expected") == 0L -> "UNKNOWN"
                (market.id("missing") ?: 0) > 0 || (market.id("invalid") ?: 0) > 0 -> "ISSUES"
                (market.id("unknownAssets") ?: 0) > 0 -> "INCOMPLETE"
                else -> "OK"
            }),
            AdminTileModel("repair", "Réparation", repair?.text("state") ?: "UNKNOWN"),
            AdminTileModel("batches", "Batchs", when {
                batches.any { it.text("state") == "LATE" } -> "LATE"
                batches.any { it.text("state") == "ISSUES" } -> "ISSUES"
                batches.isNotEmpty() && batches.all { it.text("state") == "NO_BACKLOG" } -> "OK"
                else -> "UNKNOWN"
            }),
            AdminTileModel("providers", "Fournisseurs", when {
                providers.any { (it.id("failed24h") ?: 0) > 0 } -> "ISSUES"
                providers.any { (it.id("successful24h") ?: 0) > 0 } -> "OK"
                else -> "UNKNOWN"
            }),
            AdminTileModel("ml", "Dernier calcul ML", if (ml?.text("lastRun") == null) "NEVER_RUN" else "OK"),
        )
        tiles.chunked(2).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { tile ->
                    AdminTile(tile, section == tile.key, Modifier.weight(1f)) {
                        section = if (section == tile.key) null else tile.key
                    }
                }
            }
        }

        if (section == "system") Panel("Disponibilité") {
            KeyValue("Serveur depuis cet appareil", server)
            KeyValue("Backend", adminState(data?.text("backend")))
            KeyValue("PostgreSQL", adminState(data?.text("database")))
            data?.text("checkedAt")?.let { KeyValue("Vérifié le", localTime(it)) }
        }
        if (section == "repair") RepairPanel(repair)
        if (section == "market") CoveragePanel(market)
        if (section == "batches") {
            Panel("Batchs et files de travail") {
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
        }
        if (section == "providers") {
            Panel("Imports fournisseurs") {
                when {
                    data?.field("providers") == null -> Text("Indisponible")
                    providers.isEmpty() -> Text("Aucun import enregistré.")
                    else -> providers.forEach { row ->
                        KeyValue(display(row.text("code")), "${number(row, "successful24h")} réussis · ${number(row, "failed24h")} en échec / 24 h")
                        Text("Dernier succès : ${localTime(row.text("lastSuccess"))}")
                        Text("Dernière activité : ${localTime(row.text("lastActivity"))}")
                    }
                }
            }
        }
        if (section == "ml") {
            Panel("Dernier entraînement ML") {
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

private data class AdminTileModel(val key: String, val title: String, val status: String)

@Composable
private fun AdminTile(tile: AdminTileModel, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val tint = when (tile.status) {
        "UP", "OK", "SUCCESS" -> Rising
        "DOWN", "FAILED", "LATE", "ISSUES" -> Falling
        else -> RiskAccent
    }
    OutlinedCard(onClick = onClick, modifier = modifier.heightIn(min = 104.dp).testTag("admin-tile-${tile.key}"),
        border = BorderStroke(if (selected) 2.dp else 1.dp, tint),
        colors = CardDefaults.outlinedCardColors(containerColor = tint.copy(alpha = if (selected) .18f else .08f))) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(tile.title, style = MaterialTheme.typography.titleSmall)
            Text(adminState(tile.status), color = tint, style = MaterialTheme.typography.labelMedium)
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
