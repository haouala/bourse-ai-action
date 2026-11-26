package lu.bourse.mobile

import com.google.gson.JsonArray
import kotlinx.coroutines.runBlocking
import lu.bourse.mobile.data.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.math.BigDecimal
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import java.io.File

/** Opt-in: actual Spring Boot V8 HTTP service, isolated H2, synthetic data only. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class BackendContractTest {
    private fun eq(expected: String, value: BigDecimal?) {
        assertNotNull(value); assertEquals(0, BigDecimal(expected).compareTo(value))
    }
    @Test fun realBackendSupportsMobileReadsWritesAndIdempotentOperations() = runBlocking {
        val url = requireNotNull(System.getProperty("bourse.backendUrl"))
        val http = HttpTransport(ConnectionConfig(false, url), true)
        val now = Instant.now().minusSeconds(3).truncatedTo(ChronoUnit.MICROS)
        val symbol = "TEST-" + UUID.randomUUID().toString().take(8).uppercase()
        val entry = watchPayload(mapOf("symbol" to symbol, "name" to "Synthetic contract fixture",
            "type" to "CRYPTO", "currency" to "EUR", "provider" to "TEST/EUR", "region" to "GLOBAL",
            "zone" to "UTC", "opens" to "00:00", "closes" to "00:00")).apply { addProperty("enabled", false) }
        assertEquals("UP", http.request("GET", listOf("actuator", "health")).objectValue().text("status"))
        http.request("PUT", api("watchlist"), body = entry)
        assertEquals("EUR", assets(http.request("GET", api("watchlist"))).single { it.symbol == symbol }.currency)

        val end = Instant.ofEpochSecond(now.epochSecond / 900 * 900)
        val bars = JsonArray().apply { repeat(40) { index ->
            add(obj("openTime" to end.minusSeconds((40L - index) * 900),
                "open" to 100, "high" to 101, "low" to 99, "close" to 100, "volume" to 100))
        } }
        http.request("POST", api("history", "import"), body = obj("symbol" to symbol,
            "name" to "Synthetic contract fixture", "assetType" to "CRYPTO", "currency" to "EUR",
            "timeframe" to "15m", "provider" to "TEST_MOBILE", "bars" to bars))
        val chart = chartData(http.request("GET", api("charts", symbol), mapOf("timeframe" to "15m", "limit" to "250")))
        assertEquals(40, chart.candles.size); assertNotNull(chart.indicators)
        assertNotNull(chart.patterns)
        assertEquals(12, chart.patterns!!.patterns.size)
        assertTrue(chart.patterns.patterns.all { it.code == "DOJI" && it.confirmation == "NOT_APPLICABLE" })
        val technical=http.request("GET",api("technical-analysis",symbol),mapOf("timeframe" to "15m")).objectValue()
        assertEquals("TECHNICAL_FEATURES_V1",technical.child("analysis")!!.text("method"))
        assertEquals(BigDecimal("1.0"),technical.child("analysis")!!.child("features")!!.decimal("doji"))
        assertEquals("OPEN", http.request("GET", api("calendars", "assets", symbol, "state")).objectValue().text("marketState"))
        val seasons = http.request("GET", api("seasonality", symbol)).objectValue()
        assertFalse(seasons.flag("usableForScoring")!!)
        val rules = http.request("GET", api("explanations", "rulesets")).rows()
        assertTrue(rules.size >= 3)
        val explanation = scoreAnalysis(http.request("POST", api("explanations", symbol), mapOf("horizon" to "DAY")))
        assertEquals(explanation.id, http.request("GET", api("explanations", symbol, "history"), mapOf("horizon" to "DAY")).rows().first().id())
        assertEquals(explanation.id, scoreAnalysis(http.request("GET", api("explanations", "snapshots", explanation.id))).id)
        assertTrue(http.request("GET", api("information", "sources")).rows().size >= 1)
        assertTrue(newsItems(http.request("GET", api("information", "items"))).isEmpty())

        val portfolio = requireNotNull(http.request("POST", api("portfolios"), body = createPortfolio(
            mapOf("name" to "Mobile HTTP test", "currency" to "EUR", "capital" to "1000", "loss" to "100"))).objectValue().id())
        val trade = mapOf("symbol" to symbol, "horizon" to "SHORT", "quantity" to "2", "price" to "100", "fees" to "0", "fx" to "1", "executedAt" to now.minusSeconds(3600).toString())
        val buy = declaredTrade(trade, UUID.randomUUID().toString())
        val bought = http.request("POST", api("portfolios", portfolio, "positions"), body = buy)
        assertEquals(bought, http.request("POST", api("portfolios", portfolio, "positions"), body = buy))
        val position = positions(http.request("GET", api("portfolios", portfolio, "positions"))).single()
        val incomplete = http.request("POST", api("portfolios", portfolio, "evaluate")).objectValue()
        assertNull(incomplete.decimal("loss")); assertNull(incomplete.decimal("remainingLossBudget"))
        fun mark(price: String) = priceMark(mapOf("price" to price, "fx" to "1", "observedAt" to now.toString(),
            "fxObservedAt" to now.minusSeconds(1).toString(), "source" to "TEST_MOBILE"))
        http.request("PUT", api("portfolios", portfolio, "positions", position.id, "mark"), body = mark("95"))
        val risk = http.request("POST", api("portfolios", portfolio, "evaluate")).objectValue()
        eq("10", risk.decimal("loss")); eq("90", risk.decimal("remainingLossBudget"))
        http.request("PUT", api("portfolios", portfolio, "positions", position.id, "mark"), body = mark("1"))
        assertEquals("LOSS_LIMIT_REACHED", http.request("POST", api("portfolios", portfolio, "monitor")).objectValue().text("status"))
        val alert = alerts(http.request("GET", api("portfolios", portfolio, "alerts"))).single { it.type == "LOSS_LIMIT" }
        http.request("POST", api("portfolios", portfolio, "alerts", alert.id, "acknowledge"))
        assertEquals("ACKNOWLEDGED", alerts(http.request("GET", api("portfolios", portfolio, "alerts"))).first { it.id == alert.id }.status)
        http.request("PUT", api("portfolios", portfolio, "positions", position.id, "mark"), body = mark("95"))
        val sale = declaredTrade(trade + mapOf("side" to "SELL", "quantity" to "1", "price" to "110"), UUID.randomUUID().toString(), position)
        val sold = http.request("POST", api("portfolios", portfolio, "positions", position.id, "trades"), body = sale)
        assertEquals(sold, http.request("POST", api("portfolios", portfolio, "positions", position.id, "trades"), body = sale))
        eq("1", positions(http.request("GET", api("portfolios", portfolio, "positions"))).single().quantity)
        assertFalse(http.request("GET", api("portfolios", portfolio, "positions", position.id, "plan")).objectValue().flag("executableOrder")!!)

        val actor = requireNotNull(http.request("POST", api("social", "actors"), body = obj("displayName" to "Synthetic actor",
            "roleLabel" to "TEST", "platform" to "OTHER", "accountId" to UUID.randomUUID().toString(), "identityConfirmed" to false)).objectValue().id())
        val publication = requireNotNull(http.request("POST", api("social", "publications"), body = obj("actorId" to actor,
            "externalId" to UUID.randomUUID().toString(), "content" to "Synthetic fixture, no real statement",
            "sourceUrl" to "https://example.com/mobile-test", "publishedAt" to now)).objectValue().id())
        val event = requireNotNull(http.request("POST", api("social", "events"), body = obj("canonicalKey" to UUID.randomUUID().toString(),
            "title" to "Synthetic event", "topic" to "GENERAL", "kind" to "OPINION", "confirmation" to "UNCONFIRMED",
            "evidenceUrl" to "https://example.com/mobile-test", "publicationIds" to JsonArray().apply { add(publication) })).objectValue().id())
        http.request("PUT", api("social", "events", event, "assessment"), body = obj("symbol" to symbol,
            "direction" to "UNKNOWN", "materiality" to 50, "relevance" to 50, "novelty" to 50, "analystConfidence" to 50,
            "horizon" to "DAYS", "rationale" to "Synthetic unknown direction, no investment conclusion"))
        assertNull(http.request("GET", api("social", "events", event, "scores")).rows().single().decimal("signedScore"))
        assertTrue(http.request("GET", api("social", "actors")).rows().any { it.id() == actor })
        assertTrue(http.request("GET", api("social", "publications")).rows().any { it.id() == publication })
        val output = File("build/outputs/backend-contract.json")
        output.parentFile.mkdirs()
        output.writeText(obj("realBackend" to true, "syntheticDataOnly" to true,
            "backendCommit" to "856c5e538968515626fe370c1c1179b0ccba8875",
            "externalDataCalls" to 0, "brokerOrders" to 0, "status" to "PASSED").toString())
    }
}
