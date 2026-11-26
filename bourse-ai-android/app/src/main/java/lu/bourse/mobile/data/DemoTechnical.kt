package lu.bourse.mobile.data

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import java.time.Instant

/** Clearly synthetic examples for UI exploration; never used by HttpTransport. */
fun demoTechnical(symbol: String, timeframe: String, price: Double): JsonObject {
    val end = Instant.parse("2026-11-20T00:00:00Z")
    val points = JsonArray()
    listOf(.8, .95, .87, 1.08, 1.0, 1.17).forEachIndexed { index, value ->
        val at = end.minusSeconds((23L - index * 4) * 86400)
        points.add(obj("label" to index, "time" to at, "availableAt" to at.plusSeconds(2 * 86400), "price" to value * price))
    }
    return obj("symbol" to symbol, "timeframe" to timeframe, "currency" to "USD", "asOf" to end.plusSeconds(86400),
        "referenceTime" to end, "status" to "DEMO_ONLY", "usable" to true,
        "analysis" to obj("method" to "TECHNICAL_FEATURES_V1", "continuousCandles" to 120,
            "features" to obj("hammer" to 1, "doji" to 0, "engulf_bull" to 0, "engulf_bear" to 0,
                "confirmed_bull" to 0, "confirmed_bear" to 0, "relative_volume_20" to 2,
                "volume_available" to 1, "triple_bottom" to 0, "triple_top" to 0,
                "elliott_impulse" to 1, "elliott_abc" to 0, "donchian_breakout" to 0,
                "atr14_pct" to .035, "bollinger_position" to .7, "bollinger_width" to .12,
                "sma20_50_gap" to .05, "ohlc_available" to 1),
            "signals" to JsonArray().apply { add(obj("code" to "ELLIOTT_IMPULSE_UP", "direction" to "NEUTRAL", "status" to "EXPLORATORY",
                "availableAt" to end.minusSeconds(86400), "points" to points)) },
            "opportunityDelta" to 4, "riskDelta" to 3,
            "contributions" to JsonArray().apply {
                add(obj("code" to "CANDLE_PATTERNS", "opportunity" to 2, "risk" to 0, "explanation" to "Exemple fictif de marteau : +2 points bruts."))
                add(obj("code" to "SMA_20_50", "opportunity" to 2, "risk" to 1, "explanation" to "Exemple fictif de tendance : +2 points d’opportunité."))
                add(obj("code" to "ATR_14", "opportunity" to 0, "risk" to 2, "explanation" to "Exemple fictif d’amplitude élevée : +2 points de risque."))
            }))
}
