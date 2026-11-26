package lu.bourse.mobile.data

import com.google.gson.*
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant

/** Local fixtures only. They never call or write to a server. */
class DemoTransport : Transport {
    private val research = DemoResearch()
    private val analysis = DemoAnalysis()
    private val information = DemoInformation()
    private val portfolio = DemoPortfolio()
    private val watchlist = JsonArray().apply {
        add(watchRow(1, "AAPL", "Apple", "STOCK", "AAPL"))
        add(watchRow(2, "NVDA", "NVIDIA", "STOCK", "NVDA"))
        add(watchRow(3, "BTC-USD", "Bitcoin", "CRYPTO", "BTC/USD"))
    }
    private fun watchRow(id: Long, symbol: String, name: String, type: String, provider: String): JsonObject = obj("asset_id" to id, "symbol" to symbol, "name" to name, "asset_type" to type, "currency" to "USD", "provider_symbol" to provider, "region" to if (type == "CRYPTO") "GLOBAL" else "US", "market_zone" to if (type == "CRYPTO") "UTC" else "America/New_York", "session_open" to if (type == "CRYPTO") "00:00" else "09:30", "session_close" to if (type == "CRYPTO") "00:00" else "16:00", "enabled" to true)
    private fun chart(symbol: String, timeframe: String): JsonObject {
        if (watchlist.none { it.asJsonObject.text("symbol") == symbol }) throw ApiFailure(404, "Actif inconnu")
        val seconds = when (timeframe) { "15m" -> 900L; "30m" -> 1800L; "1h" -> 3600L; "1d" -> 86400L; else -> throw ApiFailure(400, "Intervalle invalide") }
        val base = when(symbol) { "NVDA" -> 145.0; "BTC-USD" -> 65000.0; else -> 224.0 }
        val anchor = Instant.parse(if (timeframe == "1d") "2026-11-20T00:00:00Z" else "2026-11-19T21:00:00Z")
        val bars = JsonArray()
        repeat(48) { i ->
            val price = BigDecimal.valueOf(base * (1 + i * 0.0008 + kotlin.math.sin(i * 0.4) * 0.009)).setScale(4, RoundingMode.HALF_UP)
            val open = price.multiply(BigDecimal("0.999")); val closesAt = anchor.minusSeconds((47 - i) * seconds)
            bars.add(obj("openTime" to closesAt.minusSeconds(seconds), "closeTime" to closesAt, "open" to open, "high" to price.multiply(BigDecimal("1.003")), "low" to price.multiply(BigDecimal("0.997")), "close" to price, "volume" to 100000, "provider" to "DEMO_SYNTHETIC"))
        }
        // Explicit fictional hammer example, scaled to the asset; no live detection is simulated.
        listOf(1.09, 1.07, 1.05, 1.01).forEachIndexed { n, multiplier ->
            val bar = bars[43 + n].asJsonObject
            val close = BigDecimal.valueOf(base * multiplier)
            bar.addProperty("open", close + BigDecimal.valueOf(base * .01))
            bar.addProperty("high", close + BigDecimal.valueOf(base * .02))
            bar.addProperty("low", close - BigDecimal.valueOf(base * .01))
            bar.addProperty("close", close)
        }
        val last = bars[47].asJsonObject
        last.addProperty("open", base); last.addProperty("close", base * 1.01)
        last.addProperty("high", base * 1.012); last.addProperty("low", base * .96)
        last.addProperty("volume", 200000)
        val patterns = obj("method" to "OHLC_PATTERNS_V1", "status" to "AVAILABLE", "inspectedCandles" to 12,
            "patterns" to JsonArray().apply { add(obj("code" to "HAMMER", "direction" to "BULLISH",
                "openTime" to last.required("openTime"), "closeTime" to last.required("closeTime"),
                "confirmation" to "AWAITING_NEXT_BAR", "volumeRatio" to 2, "volumeStatus" to "ABOVE_USUAL")) })
        return obj("symbol" to symbol, "timeframe" to timeframe, "currency" to "USD", "candles" to bars, "latestIndicators" to obj("sma20" to base * 1.028, "rsi14" to 56.2, "macd" to 0.84, "macdSignal" to 0.65, "macdHistogram" to 0.19), "warning" to "FICTITIOUS_DEMO_DATA", "patternAnalysis" to patterns)
    }
    override suspend fun request(method: String, path: List<String>, query: Map<String, String>, body: JsonObject?): JsonElement {
        research.request(method, path, query)?.let { return it }
        analysis.request(method, path, query, body)?.let { return it }
        information.request(method, path, query, body)?.let { return it }
        portfolio.request(method, path, body) { symbol -> watchlist.firstOrNull { it.asJsonObject.text("symbol") == symbol }?.asJsonObject }?.let { return it }
        if (path == listOf("actuator", "health")) return obj("status" to "UP", "mode" to "DEMO")
        if (method == "GET" && path.size == 4 && path.take(3) == listOf("api", "v1", "charts")) return chart(path[3], query["timeframe"] ?: "15m")
        if (method == "GET" && path.size == 6 && path.take(4) == listOf("api", "v1", "calendars", "assets") && path.last() == "state") return obj("calendarCode" to "DEMO_US", "marketState" to "CLOSED", "expectedClose" to "2026-11-19T21:00:00Z", "nextOpen" to "2026-11-20T14:30:00Z", "reason" to "Exemple local fictif, indépendant du calendrier réel")
        if (method == "GET" && path == listOf("api", "v1", "watchlist", "suggestions")) {
            val term = query["q"].orEmpty().trim()
            return JsonArray().apply {
                (watchlist.map { it.asJsonObject } + listOf(
                    watchRow(100, "MSFT", "Microsoft", "STOCK", "MSFT"),
                    watchRow(101, "AMZN", "Amazon", "STOCK", "AMZN"),
                    watchRow(102, "TSLA", "Tesla", "STOCK", "TSLA")
                )).distinctBy { it.text("symbol") }.filter {
                    it.text("symbol").orEmpty().contains(term, true) || it.text("name").orEmpty().contains(term, true)
                }.forEach { add(it.deepCopy()) }
            }
        }
        if (path == listOf("api", "v1", "watchlist")) {
            if (method == "GET") return watchlist.deepCopy()
            if (method == "PUT" && body != null) {
                val existing = watchlist.map { it.asJsonObject }.firstOrNull { it.text("symbol") == body.text("symbol") }
                if (existing != null && (existing.text("assetType") != body.text("assetType") || existing.text("currency") != body.text("currency"))) throw ApiFailure(409, "Type ou devise déjà définis différemment")
                val cap = if (body.text("assetType") == "CRYPTO") 30 else 100
                if (body.flag("enabled") == true && existing?.flag("enabled") != true && watchlist.count { it.asJsonObject.text("assetType") == body.text("assetType") && it.asJsonObject.flag("enabled") == true } >= cap) throw ApiFailure(409, "Capacité de suivi atteinte")
                val row = body.deepCopy(); val id = existing?.id("assetId") ?: (watchlist.size() + 1).toLong(); row.addProperty("asset_id", id)
                if (existing != null) watchlist.remove(existing)
                watchlist.add(row)
                return obj("assetId" to id)
            }
        }
        throw ApiFailure(404, "Module de démonstration pas encore disponible")
    }
}
