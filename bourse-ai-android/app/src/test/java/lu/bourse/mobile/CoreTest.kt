@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
package lu.bourse.mobile

import com.google.gson.JsonNull
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import lu.bourse.mobile.data.*
import okhttp3.mockwebserver.*
import org.junit.*
import org.junit.Assert.*
import java.math.BigDecimal
import java.util.concurrent.TimeUnit

class MemoryConfig(var value: ConnectionConfig = ConnectionConfig()) : ConfigStorage {
    override fun read() = value
    override fun save(config: ConnectionConfig) { value = config }
}

class CoreTest {
    @Test fun configurationRejectsCredentialsAndProductionHttp() {
        assertThrows(IllegalArgumentException::class.java) { ConnectionConfig(server = "https://user:secret@example.com").validated(true) }
        assertThrows(IllegalArgumentException::class.java) { ConnectionConfig(server = "http://example.com").validated(false) }
        assertThrows(IllegalArgumentException::class.java) { ConnectionConfig(server = "file:///tmp/data").validated(true) }
        assertEquals("https://example.com", ConnectionConfig(server = "https://example.com/").validated(false).server)
    }
    @Test fun optionalFieldsNeverTurnMissingMoneyIntoZero() {
        val row = obj("remaining_loss_budget" to null, "cash" to BigDecimal("123.45678901"), "asset_id" to 12)
        assertNull(row.decimal("remainingLossBudget"))
        assertEquals(BigDecimal("123.45678901"), row.decimal("cash"))
        assertEquals(12L, row.id("assetId"))
        assertThrows(ContractFailure::class.java) { row.rows() }
    }
    @Test fun amountsSymbolsAndEvidenceValidateWithoutRounding() {
        assertEquals(BigDecimal("12.12345678"), positiveDecimal("12,12345678", "Prix"))
        for (v in listOf("-1", "0", "NaN", "1.123456789")) assertThrows(IllegalArgumentException::class.java) { positiveDecimal(v, "Prix") }
        assertEquals("BTC-USD", normalizedSymbol(" btc-usd "))
        assertThrows(IllegalArgumentException::class.java) { normalizedSymbol("AAPL/../../") }
        assertFalse(httpsEvidence("javascript:alert(1)")); assertFalse(httpsEvidence("http://example.com")); assertTrue(httpsEvidence("https://example.com/news"))
    }
    @Test fun httpUsesEncodedSegmentsAndExactJsonNumbers() = runTest {
        val server = MockWebServer(); server.start()
        try {
            server.enqueue(MockResponse().setBody("{\"cash\":123.45678901}"))
            val transport = HttpTransport(ConnectionConfig(false, server.url("/").toString()), true)
            val result = transport.request("POST", listOf("api", "v1", "A/B"), mapOf("horizon" to "YEAR"), obj("price" to BigDecimal("0.12345678")))
            val request = server.takeRequest(2, TimeUnit.SECONDS)!!
            assertEquals("/api/v1/A%2FB?horizon=YEAR", request.path)
            assertTrue(request.body.readUtf8().contains("0.12345678"))
            assertEquals(BigDecimal("123.45678901"), result.objectValue().decimal("cash"))
        } finally { server.shutdown() }
    }
    @Test fun failedRequestsDoNotRetryOrExposeServerBodies() = runTest {
        val server = MockWebServer(); server.start()
        try {
            server.enqueue(MockResponse().setResponseCode(429).setBody("secret-token"))
            val transport = HttpTransport(ConnectionConfig(false, server.url("/").toString()), true)
            val failure = try { transport.request("GET", listOf("api")); null } catch (e: ApiFailure) { e }
            assertEquals(429, failure?.status); assertFalse(failure!!.message!!.contains("secret-token")); assertEquals(1, server.requestCount)
            server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "https://example.com"))
            val redirect = try { transport.request("GET", listOf("api")); null } catch (e: ApiFailure) { e }
            assertEquals(302, redirect?.status)
        } finally { server.shutdown() }
    }
    @Test fun configSwitchCancelsOldLoadsAndSeparatesDemo() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val gate = CompletableDeferred<Unit>()
            val storage = MemoryConfig()
            val store = AppStore(storage) { config -> object : Transport {
                override suspend fun request(method: String, path: List<String>, query: Map<String,String>, body: com.google.gson.JsonObject?): com.google.gson.JsonElement {
                    if (config.demo) gate.await()
                    return obj("mode" to if (config.demo) "old" else "live")
                }
            } }
            store.load("list", listOf("api")); runCurrent()
            store.configure(ConnectionConfig(false, "https://example.com", 7))
            gate.complete(Unit); store.load("list", listOf("api")); advanceUntilIdle()
            assertEquals("live", store.resources.value["list"]?.value?.objectValue()?.text("mode"))
            assertFalse(storage.value.demo); assertEquals(7L, storage.value.portfolioId)
        } finally { Dispatchers.resetMain() }
    }
    @Test fun mutationsCannotBeSubmittedTwiceWhilePending() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            var calls = 0; val gate = CompletableDeferred<Unit>()
            val store = AppStore(MemoryConfig()) { object : Transport {
                override suspend fun request(method: String, path: List<String>, query: Map<String,String>, body: com.google.gson.JsonObject?): com.google.gson.JsonElement { calls++; gate.await(); return JsonNull.INSTANCE }
            } }
            store.mutate("buy", listOf("buy")); store.mutate("buy", listOf("buy")); runCurrent(); assertEquals(1, calls)
            gate.complete(Unit); advanceUntilIdle(); assertNotNull(store.resources.value["buy"]?.value)
        } finally { Dispatchers.resetMain() }
    }
}
