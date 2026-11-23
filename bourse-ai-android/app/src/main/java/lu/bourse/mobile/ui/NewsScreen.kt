package lu.bourse.mobile.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import lu.bourse.mobile.*
import lu.bourse.mobile.data.*

@Composable
fun NewsScreen(store: AppStore, open: (Long) -> Unit, sources: () -> Unit, persons: () -> Unit, events: () -> Unit) {
    val config by store.config.collectAsStateWithLifecycle()
    var filter by rememberSaveable { mutableStateOf<String?>(null) }
    var search by rememberSaveable { mutableStateOf("") }
    fun refresh() = store.load("news", api("information", "items"), filter?.let { mapOf("source" to it) } ?: emptyMap())
    LaunchedEffect(config, filter) { refresh(); store.load("sources", api("information", "sources")); if (!config.demo) store.load("gdelt-news", api("news-collection", "items")) }
    Page("Informations", "Publication, collecte et confirmation restent distinctes.") {
        OutlinedTextField(search, { search = it }, label = { Text("Rechercher dans les informations") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        DataBlock(store, "sources", decode = { it.rows() }) { rows -> Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(filter == null, { filter = null }, label = { Text("Toutes les sources") })
            rows.forEach { row -> val code = row.required("code"); FilterChip(filter == code, { filter = code }, label = { Text(row.text("name") ?: code) }) }
        } }
        Row { TextButton(onClick = sources) { Text("Sources") }; TextButton(onClick = persons) { Text("Personnes") }; TextButton(onClick = events) { Text("Impacts") } }
        OutlinedButton(onClick = { refresh() }) { Text("Actualiser les informations") }
        if (!config.demo) Panel("Actualités GDELT : NGrams et GKG") {
            Text("Mentions détectées dans les archives GDELT. Une mention ne constitue ni une recommandation financière ni une citation vérifiée.", style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = { store.load("gdelt-news", api("news-collection", "items")) }) { Text("Actualiser les archives") }
            DataBlock(store, "gdelt-news", decode = { it.rows() }) { articles ->
                if (articles.isEmpty()) Text("Aucune archive GDELT collectée pour les actions surveillées.")
                articles.filter {
                    search.isBlank() || it.text("symbol")?.contains(search,true)==true ||
                        it.text("title")?.contains(search,true)==true
                }.take(30).forEach { row ->
                    Text("${display(row.text("symbol"))} · ${display(row.text("source"))}", style = MaterialTheme.typography.labelMedium)
                    Text(display(row.text("title")))
                    Evidence(row.text("url"), "Consulter l’article original")
                    HorizontalDivider()
                }
            }
        }
        DataBlock(store, "news", decode = ::newsItems) { all ->
            val visible = all.filter { it.title.contains(search, true) || it.summary?.contains(search, true) == true }
            if (visible.isEmpty()) Note("Aucune information. Configurer les sources du backend ou modifier la recherche.")
            visible.forEach { item -> Panel(item.title) {
                Text("${display(item.source)} · ${dateLabel(item.publishedAt)}", style = MaterialTheme.typography.labelMedium)
                Text(item.summary?.take(200) ?: "Résumé non fourni")
                Text("Statut : ${display(item.review)}", style = MaterialTheme.typography.labelSmall)
                TextButton(onClick = { open(item.id) }) { Text("Lire et consulter les preuves") }
            } }
        }
    }
}

@Composable
fun NewsDetailScreen(store: AppStore, itemId: Long) {
    val config by store.config.collectAsStateWithLifecycle()
    var attributing by rememberSaveable { mutableStateOf(false) }
    var publication by rememberSaveable { mutableStateOf<Long?>(null) }
    fun mentions() = store.load("mentions:$itemId", api("information", "items", itemId, "mentions"))
    LaunchedEffect(config, itemId) { store.load("news-detail", api("information", "items")); mentions() }
    Page("Publication et preuves") {
        DataBlock(store, "news-detail", decode = ::newsItems) { rows ->
            val item = rows.firstOrNull { it.id == itemId }
            if (item == null) Note("Cette information ne figure plus dans les 200 dernières entrées disponibles. Revenir à la liste pour actualiser.")
            else {
                Panel(item.title) {
                    Text(item.summary ?: "Texte non fourni par le flux")
                    KeyValue("Publication originale", dateLabel(item.publishedAt))
                    KeyValue("Date de collecte", dateLabel(item.collectedAt))
                    KeyValue("Validation", display(item.review))
                    Evidence(item.originalUrl)
                }
                Note("Un nom repéré dans un article ne prouve pas que cette personne a prononcé une déclaration. Le score utilise les événements validés et leur impact propre à chaque entreprise.")
                DataBlock(store, "mentions:$itemId", decode = { it.rows() }) { found ->
                    if (found.isEmpty()) Text("Aucun nom enregistré repéré dans ce texte.")
                    found.forEach { mention -> Panel(display(mention.text("matchedName"))) { KeyValue("Identifiant de personne", display(mention.text("actorId"))); KeyValue("Attribution", display(mention.text("attributionStatus"))); Text("Une simple mention n’est pas une citation vérifiée.") } }
                }
                if (item.review == "PENDING_REVIEW" && item.publishedAt != null) OutlinedButton(onClick = { store.clear("quote-write"); attributing = true }) { Text("Valider manuellement une citation exacte") }
                publication?.let { Note("Citation enregistrée : publication $it. La qualification de son impact reste une étape distincte.") }
                if (attributing) EditorDialog("Attribution humaine d’une citation", listOf(InputField("actorId", "Identifiant de la personne enregistrée", numeric = true), InputField("quote", "Passage exact présent dans le texte"), InputField("evidence", "Lien HTTPS prouvant l’attribution", item.originalUrl.orEmpty())), store, "quote-write", { attributing = false }, "Confirmer l’attribution") { values ->
                    val payload = attributionPayload(item, values.getValue("actorId"), values.getValue("quote"), values.getValue("evidence"))
                    store.mutate("quote-write", api("information", "items", itemId, "attributions"), payload) { result -> publication = result.objectValue().id("publicationId"); attributing = false; mentions() }
                }
                JsonDetails(item.raw, "Voir la version collectée et ses identifiants")
            }
        }
    }
}

@Composable
fun SourcesScreen(store: AppStore) {
    val config by store.config.collectAsStateWithLifecycle()
    val states by store.resources.collectAsStateWithLifecycle()
    fun refresh() { store.load("sources", api("information", "sources")); store.load("feed-runs", api("information", "runs")) }
    LaunchedEffect(config) { refresh() }
    Page("Sources d’informations", "Flux RSS et Atom du catalogue backend · aucun token dans le mobile") {
        OutlinedButton(onClick = { refresh() }) { Text("Actualiser les sources et imports") }
        Note("Activer une source autorise sa collecte par le backend. La planification du serveur est distincte ; le bouton Collecter déclenche un essai manuel.")
        states["feed-write"]?.error?.let { Note(it, true) }
        DataBlock(store, "sources", decode = { it.rows() }) { sources -> sources.forEach { row ->
            val code = row.required("code"); val enabled = row.flag("enabled") == true
            Panel(row.text("name") ?: code) {
                Text("$code · ${display(row.text("languageCode"))}")
                Row { Text(if (enabled) "Source active" else "Source désactivée"); Switch(checked = enabled, enabled = states["feed-write"]?.loading != true, onCheckedChange = { active -> store.mutate("feed-write", api("information", "sources", code, "enabled"), obj("enabled" to active), "PUT") { refresh() } }) }
                KeyValue("Dernière réussite", dateLabel(row.text("lastSuccessAt")))
                row.text("lastError")?.let { Note("Dernière erreur de collecte : $it", true) }
                row.text("nextAttemptAt")?.let { Text("Prochain essai autorisé : ${dateLabel(it)}") }
                Evidence(row.text("evidenceUrl"), "Site de la source")
                OutlinedButton(enabled = enabled && states["feed-write"]?.loading != true, onClick = { store.mutate("feed-write", api("information", "sources", code, "sync")) { refresh() } }) { Text("Collecter ce flux") }
            }
        } }
        if (!config.demo) Panel("Archives GDELT — trois méthodes") {
            Text("GKG et NGrams : archives téléchargées et indexées localement. RSS : flux déjà présents dans le catalogue. N’utilise pas l’API DOC limitée à 429.",style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = { store.load("bulk-status", api("news-collection", "status")) }) { Text("Statut des collecteurs") }
            DataBlock(store, "bulk-status", decode = { it.objectValue() }) { result ->
                Text("Planification automatique : ${if(result.flag("automaticEnabled")==true) "activée" else "désactivée"}")
                result.list("runs").take(5).forEach { record ->
                    Text("${display(record.text("method"))} · ${display(record.text("status"))} · ${display(record.text("errorCode"))}",
                        style = MaterialTheme.typography.bodySmall)
                }
            }
            Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("GKG","NGRAMS","RSS").forEach { method ->
                    OutlinedButton(enabled = states["bulk-run"]?.loading != true,
                        onClick = { store.mutate("bulk-run", api("news-collection", "run"), query = mapOf("method" to method)) { store.load("bulk-status", api("news-collection", "status")); store.load("gdelt-news", api("news-collection", "items")) } }) {
                        Text("Lot $method")
                    }
                }
            }
            Text("Un lot déjà traité est ignoré. L’archive NGrams doit être publiée pour la minute choisie ; le backend utilise le dernier créneau GKG par défaut.",style = MaterialTheme.typography.bodySmall)
        }
        Panel("Derniers imports") { DataBlock(store, "feed-runs", decode = { it.rows() }) { runs -> if (runs.isEmpty()) Text("Aucun import enregistré."); runs.take(8).forEach { Text("${display(it.text("sourceCode"))} · ${display(it.text("status"))} · ${display(it.text("insertedCount"))} ajouts · ${dateLabel(it.text("startedAt"))}") } } }
    }
}

