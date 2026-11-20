package lu.bourse.mobile.data

import com.google.gson.*

class DemoInformation {
    private val sources = JsonArray().apply {
        add(obj("code" to "DEMO_FINANCE", "name" to "Flux financier fictif", "language_code" to "fr", "enabled" to false, "evidence_url" to "https://example.com/bourse-ai/demo", "last_success_at" to null))
        add(obj("code" to "DEMO_POLICY", "name" to "Flux politique fictif", "language_code" to "fr", "enabled" to false, "evidence_url" to "https://example.com/bourse-ai/demo", "last_success_at" to null))
    }
    private val items = JsonArray().apply {
        add(obj("id" to 1, "title" to "Exemple fictif : projet de droits de douane", "summary" to "Une responsable fictive déclare : Nous envisageons de nouveaux droits de douane. Les coûts pourraient augmenter pour certaines entreprises importatrices. Ce texte est créé uniquement pour tester l’application.", "source_code" to "DEMO_POLICY", "original_url" to "https://example.com/bourse-ai/demo", "published_at" to "2026-11-19T17:30:00Z", "collected_at" to "2026-11-19T17:35:00Z", "review_status" to "PENDING_REVIEW"))
        add(obj("id" to 2, "title" to "Exemple fictif : marge nette en baisse", "summary" to "Dans ce scénario pédagogique, une marge passe de 18 % à 14 %. Il ne s’agit pas d’un résultat publié par une entreprise réelle.", "source_code" to "DEMO_FINANCE", "original_url" to "https://example.com/bourse-ai/demo", "published_at" to "2026-11-18T16:00:00Z", "collected_at" to "2026-11-18T16:05:00Z", "review_status" to "PENDING_REVIEW"))
        add(obj("id" to 3, "title" to "Exemple fictif : texte sans date fiable", "summary" to "Cette information reste collectée sans devenir une publication scorable.", "source_code" to "DEMO_FINANCE", "original_url" to "https://example.com/bourse-ai/demo", "published_at" to null, "collected_at" to "2026-11-19T16:00:00Z", "review_status" to "MISSING_PUBLICATION_DATE"))
    }
    private val runs = JsonArray()
    private val attributed = mutableMapOf<String, Long>()
    private val publications = JsonArray().apply { add(obj("id" to 1, "actor_id" to 1, "content" to "Exemple fictif : Nous envisageons de nouveaux droits de douane.", "source_url" to "https://example.com/bourse-ai/demo", "published_at" to "2026-11-19T17:30:00Z", "attribution_kind" to "DEMO_ONLY")) }
    fun request(method: String, path: List<String>, query: Map<String, String>, body: JsonObject?): JsonElement? {
        if (path.take(3) == listOf("api", "v1", "social")) {
            when(path.getOrNull(3)) {
                "actors" -> return JsonArray().apply { add(obj("id" to 1, "display_name" to "Responsable fictive", "role_label" to "Personnage de démonstration", "platform" to "OTHER", "identity_confirmed" to false, "identity_evidence_url" to null)) }
                "publications" -> return publications.deepCopy()
                "events" -> {
                    if (path.last() == "scores") return JsonArray().apply { add(obj("id" to 1, "symbol" to "NVDA", "direction" to "NEGATIVE", "potential_impact" to 70, "confidence_score" to 50, "signed_score" to -35, "scored_at" to "2026-11-19T17:40:00Z", "input_snapshot" to obj("rationale" to "Exemple fictif : coûts potentiels plus élevés, interprétation encore incertaine.", "sourceUrl" to "https://example.com/bourse-ai/demo").toString())) }
                    return JsonArray().apply { add(obj("id" to 1, "title" to "Projet tarifaire fictif", "topic" to "TARIFFS", "confirmation" to "UNCONFIRMED", "retracted" to false, "evidence_url" to "https://example.com/bourse-ai/demo")) }
                }
            }
        }
        if (path.take(3) != listOf("api", "v1", "information")) return null
        when(path.getOrNull(3)) {
            "sources" -> {
                if (path.size == 4) return sources.deepCopy()
                val source = sources.firstOrNull { it.asJsonObject.text("code") == path[4] }?.asJsonObject ?: throw ApiFailure(404, "Source inconnue")
                if (path.last() == "enabled") { source.addProperty("enabled", body?.flag("enabled") == true); return JsonNull.INSTANCE }
                if (path.last() == "sync") {
                    if (source.flag("enabled") != true) throw ApiFailure(409, "Activer cette source pour la collecter")
                    source.addProperty("last_success_at", "2026-11-20T06:00:00Z")
                    runs.add(obj("id" to runs.size() + 1L, "source_code" to path[4], "status" to "SUCCEEDED", "inserted_count" to 0, "started_at" to "2026-11-20T06:00:00Z"))
                    return obj("source" to path[4], "status" to "SUCCEEDED", "inserted" to 0)
                }
            }
            "runs" -> return runs.deepCopy()
            "items" -> {
                if (path.size == 4) return JsonArray().apply { items.filter { query["source"] == null || it.asJsonObject.text("sourceCode") == query["source"] }.forEach { add(it.deepCopy()) } }
                val id = path[4].toLongOrNull() ?: throw ApiFailure(404, "Information inconnue")
                if (path.last() == "mentions") return JsonArray().apply { if (id == 1L) add(obj("actor_id" to 1, "matched_name" to "Responsable fictive", "attribution_status" to if (attributed.isEmpty()) "NAME_MATCH_ONLY" else "HUMAN_VERIFIED_QUOTE")) }
                if (path.last() == "attributions" && method == "POST" && body != null) {
                    val item = newsItems(items).firstOrNull { it.id == id } ?: throw ApiFailure(404, "Information inconnue")
                    attributionPayload(item, body.required("actorId"), body.required("exactQuote"), body.required("evidenceUrl"))
                    if (body.id("actorId") != 1L) throw ApiFailure(400, "Personne non enregistrée")
                    val key = "$id:${body.id("actorId")}:${body.text("exactQuote")}"
                    val publication = attributed.getOrPut(key) { val next = publications.size() + 1L; publications.add(obj("id" to next, "actor_id" to 1, "content" to body.required("exactQuote"), "source_url" to item.originalUrl, "published_at" to item.publishedAt, "attribution_kind" to "HUMAN_VERIFIED_QUOTE")); next }
                    return obj("publicationId" to publication)
                }
            }
        }
        return null
    }
}
