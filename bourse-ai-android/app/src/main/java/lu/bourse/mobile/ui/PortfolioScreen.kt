package lu.bourse.mobile.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import lu.bourse.mobile.*
import lu.bourse.mobile.data.*
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

private fun tradeFields(includeSymbol: Boolean) = buildList {
    if (includeSymbol) { add(InputField("symbol", "Symbole déjà enregistré au suivi")); add(InputField("horizon", "Horizon de suivi", "MEDIUM", options = listOf("SHORT", "MEDIUM", "LONG"))) }
    else add(InputField("side", "Opération déjà exécutée", "SELL", options = listOf("SELL", "BUY")))
    add(InputField("quantity", "Quantité", numeric = true)); add(InputField("price", "Prix par unité dans la devise de l’actif", numeric = true)); add(InputField("fees", "Frais dans la devise de l’actif", "0", true))
    add(InputField("fx", "FX vers le portefeuille (1 si même devise)", numeric = true))
    add(InputField("executedAt", "Date et heure avec fuseau (ISO)", Instant.now().toString()))
}

@Composable
fun PortfolioScreen(store: AppStore, plan: (Long, Long) -> Unit, asset: (String) -> Unit) {
    val config by store.config.collectAsStateWithLifecycle()
    val states by store.resources.collectAsStateWithLifecycle()
    var form by rememberSaveable { mutableStateOf<String?>(null) }
    var chosen by rememberSaveable { mutableStateOf<Long?>(null) }
    var requestId by rememberSaveable { mutableStateOf(UUID.randomUUID().toString()) }
    val portfolio = config.portfolioId
    fun refresh() { portfolio?.let { store.load("positions:$it", api("portfolios", it, "positions")); store.load("risk:$it", api("portfolios", it, "evaluate"), method = "POST") } }
    fun openForm(name: String, position: Long? = null) { store.clear("portfolio-write"); chosen = position; requestId = UUID.randomUUID().toString(); form = name }
    LaunchedEffect(config) { if (config.demo && portfolio == null) store.selectPortfolio(1) else refresh() }
    Page("Portefeuille", "Déclarer des opérations déjà exécutées chez votre courtier. Aucun ordre n’est envoyé.") {
        Row { TextButton(onClick = { openForm("create") }) { Text("Créer") }; TextButton(onClick = { openForm("select") }) { Text("Choisir un identifiant") } }
        if (portfolio == null) Note("Créer un portefeuille ou saisir son identifiant. La perte maximale se définit sur la mise totale.")
        else {
            Text("Portefeuille $portfolio", style = MaterialTheme.typography.titleLarge)
            DataBlock(store, "risk:$portfolio", decode = { it.objectValue() }) { risk -> RiskPanel(risk) }
            Button(onClick = { openForm("buy") }, modifier = Modifier.fillMaxWidth()) { Text("Déclarer un achat effectué") }
            OutlinedButton(onClick = { refresh() }) { Text("Actualiser les positions et le risque") }
            DataBlock(store, "positions:$portfolio", decode = ::positions) { all ->
                if (all.isEmpty()) Note("Aucune position enregistrée.")
                all.forEach { position -> Panel("${position.symbol} · ${if (position.status == "OPEN") "Position ouverte" else "Position clôturée"}") {
                    KeyValue("Quantité détenue", priceText(position.quantity)); KeyValue("Prix moyen d’entrée", priceText(position.entry, position.currency))
                    KeyValue("Ouverture", dateLabel(position.openedAt)); Text("Horizon : ${position.horizon} · coût restant dans le portefeuille : ${amount(position.cost)}")
                    TextButton(onClick = { asset(position.symbol) }) { Text("Graphique et analyse de l’actif") }
                    if (position.status == "OPEN") {
                        OutlinedButton(onClick = { openForm("trade", position.id) }) { Text("Déclarer vente partielle ou nouvel achat") }
                        OutlinedButton(onClick = { openForm("mark", position.id) }) { Text("Renseigner un prix et un FX datés") }
                        TextButton(onClick = { plan(portfolio, position.id) }) { Text("Plan et scénarios illustratifs") }
                    }
                } }
            }
        }
        states["portfolio-write"]?.error?.let { if (form == null) Note(it, true) }
    }
    when(form) {
        "create" -> EditorDialog("Créer un portefeuille", listOf(InputField("name", "Nom", "Mon portefeuille"), InputField("currency", "Devise de référence", "EUR"), InputField("capital", "Capital total", "1000", true), InputField("loss", "Perte maximale sur la mise totale", "500", true)), store, "portfolio-write", { form = null }) { values ->
            store.mutate("portfolio-write", api("portfolios"), createPortfolio(values)) { result -> store.selectPortfolio(result.objectValue().id() ?: throw ContractFailure("Portefeuille créé sans identifiant")); form = null }
        }
        "select" -> EditorDialog("Choisir un portefeuille existant", listOf(InputField("id", "Identifiant positif", portfolio?.toString().orEmpty(), true)), store, "portfolio-write", { form = null }) { values -> val id = values.getValue("id").toLongOrNull()?.takeIf { it > 0 } ?: throw IllegalArgumentException("Identifiant invalide"); store.selectPortfolio(id); form = null }
        "buy" -> if (portfolio != null) EditorDialog("Déclarer un achat déjà exécuté", tradeFields(true), store, "portfolio-write", { form = null }, "Enregistrer la déclaration") { values ->
            store.mutate("portfolio-write", api("portfolios", portfolio, "positions"), declaredTrade(values, requestId)) { form = null; refresh() }
        }
        "trade" -> if (portfolio != null) {
            val position = states["positions:$portfolio"]?.value?.let { runCatching { positions(it).firstOrNull { p -> p.id == chosen } }.getOrNull() }
            if (position != null) EditorDialog("Opération sur ${position.symbol}", tradeFields(false), store, "portfolio-write", { form = null }, "Enregistrer l’opération effectuée") { values ->
                store.mutate("portfolio-write", api("portfolios", portfolio, "positions", position.id, "trades"), declaredTrade(values, requestId, position)) { form = null; refresh() }
            }
        }
        "mark" -> if (portfolio != null && chosen != null) EditorDialog("Prix et change observés", listOf(InputField("price", "Prix dans la devise de l’actif", numeric = true), InputField("fx", "FX vers la devise du portefeuille", numeric = true), InputField("observedAt", "Date du prix, ISO avec fuseau", Instant.now().toString()), InputField("fxObservedAt", "Date du FX, indépendante du prix", Instant.now().toString()), InputField("source", "Source du prix", "MANUAL_MOBILE")), store, "portfolio-write", { form = null }) { values ->
            store.mutate("portfolio-write", api("portfolios", portfolio, "positions", chosen!!, "mark"), priceMark(values), "PUT") { form = null; refresh() }
        }
    }
}

