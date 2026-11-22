package lu.bourse.mobile

import com.google.gson.JsonObject
import kotlinx.coroutines.test.runTest
import lu.bourse.mobile.data.*
import org.junit.Assert.*
import org.junit.Test
import java.math.BigDecimal
import java.time.Instant

class PortfolioTest {
    private val trade = mapOf("symbol" to "NVDA", "horizon" to "MEDIUM", "quantity" to "2", "price" to "100", "fees" to "0", "fx" to "0.9", "executedAt" to "2026-11-19T10:00:00Z")
    private suspend fun book(demo: DemoTransport): Long = demo.request("POST", api("portfolios"), body = createPortfolio(mapOf("name" to "Test", "currency" to "EUR", "capital" to "1000", "loss" to "100"))).objectValue().id()!!
    private fun eq(expected: String, actual: BigDecimal?) { assertNotNull(actual); assertEquals(0, BigDecimal(expected).compareTo(actual)) }
    @Test fun timestampsPreserveOffsetsAndUseBackendMicrosecondPrecision() {
        val now = Instant.parse("2026-11-20T08:00:00Z")
        assertEquals(Instant.parse("2026-11-20T07:00:00.123456Z"), timestamp("2026-11-20T08:00:00.123456789+01:00", "Date", now))
        assertThrows(IllegalArgumentException::class.java) { timestamp("2026-11-20T09:00:00Z", "Date", now) }
        assertThrows(IllegalArgumentException::class.java) { timestamp("2026-11-20T07:00:00", "Date", now) }
    }
    @Test fun portfolioBudgetAndDecimalsRejectInvalidInputs() {
        assertThrows(IllegalArgumentException::class.java) { createPortfolio(mapOf("name" to "x", "currency" to "EUR", "capital" to "100", "loss" to "101")) }
        assertThrows(IllegalArgumentException::class.java) { positiveDecimal("1e100", "Montant") }
        assertThrows(IllegalArgumentException::class.java) { positiveDecimal("999999999999999", "Montant") }
        assertThrows(IllegalArgumentException::class.java) { declaredTrade(trade + ("fx" to ""), "id") }
    }
    @Test fun buyIsIdempotentAndFxIsAppliedToCashExactlyOnce() = runTest {
        val demo = DemoTransport(); val id = book(demo); val body = declaredTrade(trade, "buy1")
        val one = demo.request("POST", api("portfolios", id, "positions"), body = body)
        assertEquals(one, demo.request("POST", api("portfolios", id, "positions"), body = body))
        val risk = demo.request("POST", api("portfolios", id, "evaluate")).objectValue()
        eq("820", risk.decimal("cash")); assertNull(risk.decimal("loss")); assertNull(risk.decimal("remainingLossBudget"))
        assertEquals(1, positions(demo.request("GET", api("portfolios", id, "positions"))).size)
        val changed = body.deepCopy().apply { addProperty("price", 101) }
        val error = try { demo.request("POST", api("portfolios", id, "positions"), body = changed); null } catch (e: ApiFailure) { e }; assertEquals(409, error?.status)
    }
    @Test fun priceAndFxDatesStayIndependentAndCompleteTheRiskCalculation() = runTest {
        val demo = DemoTransport(); val id = book(demo)
        val p = demo.request("POST", api("portfolios", id, "positions"), body = declaredTrade(trade, "buy")).objectValue().id("positionId")!!
        val mark = priceMark(mapOf("price" to "95", "fx" to "0.9", "observedAt" to "2026-11-19T21:00:00Z", "fxObservedAt" to "2026-11-19T20:55:00Z", "source" to "MANUAL_TEST"))
        demo.request("PUT", api("portfolios", id, "positions", p, "mark"), body = mark)
        val risk = demo.request("POST", api("portfolios", id, "evaluate")).objectValue()
        eq("9", risk.decimal("loss")); eq("91", risk.decimal("remainingLossBudget"))
        val quality = risk.list("quality").single(); assertNotEquals(quality.text("observedAt"), quality.text("fxObservedAt"))
    }
    @Test fun partialSalesUpdateQuantityCostAndCashWithoutReplaying() = runTest {
        val demo = DemoTransport(); val id = book(demo)
        val p = demo.request("POST", api("portfolios", id, "positions"), body = declaredTrade(trade, "buy")).objectValue().id("positionId")!!
        val position = positions(demo.request("GET", api("portfolios", id, "positions"))).single()
        val sale = declaredTrade(trade + mapOf("side" to "SELL", "quantity" to "1", "price" to "110"), "sell", position)
        val first = demo.request("POST", api("portfolios", id, "positions", p, "trades"), body = sale)
        assertEquals(first, demo.request("POST", api("portfolios", id, "positions", p, "trades"), body = sale))
        val after = positions(demo.request("GET", api("portfolios", id, "positions"))).single(); eq("1", after.quantity); eq("90", after.cost)
        eq("919", demo.request("POST", api("portfolios", id, "evaluate")).objectValue().decimal("cash"))
        assertThrows(IllegalArgumentException::class.java) { declaredTrade(trade + mapOf("side" to "SELL", "quantity" to "3"), "over", after) }
    }
    @Test fun fullSaleClosesPositionAndPlanNeverExecutesAnOrder() = runTest {
        val demo = DemoTransport(); val id = book(demo)
        val p = demo.request("POST", api("portfolios", id, "positions"), body = declaredTrade(trade, "buy")).objectValue().id("positionId")!!
        val position = positions(demo.request("GET", api("portfolios", id, "positions"))).single()
        val plan = demo.request("GET", api("portfolios", id, "positions", p, "plan")).objectValue()
        assertFalse(plan.flag("executableOrder")!!); assertNull(plan.decimal("targetPrice"))
        demo.request("POST", api("portfolios", id, "positions", p, "trades"), body = declaredTrade(trade + ("side" to "SELL"), "close", position))
        val closed = positions(demo.request("GET", api("portfolios", id, "positions"))).single()
        assertEquals("CLOSED", closed.status); eq("0", closed.quantity)
    }
}
