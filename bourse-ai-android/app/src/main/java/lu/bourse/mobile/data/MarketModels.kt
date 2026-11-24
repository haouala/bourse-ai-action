package lu.bourse.mobile.data

import com.google.gson.JsonElement
import java.math.BigDecimal

data class MarketModelResult(
    val horizon: String,
    val model: String,
    val asOf: String,
    val estimate: BigDecimal?,
    val brier: BigDecimal?,
    val constantBrier: BigDecimal?,
    val testCount: Long
)

fun marketModelResults(payload: JsonElement): List<MarketModelResult> = payload.rows().map { row ->
    val estimate = row.decimal("estimate")?.takeIf {
        row.text("status") == "RESEARCH_ONLY" && it in BigDecimal.ZERO..BigDecimal.ONE
    }
    MarketModelResult(row.required("horizon"), row.required("model"), row.required("asOf"),
        estimate, row.decimal("brier"), row.decimal("constantBrier"), row.id("testCount") ?: 0)
}
