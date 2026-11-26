package lu.bourse.mobile.data

import com.google.gson.*
import java.math.BigDecimal

val horizons = listOf("DAY" to "Journée", "MONTH" to "Mois", "YEAR" to "Année")
data class ScoreAnalysis(val id: Long, val score: BigDecimal?, val previous: BigDecimal?, val change: BigDecimal?, val coverage: Int, val raw: JsonObject, val factors: List<JsonObject>)
fun scoreAnalysis(value: JsonElement): ScoreAnalysis {
    val r = value.objectValue(); val score = r.decimal("score"); val previous = r.decimal("previousScore"); val change = r.decimal("scoreChange")
    val coverageLong = r.id("coverageScore") ?: throw ContractFailure("Couverture de données absente")
    if (coverageLong !in 0L..100L) throw ContractFailure("Couverture hors limites")
    val coverage = coverageLong.toInt()
    if (score != null && (score < BigDecimal.ZERO || score > BigDecimal(100)) || coverage !in 0..100) throw ContractFailure("Score ou couverture hors limites")
    if (score != null && previous != null && change != null && (score - previous).compareTo(change) != 0) throw ContractFailure("Variation du score incohérente")
    return ScoreAnalysis(r.id() ?: throw ContractFailure("Identifiant d’analyse absent"), score, previous, change, coverage, r, r.list("factors"))
}
fun scoreRules(values: Map<String, String>): JsonObject {
    val name = values.getValue("name").trim()
    require(name.matches(Regex("[A-Za-z0-9._-]{1,120}")) && !name.startsWith("DEFAULT_")) { "Nom de variante invalide ou réservé" }
    fun weight(key: String) = values.getValue(key).toIntOrNull()?.takeIf { it in 0..50 } ?: throw IllegalArgumentException("Les amplitudes doivent être des entiers de 0 à 50")
    val tech = weight("technicalMax"); val fund = weight("fundamentalMax"); val info = weight("informationMax")
    require(tech + fund + info in 1..50) { "La somme des amplitudes doit être comprise entre 1 et 50" }
    val threshold = positiveDecimal(values.getValue("marginChangeThreshold"), "Seuil de marge")
    val low = positiveDecimal(values.getValue("rsiOversold"), "RSI bas", true); val high = positiveDecimal(values.getValue("rsiOverbought"), "RSI haut", true)
    require(threshold <= BigDecimal(100) && low < high && high <= BigDecimal(100)) { "Vérifier le seuil de marge et 0 ≤ RSI bas < RSI haut ≤ 100" }
    return obj("name" to name, "rules" to obj("technicalMax" to tech, "fundamentalMax" to fund, "informationMax" to info, "marginChangeThreshold" to threshold, "rsiOversold" to low, "rsiOverbought" to high))
}
fun evidenceLinks(value: JsonElement): List<String> {
    val links = linkedSetOf<String>()
    fun visit(v: JsonElement, depth: Int) {
        if (depth > 8) return
        when {
            v.isJsonObject -> v.asJsonObject.entrySet().forEach { visit(it.value, depth + 1) }
            v.isJsonArray -> v.asJsonArray.forEach { visit(it, depth + 1) }
            v.isJsonPrimitive && v.asJsonPrimitive.isString -> if (httpsEvidence(v.asString)) links.add(v.asString)
        }
    }
    visit(value, 0); return links.take(12)
}
fun statusLabel(value: String?): String = when(value) {
    "OPEN" -> "Ouvert"; "CLOSED" -> "Fermé"; "HALTED", "MARKET_HALTED" -> "Marché suspendu"
    "UNKNOWN", "CALENDAR_UNKNOWN" -> "Calendrier inconnu"; "STALE_DATA" -> "Prix trop ancien"; "DATA_INCOMPLETE" -> "Données incomplètes"
    "FX_STALE" -> "Taux de change absent ou périmé"; "FRESH" -> "Prix récent"; "AT_LAST_CLOSE", "MARKET_CLOSED_ESTIMATE" -> "Estimation à la dernière clôture"
    "OPENING_REFERENCE", "OPENING_REFERENCE_ESTIMATE" -> "Référence en attente de la première bougie"
    "LOSS_LIMIT_REACHED" -> "Limite de perte atteinte"; "OK" -> "Données utilisables"
    "AVAILABLE" -> "Disponible"; "MISSING" -> "Donnée absente"; "UNRESOLVED" -> "Interprétation à valider"; "STALE" -> "Donnée ancienne"
    "EXPLORATORY_ONLY" -> "Descriptif uniquement"; null -> "Indisponible"; else -> value.replace('_', ' ')
}