@Composable
fun PersonsScreen(store: AppStore) {
    val config by store.config.collectAsStateWithLifecycle()
    var selected by rememberSaveable { mutableStateOf<Long?>(null) }
    LaunchedEffect(config) { store.load("actors", api("social", "actors")); store.load("publications", api("social", "publications")) }
    Page("Personnes et publications", "L’influence dépend du sujet, de la fonction et de la date ; elle ne dépend pas seulement du nombre d’abonnés.") {
        Note("Les éditeurs institutionnels et les personnes citées sont distincts. Un compte éditeur ne prouve pas l’identité de chaque intervenant.")
        DataBlock(store, "actors", decode = { it.rows() }) { actors ->
            if (actors.isEmpty()) Text("Aucune personne enregistrée dans le backend.")
            actors.forEach { actor -> Panel(display(actor.text("displayName"))) {
                Text("${display(actor.text("roleLabel"))} · ${display(actor.text("platform"))}")
                Text("Identifiant ${display(actor.text("id"))} · ${if (actor.flag("identityConfirmed") == true) "Identité documentée" else "Identité non confirmée"}")
                Evidence(actor.text("identityEvidenceUrl"), "Preuve d’identité")
                TextButton(onClick = { selected = actor.id() }) { Text("Voir ses publications") }
            } }
        }
        selected?.let { actorId -> Panel("Publications de la personne $actorId") { DataBlock(store, "publications", decode = { it.rows().filter { row -> row.id("actorId") == actorId } }) { rows ->
            if (rows.isEmpty()) Text("Aucune publication dans les dernières entrées disponibles.")
            rows.forEach { row -> Text(display(row.text("content"))); Text("${dateLabel(row.text("publishedAt"))} · ${display(row.text("attributionKind"))}"); Evidence(row.text("sourceUrl")) }
        } } }
    }
}

