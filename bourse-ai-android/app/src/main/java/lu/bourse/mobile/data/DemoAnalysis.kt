package lu.bourse.mobile.data

import com.google.gson.*
import java.math.BigDecimal

class DemoAnalysis {
    private val ruleVersions = JsonArray().apply {
        add(rule(1, "DEFAULT_DAY_V1", 20, 5, 20)); add(rule(2, "DEFAULT_MONTH_V1", 15, 15, 20)); add(rule(3, "DEFAULT_YEAR_V1", 10, 25, 15))
    }
    private val snapshots = mutableListOf<JsonObject>()
    private fun rule(id: Long, name: String, tech: Int, fund: Int, info: Int) = obj("id" to id, "name" to name, "method" to "DEMO_ONLY", "configuration_json" to obj("technicalMax" to tech, "fundamentalMax" to fund, "informationMax" to info, "marginChangeThreshold" to 3, "rsiOversold" to 30, "rsiOverbought" to 70).toString())
    fun request(method: String, path: List<String>, query: Map<String, String>, body: JsonObject?): JsonElement? {
        if (path.take(3) == listOf("api", "v1", "seasonality") && path.size == 4) {
            fun buckets(count: Int, scale: String) = JsonArray().apply { repeat(count) { i -> add(obj("scale" to scale, "bucket" to i + 1, "observations" to 5, "meanPercent" to (i % 5 - 2) * 0.7, "medianPercent" to (i % 5 - 2) * 0.5, "winFrequencyPercent" to 60, "worstPercent" to -7.2, "bestPercent" to 8.4, "standardDeviation" to 3.2, "reliability" to "DEMO_5_YEARS", "history" to JsonArray())) } }
            return obj("symbol" to path.last(), "asOf" to "2026-11-20T06:00:00Z", "method" to "DEMO_ONLY", "seriesStatus" to "DEMO_SYNTHETIC", "usableForScoring" to false, "statistics" to obj("monthly" to buckets(12, "MONTH"), "quarterly" to buckets(4, "QUARTER"), "annual" to buckets(1, "YEAR"), "rejectedPeriods" to 0))
        }
        if (path.take(3) != listOf("api", "v1", "explanations")) return null
        if (path.last() == "rulesets") {
            if (method == "GET") return ruleVersions.deepCopy()
            require(body != null); val name = body.required("name")
            if (ruleVersions.any { it.asJsonObject.text("name") == name }) throw ApiFailure(409, "Nom de version déjà utilisé")
            val id = ruleVersions.size() + 1L
            ruleVersions.add(obj("id" to id, "name" to name, "method" to "DEMO_ONLY", "configuration_json" to body.child("rules")!!.toString()))
            return obj("id" to id)
        }
        if (path.size == 5 && path[3] == "snapshots") return snapshots.firstOrNull { it.id() == path[4].toLongOrNull() }?.deepCopy() ?: throw ApiFailure(404, "Analyse inconnue")
        val symbol = path.getOrNull(3) ?: return null
        val horizon = query["horizon"] ?: "MONTH"
        if (path.last() == "history") return JsonArray().apply { snapshots.filter { it.text("symbol") == symbol && it.text("horizon") == horizon }.asReversed().forEach { add(it.deepCopy()) } }
        if (method != "POST") return null
        val id = query["rulesetId"]?.toLongOrNull() ?: when(horizon) { "DAY" -> 1L; "YEAR" -> 3L; else -> 2L }
        val rule = ruleVersions.firstOrNull { it.asJsonObject.id() == id }?.asJsonObject ?: throw ApiFailure(404, "Règles inconnues")
        val config = JsonParser.parseString(rule.required("configurationJson")).asJsonObject
        val crypto = symbol.startsWith("BTC")
        val macd = config.decimal("technicalMax")!! * BigDecimal("0.4")
        val fund = if (crypto) BigDecimal.ZERO else config.decimal("fundamentalMax")!! * BigDecimal("-0.3")
        val info = config.decimal("informationMax")!! * BigDecimal("-0.35")
        val score = BigDecimal(50) + macd + fund + info
        val previous = snapshots.lastOrNull { it.text("symbol") == symbol && it.text("horizon") == horizon }
        val factors = JsonArray().apply {
            add(obj("key" to "MACD", "category" to "TECHNICAL", "points" to macd, "status" to "AVAILABLE", "explanation" to "Exemple fictif : le MACD contribue positivement à ce score.", "evidence" to obj("timeframe" to if (horizon == "DAY") "15m" else if (horizon == "MONTH") "1h" else "1d")))
            add(obj("key" to "NET_MARGIN", "category" to "FUNDAMENTAL", "points" to fund, "status" to if (crypto) "NOT_APPLICABLE" else "AVAILABLE", "explanation" to if (crypto) "La marge nette ne s’applique pas à cette crypto." else "Exemple fictif : la marge nette passe de 18 % à 14 %, ce qui réduit la composante financière.", "evidence" to obj("previousMargin" to 18, "currentMargin" to 14, "demo" to true)))
            add(obj("key" to "DEMO_EVENT", "category" to "INFORMATION", "points" to info, "status" to "AVAILABLE", "explanation" to "Exemple fictif : un projet de droits de douane augmente les coûts potentiels. Aucune publication réelle n’est utilisée.", "evidence" to obj("originalUrl" to "https://example.com/bourse-ai/demo", "confirmation" to "DEMO_ONLY")))
            add(obj("key" to "SEASONALITY", "category" to "SEASONALITY", "points" to 0, "status" to "EXPLORATORY_ONLY", "explanation" to "La saisonnalité reste descriptive et ne modifie pas le score.", "evidence" to obj("usableForScoring" to false)))
        }
        val changes = JsonObject()
        if (previous != null) factors.forEach { f -> val factor = f.asJsonObject; val before = previous.list("factors").firstOrNull { it.text("key") == factor.text("key") }?.decimal("points") ?: BigDecimal.ZERO; changes.addProperty(factor.required("key"), factor.decimal("points")!! - before) }
        val result = obj("id" to snapshots.size + 100L, "symbol" to symbol, "horizon" to horizon, "rulesetId" to id, "method" to "DEMO_ONLY", "asOf" to "2026-11-20T06:00:00Z", "score" to score, "coverageScore" to 100, "status" to "DEMO_REVIEW_ONLY", "quoteQuality" to obj("status" to "AT_LAST_CLOSE", "observedAt" to "2026-11-19T21:00:00Z"), "factors" to factors, "previousSnapshotId" to previous?.id(), "previousScore" to previous?.decimal("score"), "scoreChange" to previous?.decimal("score")?.let { score - it }, "contributionChanges" to changes, "rulesetChanged" to (previous != null && previous.id("rulesetId") != id))
        snapshots.add(result.deepCopy()); return result
    }
}
