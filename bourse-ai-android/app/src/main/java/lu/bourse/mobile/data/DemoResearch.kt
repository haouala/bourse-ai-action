package lu.bourse.mobile.data

import com.google.gson.*
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.LocalDate
import kotlin.math.sin

/**
 * Test-only contract adapter for the new Java research APIs.
 * It is never used by the HTTP transport and cannot contact a market provider.
 */
class DemoResearch {
    private val supported = setOf("NVDA", "AAPL", "BTC-USD")
    private fun base(symbol: String): Double = when (symbol) {
        "NVDA" -> 145.0
        "BTC-USD" -> 65000.0
        else -> 224.0
    }
    private fun price(value: Double): BigDecimal = BigDecimal.valueOf(value)
        .setScale(4, RoundingMode.HALF_UP)
    private fun date(i: Int): String = LocalDate.parse("2026-11-20").minusDays(i.toLong()).toString()
    private fun requireSymbol(symbol: String) {
        if (symbol !in supported) throw ApiFailure(404, "Actif absent du catalogue fictif")
    }

    fun request(method: String, path: List<String>, query: Map<String, String>): JsonElement? {
        if (method != "GET" || path.size < 4 || path.take(2) != listOf("api", "v1")) return null
        if (path.size == 5 && path[2] == "ml" && path[3] == "market") return JsonArray()
        val symbol = path[3]
        val group = path[2]
        if (group !in setOf("charts", "scoring", "regimes", "ai-bottlenecks", "technical-analysis")) return null
        requireSymbol(symbol)
        val value = base(symbol)
        if (group == "technical-analysis" && path.size == 4) return demoTechnical(symbol, query["timeframe"] ?: "1d", value)
        if (group == "charts" && path.size == 5) return when (path[4]) {
            "history" -> {
                val observations = JsonArray()
                repeat(120) { n ->
                    val i = 119 - n
                    val sample = price(value * (0.87 + .0011 * n + .016 * sin(n * .19)))
                    observations.add(obj("timestamp" to date(i) + "T00:00:00Z", "close" to sample))
                }
                obj("symbol" to symbol, "currency" to "USD",
                    "seriesType" to "OBSERVED_DAILY_CLOSE_DEMO", "points" to observations,
                    "disclaimer" to "Données 100% fictives")
            }
            "scenarios" -> {
                val points = JsonArray()
                for (h in listOf(7, 30, 90, 180)) {
                    val scale = kotlin.math.sqrt(h / 30.0)
                    points.add(obj(
                        "horizonDays" to h,
                        "targetDate" to LocalDate.parse("2026-11-20").plusDays(h.toLong()).toString(),
                        "pessimistic" to price(value * (1 - .08 * scale)),
                        "central" to price(value * (1 + .035 * scale)),
                        "optimistic" to price(value * (1 + .12 * scale)),
                        "status" to "RESEARCH_ONLY",
                        "trainingExamples" to 570,
                        "holdoutExamples" to 140,
                        "holdoutErrorPct" to 6.2,
                        "noChangeErrorPct" to 7.0,
                        "bandCoverage" to .67
                    ))
                }
                obj("symbol" to symbol, "currency" to "USD",
                    "referencePrice" to price(value),
                    "referenceTime" to "2026-11-20T00:00:00Z",
                    "method" to "FAKE_DEMO_QUANTILES", "version" to "DEMO",
                    "points" to points, "unavailable" to JsonArray(),
                    "cautions" to JsonArray().apply { add("Simulation purement fictive, non investissable") })
            }
            "predictions" -> {
                val points = JsonArray()
                for (i in 0..4) {
                    val predicted = price(value * (1 + .01 * i))
                    val pending = i == 4
                    points.add(obj(
                        "id" to (i + 1),
                        "horizonDays" to 30,
                        "issuedAt" to date(100 - i * 25) + "T00:00:00Z",
                        "targetTime" to date(70 - i * 25) + "T00:00:00Z",
                        "observedAt" to if (pending) null else date(70 - i * 25) + "T00:00:00Z",
                        "modelName" to "DEMO_EMPIRICAL",
                        "modelVersion" to "DEMO",
                        "referencePrice" to price(value * .95),
                        "predictedPrice" to predicted,
                        "realizedPrice" to if (pending) null else price(value * (1 + .011 * i)),
                        "absoluteErrorPct" to if (pending) null else .4 + i * .1,
                        "directionCorrect" to if (pending) null else true,
                        "status" to if (pending) "PENDING" else "EVALUATED"
                    ))
                }
                obj("symbol" to symbol, "count" to points.size(), "points" to points,
                    "disclaimer" to "Historique de prédictions inventé pour la démo")
            }
            "ml" -> {
                val points = JsonArray()
                repeat(5) { n ->
                    points.add(obj(
                        "runId" to n + 1,
                        "trainedAt" to date(70 - n * 12) + "T00:00:00Z",
                        "horizonSessions" to 10,
                        "modelVersion" to "DEMO",
                        "testSamples" to 80,
                        "directionAccuracyPct" to (53 + n * 2),
                        "baselineAccuracyPct" to 50,
                        "brierScore" to .22,
                        "baselineBrier" to .25,
                        "status" to "RESEARCH_ONLY"
                    ))
                }
                obj("symbol" to symbol, "count" to points.size(), "points" to points,
                    "disclaimer" to "Modèle ML fictif")
            }
            else -> null
        }
        if (group == "scoring" && path.size == 4) return obj(
            "symbol" to symbol,
            "horizonDays" to (query["horizonDays"]?.toIntOrNull() ?: 30),
            "opportunityScore" to 73,
            "riskScore" to 46,
            "reliabilityScore" to null,
            "status" to "INSUFFICIENT_RELIABILITY_EVIDENCE",
            "evaluatedForecasts" to 4,
            "scoreVersion" to "TECHNICAL_SCORE_V2",
            "technicalOpportunityDelta" to 2,
            "technicalRiskDelta" to 2,
            "technicalAnalysis" to demoTechnical(symbol, "1d", value),
            "disclaimer" to "Scores 100% fictifs, sans probabilité de gain"
        )
        if (group == "regimes" && path.size == 4) return obj(
            "symbol" to symbol, "asOf" to "2026-11-20T00:00:00Z",
            "signal" to obj("regime" to "BULL", "volatilityLevel" to "ELEVATED",
                "annualizedVolatilityPct" to 24.1, "drawdownPct" to -7.3),
            "status" to "RESEARCH_ONLY"
        )
        if (group == "ai-bottlenecks" && path.size == 4) return obj(
            "symbol" to symbol,
            "assessment" to obj("score" to null, "status" to "INSUFFICIENT_EVIDENCE"),
            "evidence" to JsonArray(),
            "disclaimer" to "Démo : aucune source réelle ni pénurie vérifiée"
        )
        return null
    }
}
