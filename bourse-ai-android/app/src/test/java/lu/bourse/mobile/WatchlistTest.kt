package lu.bourse.mobile

import kotlinx.coroutines.test.runTest
import lu.bourse.mobile.data.*
import org.junit.Assert.*
import org.junit.Test

class WatchlistTest {
    private fun values(symbol: String = "AMD", type: String = "STOCK") = mapOf("symbol" to symbol, "name" to "Example", "type" to type, "currency" to "USD", "provider" to symbol, "region" to "US", "zone" to "America/New_York", "opens" to "09:30", "closes" to "16:00")
    @Test fun demonstrationProvidesTypedSearchableStocksAndCrypto() = runTest {
        val all = assets(DemoTransport().request("GET", api("watchlist")))
        assertEquals(3, all.size); assertEquals(listOf("NVDA"), filterAssets(all, "nvidia", "ALL").map { it.symbol })
        assertEquals(listOf("BTC-USD"), filterAssets(all, "", "CRYPTO").map { it.symbol })
        assertTrue(filterAssets(all, "bitcoin", "STOCK").isEmpty())
    }
    @Test fun addingAndPausingAreIdempotentAndPersistWithinDemoSession() = runTest {
        val demo = DemoTransport(); val body = watchPayload(values())
        val first = demo.request("PUT", api("watchlist"), body = body).objectValue().id("assetId")
        val second = demo.request("PUT", api("watchlist"), body = body).objectValue().id("assetId")
        assertEquals(first, second)
        val added = assets(demo.request("GET", api("watchlist"))).first { it.symbol == "AMD" }
        demo.request("PUT", api("watchlist"), body = added.payload(false))
        assertFalse(assets(demo.request("GET", api("watchlist"))).first { it.symbol == "AMD" }.enabled)
    }
    @Test fun cryptoUsesContinuousCalendarAndValidatesProviderPair() {
        val body = watchPayload(values("BTC-USD", "CRYPTO") + ("provider" to "BTC/USD"))
        assertEquals("UTC", body.text("marketZone")); assertEquals("00:00", body.text("sessionClose")); assertEquals("GLOBAL", body.text("region"))
        assertThrows(IllegalArgumentException::class.java) { watchPayload(values() + ("provider" to "bad token ?")) }
        assertThrows(IllegalArgumentException::class.java) { watchPayload(values() + ("closes" to "08:00")) }
    }
    @Test fun demoEnforcesStockCapacity() = runTest {
        val demo = DemoTransport()
        repeat(98) { demo.request("PUT", api("watchlist"), body = watchPayload(values("STOCK$it"))) }
        val failure = try { demo.request("PUT", api("watchlist"), body = watchPayload(values("OVER"))); null } catch (e: ApiFailure) { e }
        assertEquals(409, failure?.status)
        assertEquals(100, assets(demo.request("GET", api("watchlist"))).count { it.type == "STOCK" && it.enabled })
    }
    @Test fun malformedWatchlistIsRejectedExplicitly() {
        assertThrows(ContractFailure::class.java) { assets(com.google.gson.JsonArray().apply { add(obj("symbol" to "AAPL")) }) }
    }
}
