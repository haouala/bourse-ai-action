package lu.bourse.mobile.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import lu.bourse.mobile.*
import lu.bourse.mobile.data.*
import java.util.UUID

@Composable
fun AnalysisScreen(store: AppStore, symbol: String, rulesetId: Long?, season: () -> Unit, rules: () -> Unit) {
    var horizon by rememberSaveable { mutableStateOf("MONTH") }
    val config by store.config.collectAsStateWithLifecycle()
    val key = "analysis:$symbol:$horizon:${rulesetId ?: "default"}"
    fun history() = store.load("history:$symbol:$horizon", api("explanations", symbol, "history"), mapOf("horizon" to horizon))
    LaunchedEffect(config, symbol, horizon) { history() }
    Page("Analyse · $symbol", "Distinguer les nouvelles et les règles financières qui modifient le score.") {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { horizons.forEach { (key, label) -> FilterChip(selected = horizon == key, onClick = { horizon = key }, label = { Text(label) }) } }
        Text("Version de règles : ${rulesetId?.toString() ?: "défaut de cet horizon"}", style = MaterialTheme.typography.labelLarge)
        Button(modifier = Modifier.fillMaxWidth().testTag("calculate-score"), onClick = {
            val query = mutableMapOf("horizon" to horizon).apply { rulesetId?.let { put("rulesetId", it.toString()) } }
            store.load(key, api("explanations", symbol), query, "POST")
        }) { Text("Calculer une nouvelle analyse") }
        DataBlock(store, key, "Calculer pour enregistrer un score avec les données disponibles.", ::scoreAnalysis) { analysis ->
            LaunchedEffect(analysis.id) { history() }
            Panel("Score explicatif") {
                Text(analysis.score?.let { "${amount(it)} / 100" } ?: "Score indisponible", style = MaterialTheme.typography.headlineLarge)
                analysis.score?.let { LinearProgressIndicator(progress = { it.toFloat() / 100 }, modifier = Modifier.fillMaxWidth()) }
                Text("${analysis.coverage}% des composantes attendues disposent de données. Ce pourcentage n’est pas une probabilité de gain.")
                KeyValue("Calcul daté", dateLabel(analysis.raw.text("asOf")))
                KeyValue("Qualité du prix", statusLabel(analysis.raw.child("quoteQuality")?.text("status")))
                analysis.raw.child("quoteQuality")?.text("observedAt")?.let { Text("Prix observé : ${dateLabel(it)}") }
                KeyValue("Variation depuis la précédente analyse", analysis.change?.let { "${if (it.signum() > 0) "+" else ""}${amount(it)} points" } ?: "Première analyse comparable indisponible")
                if (analysis.raw.flag("rulesetChanged") == true) Note("La version des règles a changé. Cette variation ne provient donc pas uniquement du marché.")
            }
            Note("Règles expérimentales : ce score aide à réexaminer un actif. Il ne constitue ni une prévision de cours ni un ordre d’achat ou de vente.")
            analysis.factors.forEach { factor -> Panel("${display(factor.text("category"))} · ${amount(factor.decimal("points"))} points") {
                Text(statusLabel(factor.text("status")), style = MaterialTheme.typography.labelLarge)
                Text(display(factor.text("explanation")))
                factor.field("evidence")?.let { evidence -> evidenceLinks(evidence).forEachIndexed { index, url -> Evidence(url, "Consulter la source ${index + 1}") }; JsonDetails(evidence) }
            } }
            analysis.raw.child("contributionChanges")?.let { changes -> Panel("Pourquoi le score a changé") { changes.entrySet().forEach { (name, value) -> KeyValue(name, "${value.asString} points") } } }
            JsonDetails(analysis.raw, "Voir la trace complète de l’analyse")
        }
        OutlinedButton(onClick = season, modifier = Modifier.fillMaxWidth()) { Text("Saisonnalité mensuelle, trimestrielle et annuelle") }
        OutlinedButton(onClick = rules, modifier = Modifier.fillMaxWidth()) { Text("Comparer et créer une version de règles") }
        Panel("Analyses précédentes") {
            TextButton(onClick = { history() }) { Text("Actualiser l’historique") }
            DataBlock(store, "history:$symbol:$horizon", decode = { it.rows() }) { rows ->
                if (rows.isEmpty()) Text("Aucune analyse enregistrée pour cet horizon.")
                rows.take(10).forEach { row ->
                    TextButton(onClick = { row.id()?.let { store.load(key, api("explanations", "snapshots", it)) } }) { Text("${dateLabel(row.text("asOf"))} · score ${amount(row.decimal("score"))} · règles ${display(row.text("rulesetId"))}") }
                }
            }
        }
    }
}

