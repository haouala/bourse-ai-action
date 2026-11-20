package lu.bourse.mobile.data

import com.google.gson.*

data class NewsItem(val id: Long, val title: String, val summary: String?, val source: String?, val originalUrl: String?, val publishedAt: String?, val collectedAt: String?, val review: String?, val raw: JsonObject)
fun newsItems(value: JsonElement) = value.rows().map { row -> NewsItem(row.id() ?: throw ContractFailure("Identifiant d’information absent"), row.required("title"), row.text("summary"), row.text("sourceCode"), row.text("originalUrl"), row.text("publishedAt"), row.text("collectedAt"), row.text("reviewStatus"), row) }
fun attributionPayload(item: NewsItem, actor: String, quote: String, evidence: String): JsonObject {
    val id = actor.toLongOrNull()?.takeIf { it > 0 } ?: throw IllegalArgumentException("Identifiant de personne invalide")
    require(item.publishedAt != null && item.review == "PENDING_REVIEW") { "Cette information ne possède pas de date fiable utilisable pour une citation" }
    require(quote.length in 10..2000 && (item.title + "\n" + item.summary.orEmpty()).contains(quote)) { "La citation doit figurer exactement dans le texte collecté (10 à 2000 caractères)" }
    require(httpsEvidence(evidence)) { "Une preuve HTTPS est requise" }
    return obj("actorId" to id, "exactQuote" to quote, "evidenceUrl" to evidence)
}
