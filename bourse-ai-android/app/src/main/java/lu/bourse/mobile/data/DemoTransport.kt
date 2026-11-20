package lu.bourse.mobile.data

import com.google.gson.*

/** Local fixtures only. They never call or write to a server. */
class DemoTransport : Transport {
    private val watchlist = JsonArray().apply {
        add(watchRow(1, "AAPL", "Apple", "STOCK", "AAPL"))
        add(watchRow(2, "NVDA", "NVIDIA", "STOCK", "NVDA"))
        add(watchRow(3, "BTC-USD", "Bitcoin", "CRYPTO", "BTC/USD"))
    }
    private fun watchRow(id: Long, symbol: String, name: String, type: String, provider: String): JsonObject = obj("asset_id" to id, "symbol" to symbol, "name" to name, "asset_type" to type, "currency" to "USD", "provider_symbol" to provider, "region" to if (type == "CRYPTO") "GLOBAL" else "US", "market_zone" to if (type == "CRYPTO") "UTC" else "America/New_York", "session_open" to if (type == "CRYPTO") "00:00" else "09:30", "session_close" to if (type == "CRYPTO") "00:00" else "16:00", "enabled" to true)
    override suspend fun request(method: String, path: List<String>, query: Map<String, String>, body: JsonObject?): JsonElement {
        if (path == listOf("actuator", "health")) return obj("status" to "UP", "mode" to "DEMO")
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