@Composable
fun RulesScreen(store: AppStore, selected: (Long?) -> Unit) {
    val config by store.config.collectAsStateWithLifecycle()
    var create by rememberSaveable { mutableStateOf(false) }
    fun refresh() = store.load("rules", api("explanations", "rulesets"))
    LaunchedEffect(config) { refresh() }
    Page("Versions des règles", "Une nouvelle version conserve les analyses passées intactes.") {
        Note("Les pondérations sont expérimentales. Leur modification nécessite une validation financière indépendante ; un meilleur score ne démontre pas un meilleur rendement.")
        Button(onClick = { store.clear("rules-write"); create = true }) { Text("Créer une variante") }
        OutlinedButton(onClick = { selected(null) }) { Text("Utiliser les règles par défaut") }
        DataBlock(store, "rules", decode = { it.rows() }) { rows -> rows.forEach { row -> Panel(display(row.text("name"))) {
            Text("Version ${display(row.text("id"))} · ${display(row.text("method"))}")
            row.text("configurationJson")?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            TextButton(onClick = { row.id()?.let { selected(it) } }) { Text("Utiliser cette version") }
        } } }
    }
    if (create) EditorDialog("Nouvelle version expérimentale", listOf(
        InputField("name", "Nom unique", "MOBILE_${UUID.randomUUID().toString().take(8)}"),
        InputField("technicalMax", "Amplitude technique", "15", true), InputField("fundamentalMax", "Amplitude financière", "15", true), InputField("informationMax", "Amplitude des informations", "20", true),
        InputField("marginChangeThreshold", "Seuil de variation de marge (points)", "3", true), InputField("rsiOversold", "RSI bas", "30", true), InputField("rsiOverbought", "RSI haut", "70", true)
    ), store, "rules-write", { create = false }) { values ->
        store.mutate("rules-write", api("explanations", "rulesets"), scoreRules(values)) { result -> create = false; selected(result.objectValue().id() ?: throw ContractFailure("Version créée sans identifiant")) }
    }
}

@Composable
fun SeasonalityScreen(store: AppStore, symbol: String) {
    var scale by rememberSaveable { mutableStateOf("monthly") }
    val config by store.config.collectAsStateWithLifecycle()
    LaunchedEffect(symbol, config) { store.load("seasons:$symbol", api("seasonality", symbol)) }
    Page("Saisonnalité · $symbol", "Statistiques descriptives sur les périodes complètes, sans saisonnalité horaire.") {
        Note("Contribution au score : 0 point avant validation indépendante. Une fréquence historique de hausse n’est pas une probabilité de gain future.")
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { listOf("monthly" to "Mois", "quarterly" to "Trimestres", "annual" to "Années").forEach { (key, label) -> FilterChip(selected = scale == key, onClick = { scale = key }, label = { Text(label) }) } }
        DataBlock(store, "seasons:$symbol", decode = { it.objectValue() }) { result ->
            KeyValue("Qualité de la série", display(result.text("seriesStatus")))
            KeyValue("Date de l’étude", dateLabel(result.text("asOf")))
            val buckets = result.child("statistics")?.list(scale).orEmpty()
            if (buckets.isEmpty()) Note("Historique insuffisant pour ces périodes complètes.")
            else {
                val values = buckets.map { it.decimal("meanPercent") }
                val max = values.filterNotNull().map { it.abs() }.maxOrNull()?.toFloat()?.takeIf { it > 0 } ?: 1f
                val positive = MaterialTheme.colorScheme.primary; val negative = MaterialTheme.colorScheme.error; val grid = MaterialTheme.colorScheme.outlineVariant
                Canvas(Modifier.fillMaxWidth().height(150.dp)) {
                    val mid = size.height / 2; drawLine(grid, Offset(0f, mid), Offset(size.width, mid))
                    values.forEachIndexed { index, value -> if (value != null) { val height = (value.toFloat() / max * mid * 0.85f); val unit = size.width / values.size; drawRect(if (height >= 0) positive else negative, Offset(index * unit + unit * 0.15f, if (height >= 0) mid - height else mid), Size(unit * 0.7f, kotlin.math.abs(height))) } }
                }
                Text("Barres dans l’ordre des périodes ci-dessous · moyenne en % · absence de donnée = absence de barre", style = MaterialTheme.typography.labelSmall)
                buckets.forEach { bucket -> Panel(if (scale == "monthly") "Mois ${display(bucket.text("bucket"))}" else if (scale == "quarterly") "Trimestre ${display(bucket.text("bucket"))}" else "Années complètes") {
                    KeyValue("Nombre d’observations", display(bucket.text("observations")))
                    KeyValue("Moyenne / médiane", "${amount(bucket.decimal("meanPercent"))}% / ${amount(bucket.decimal("medianPercent"))}%")
                    KeyValue("Périodes en hausse", "${amount(bucket.decimal("winFrequencyPercent"))}%")
                    KeyValue("Pire / meilleure période", "${amount(bucket.decimal("worstPercent"))}% / ${amount(bucket.decimal("bestPercent"))}%")
                    Text("Fiabilité : ${display(bucket.text("reliability"))}")
                    JsonDetails(bucket, "Voir l’historique et la dispersion")
                } }
            }
        }
    }
}