@Composable
fun EventsScreen(store: AppStore) {
    val config by store.config.collectAsStateWithLifecycle()
    var selected by rememberSaveable { mutableStateOf<Long?>(null) }
    LaunchedEffect(config) { store.load("events", api("social", "events")) }
    Page("Impacts par entreprise", "Un même événement peut affecter deux entreprises en sens opposé.") {
        DataBlock(store, "events", decode = { it.rows() }) { events ->
            if (events.isEmpty()) Note("Aucun événement qualifié. Une information collectée ne reçoit pas automatiquement une direction financière.")
            events.forEach { event -> Panel(display(event.text("title"))) {
                Text("${display(event.text("topic"))} · ${display(event.text("confirmation"))}")
                if (event.flag("retracted") == true) Note("Cet événement a été retiré ou corrigé.", true)
                Evidence(event.text("evidenceUrl"), "Consulter la confirmation")
                TextButton(onClick = { selected = event.id(); selected?.let { store.load("event-scores", api("social", "events", it, "scores")) } }) { Text("Voir les entreprises affectées") }
            } }
        }
        if (selected != null) DataBlock(store, "event-scores", decode = { it.rows().distinctBy { row -> row.text("symbol") } }) { scores -> scores.forEach { score -> Panel(display(score.text("symbol"))) {
            KeyValue("Direction potentielle", display(score.text("direction")))
            KeyValue("Impact / confiance dans l’interprétation", "${amount(score.decimal("potentialImpact"))} / ${amount(score.decimal("confidenceScore"))}")
            KeyValue("Contribution signée", amount(score.decimal("signedScore")))
            Text("La confiance ne mesure pas la probabilité de gain.")
            score.text("inputSnapshot")?.let { raw -> val parsed = remember(raw) { runCatching { com.google.gson.JsonParser.parseString(raw) }.getOrNull() }; parsed?.let { evidence -> evidenceLinks(evidence).forEach { Evidence(it) }; JsonDetails(evidence, "Voir les raisons et les publications") } }
        } } }
    }
}
