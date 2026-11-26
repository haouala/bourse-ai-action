package lu.bourse.mobile.data

import com.google.gson.JsonElement
import java.math.BigDecimal

/**
 * The chart contracts below match ForecastChartController, not the pre-existing
 * intraday /charts/{symbol} endpoint. Never synthesize a missing point.
 */
data class DatePrice(val date: String, val close: BigDecimal)
data class HistoricalResearchChart(val symbol: String, val currency: String, val points: List<DatePrice>)
data class ScenarioPrice(val horizon: Int, val date: String, val low: BigDecimal, val central: BigDecimal, val high: BigDecimal, val holdout: Int)
data class ScenarioResearchChart(
    val currency: String, val reference: BigDecimal?, val referenceTime: String?,
    val scenarios: List<ScenarioPrice>, val unavailable: List<Int>,
    val method: String?, val cautions: List<String>
)
data class TrackedPrice(
    val horizon: Int, val issuedAt: String, val expected: BigDecimal,
    val actual: BigDecimal?, val errorPct: BigDecimal?, val status: String,
    val model: String
)
data class PredictionResearchChart(val points: List<TrackedPrice>)
data class ModelRunChart(
    val horizon: Int, val model: String, val trained: String, val accuracy: BigDecimal,
    val baseline: BigDecimal, val count: Int
)
data class ModelResearchChart(val points: List<ModelRunChart>)

private fun com.google.gson.JsonObject.money(name: String): BigDecimal =
    decimal(name) ?: throw ContractFailure("Champ financier absent : $name")

/** The API returns a real observed price at every point; dates must be monotonic. */
fun historicalResearchChart(value: JsonElement): HistoricalResearchChart {
    val row = value.objectValue()
    val points = row.list("points").map {
        DatePrice(it.required("timestamp"), it.money("close").also { p ->
            if (p.signum() <= 0) throw ContractFailure("Cours invalide")
        })
    }
    if (points.zipWithNext().any { (a,b) -> a.date >= b.date })
        throw ContractFailure("Dates historiques non croissantes")
    return HistoricalResearchChart(row.required("symbol"), row.required("currency"), points)
}

/** Show only the four actual scenario endpoints, with missing horizons explicit. */
fun scenarioResearchChart(value: JsonElement): ScenarioResearchChart {
    val row = value.objectValue()
    val scenarios = row.list("points").map { p ->
        val low = p.money("pessimistic"); val middle = p.money("central"); val high = p.money("optimistic")
        if (low > middle || middle > high || low.signum() <= 0) throw ContractFailure("Scénarios incohérents")
        ScenarioPrice(
            p.id("horizonDays")?.toInt() ?: throw ContractFailure("Horizon absent"),
            p.required("targetDate"), low, middle, high,
            p.id("holdoutExamples")?.toInt() ?: 0
        )
    }.sortedBy { it.horizon }
    if (scenarios.map { it.horizon }.distinct().size != scenarios.size) throw ContractFailure("Horizons dupliqués")
    return ScenarioResearchChart(
        row.required("currency"), row.decimal("referencePrice"), row.text("referenceTime"),
        scenarios, row.list("unavailable").mapNotNull { it.id("horizonDays")?.toInt() },
        row.text("method"), row.field("cautions")?.asJsonArray?.map { it.asString }.orEmpty()
    )
}

/** Pending forecasts have null realizedPrice; null is not plotted as zero. */
fun predictionResearchChart(value: JsonElement): PredictionResearchChart {
    val rows = value.objectValue().list("points").map { p ->
        val status = p.required("status")
        val actual = p.decimal("realizedPrice")
        if (status != "EVALUATED" && actual != null)
            throw ContractFailure("Prix observé non fiable pour une prévision en attente")
        TrackedPrice(
            p.id("horizonDays")?.toInt() ?: throw ContractFailure("Horizon absent"),
            p.required("issuedAt"), p.money("predictedPrice"), actual,
            p.decimal("absoluteErrorPct"), status, p.required("modelName") + " / " + p.required("modelVersion")
        )
    }
    return PredictionResearchChart(rows)
}

/** These are experiment metrics, not live win rates or price predictions. */
fun modelResearchChart(value: JsonElement): ModelResearchChart =
    ModelResearchChart(value.objectValue().list("points").map { p ->
        val accuracy = p.money("directionAccuracyPct")
        val baseline = p.money("baselineAccuracyPct")
        if (accuracy < BigDecimal.ZERO || accuracy > BigDecimal("100") ||
            baseline < BigDecimal.ZERO || baseline > BigDecimal("100"))
            throw ContractFailure("Précision ML hors limites")
        ModelRunChart(
            p.id("horizonSessions")?.toInt() ?: throw ContractFailure("Horizon ML absent"),
            p.required("modelVersion"), p.required("trainedAt"), accuracy, baseline,
            p.id("testSamples")?.toInt() ?: 0
        )
    })
