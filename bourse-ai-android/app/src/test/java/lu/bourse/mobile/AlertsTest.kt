package lu.bourse.mobile

import com.google.gson.JsonArray
import kotlinx.coroutines.test.runTest
import lu.bourse.mobile.data.*
import org.junit.Assert.*
import org.junit.Test

class AlertsTest {
    private val config = ConnectionConfig(false, "https://example.com", 1)
    private fun alert(id: Long = 1, portfolio: Long = 1, status: String = "NEW", date: String = "2026-11-19T17:40:00Z") = Alert(id, portfolio, "SOCIAL_EVENT_REVIEW", "Review", status, "2026-11-19T17:30:00Z", date, "event-1")
    @Test fun notificationPolicyOnlySelectsUnseenNewAlertsOfTheChosenPortfolio() {
        val a = alert(); val rows = listOf(a, a, alert(2, status = "ACKNOWLEDGED"), alert(3, portfolio = 2))
        assertEquals(listOf(a), pendingNotifications(config, rows, emptySet()))
        assertTrue(pendingNotifications(config, rows, setOf(notificationKey(config, a))).isEmpty())
    }
    @Test fun demoAndMissingPortfolioNeverGenerateBackgroundNotifications() {
        assertTrue(pendingNotifications(config.copy(demo = true), listOf(alert()), emptySet()).isEmpty())
        assertTrue(pendingNotifications(config.copy(portfolioId = null), listOf(alert()), emptySet()).isEmpty())
    }
    @Test fun deduplicationIsScopedToTheServerAndRestoredAlertGeneration() {
        val a = alert(); val seen = setOf(notificationKey(config, a))
        assertEquals(listOf(a), pendingNotifications(config.copy(server = "https://another.example.com"), listOf(a), seen))
        val restored = a.copy(createdAt = "2026-11-20T06:00:00Z")
        assertEquals(listOf(restored), pendingNotifications(config, listOf(restored), seen))
    }
    @Test fun acknowledgementPreservesAlertHistoryAndIsIdempotent() = runTest {
        val demo = DemoTransport(); val path = api("portfolios", 1, "alerts")
        val before = alerts(demo.request("GET", path)); assertEquals("NEW", before.single().status)
        demo.request("POST", path + listOf("1", "acknowledge")); demo.request("POST", path + listOf("1", "acknowledge"))
        val after = alerts(demo.request("GET", path)); assertEquals(1, after.size); assertEquals("ACKNOWLEDGED", after.single().status)
        assertEquals(before.single().dataTime, after.single().dataTime)
    }
    @Test fun lossBudgetBreachProducesOneLatchedAlertAcrossRepeatedEvaluations() = runTest {
        val demo = DemoTransport()
        val mark = priceMark(mapOf("price" to "1", "fx" to "0.93", "observedAt" to "2026-11-19T21:00:00Z", "fxObservedAt" to "2026-11-19T20:55:00Z", "source" to "DEMO_TEST"))
        demo.request("PUT", api("portfolios", 1, "positions", 1, "mark"), body = mark)
        repeat(3) { assertEquals("LOSS_LIMIT_REACHED", demo.request("POST", api("portfolios", 1, "monitor")).objectValue().text("status")) }
        assertEquals(1, alerts(demo.request("GET", api("portfolios", 1, "alerts"))).count { it.type == "LOSS_LIMIT" })
    }
    @Test fun malformedAlertsAreExplicitlyRejected() {
        assertThrows(ContractFailure::class.java) { alerts(JsonArray().apply { add(obj("message" to "test")) }) }
    }
}