@Composable
fun RiskPanel(risk: com.google.gson.JsonObject) {
    Panel("Risque global · ${display(risk.text("baseCurrency"))}") {
        val budget = risk.decimal("maxLossAmount")
        val remaining = risk.decimal("remainingLossBudget")
        if (budget != null && remaining != null && budget.signum() > 0) {
            val used = ((budget.toDouble() - remaining.toDouble()) / budget.toDouble()).coerceIn(0.0, 1.0)
            Text("Budget de perte consommé", style = MaterialTheme.typography.labelLarge)
            LinearProgressIndicator(progress = { used.toFloat() }, modifier = Modifier.fillMaxWidth(),
                color = lu.bourse.mobile.ui.theme.RiskAccent)
        }
        KeyValue("État de la valorisation", statusLabel(risk.text("status")))
        KeyValue("Liquidités", amount(risk.decimal("cash"), risk.text("baseCurrency")))
        KeyValue("Valeur connue, éventuellement partielle", amount(risk.decimal("knownEquity"), risk.text("baseCurrency")))
        KeyValue("Perte globale", amount(risk.decimal("loss"), risk.text("baseCurrency")))
        KeyValue("Perte maximale autorisée", amount(risk.decimal("maxLossAmount"), risk.text("baseCurrency")))
        KeyValue("Budget de perte restant", amount(risk.decimal("remainingLossBudget"), risk.text("baseCurrency")))
        Text("Évaluation : ${dateLabel(risk.text("evaluatedAt"))}")
        if (risk.decimal("loss") == null) Note("Valorisation incomplète : perte et budget restant ne sont pas calculables. Ne pas confondre la valeur connue avec une valorisation complète.", true)
        risk.list("quality").forEach { q -> Text("Actif ${display(q.text("assetId"))} : ${statusLabel(q.text("status"))} · prix ${dateLabel(q.text("observedAt"))} · FX ${dateLabel(q.text("fxObservedAt"))}", style = MaterialTheme.typography.bodySmall) }
    }
}

@Composable
fun PositionPlanScreen(store: AppStore, portfolio: Long, position: Long) {
    val config by store.config.collectAsStateWithLifecycle()
    var change by rememberSaveable { mutableFloatStateOf(0f) }
    LaunchedEffect(config, portfolio, position) { store.load("plan:$position", api("portfolios", portfolio, "positions", position, "plan")) }
    Page("Plan et scénarios", "Un scénario illustre une hypothèse de prix ; il ne prédit pas la courbe future.") {
        DataBlock(store, "plan:$position", decode = { it.objectValue() }) { result ->
            KeyValue("État du plan backend", display(result.text("status")))
            result.child("risk")?.let { RiskPanel(it) }
            val p = result.child("position")
            val entry = p?.decimal("averageEntryPrice")
            if (entry != null && p != null) {
                val hypothetical = entry * (BigDecimal.ONE + BigDecimal.valueOf(change.toDouble()).divide(BigDecimal(100)))
                Panel("Scénario personnel · ${display(p.text("symbol"))}") {
                    Text("Variation du prix d’entrée : ${amount(BigDecimal.valueOf(change.toDouble()))}%")
                    Slider(value = change, onValueChange = { change = it }, valueRange = -30f..30f)
                    KeyValue("Prix hypothétique", priceText(hypothetical, p.text("currency")))
                    Text("Variation du prix uniquement, avant frais et effet de change. Cette hypothèse ne modifie aucune règle et ne déclenche aucun ordre.")
                    val levels = listOfNotNull("Entrée" to entry, result.decimal("observedPrice")?.let { "Observé" to it }, result.decimal("proposedProtection")?.let { "Protection illustrative" to it }, result.decimal("targetPrice")?.let { "Objectif illustratif 2R" to it }, "Hypothèse" to hypothetical)
                    val points = normalizedPoints(levels.map { it.second }); val color = MaterialTheme.colorScheme.primary
                    Canvas(Modifier.fillMaxWidth().height(150.dp)) { points.forEachIndexed { index, value -> val y = 12.dp.toPx() + (1 - value) * (size.height - 24.dp.toPx()); drawLine(color.copy(alpha = 0.3f + index * 0.1f), Offset(0f, y), Offset(size.width, y), 2.dp.toPx()) } }
                    levels.forEach { KeyValue(it.first, priceText(it.second, p.text("currency"))) }
                }
            }
            Note("L’objectif 2R du backend est illustratif et peut rester indisponible si le prix ou le FX n’est pas exploitable. Il ne s’agit pas d’un objectif de cours validé financièrement.")
        }
    }
}
