package lu.bourse.mobile

import java.math.BigDecimal
import kotlinx.coroutines.test.runTest
import lu.bourse.mobile.data.*
import org.junit.Assert.*
import org.junit.Test

/** API contract tests for the integrated module-9 research screens. */
class ResearchChartsTest {
    @Test fun demoOffersAllFourDistinctChartContracts() = runTest {
        val t = DemoTransport()
        val history = historicalResearchChart(t.request("GET", api("charts", "NVDA", "history")))
        assertEquals(120, history.points.size)
        assertEquals("USD", history.currency)
        assertTrue(history.points.zipWithNext().all { (a, b) -> a.date < b.date })

        val scenarios = scenarioResearchChart(t.request("GET", api("charts", "NVDA", "scenarios")))
        assertEquals(listOf(7, 30, 90, 180), scenarios.scenarios.map { it.horizon })
        assertTrue(scenarios.scenarios.all { it.low <= it.central && it.central <= it.high })

        val predictions = predictionResearchChart(t.request("GET", api("charts", "NVDA", "predictions")))
        assertEquals(5, predictions.points.size)
        assertEquals("PENDING", predictions.points.last().status)
        assertNull(predictions.points.last().actual)
        assertNull(predictions.points.last().errorPct)

        val ml = modelResearchChart(t.request("GET", api("charts", "NVDA", "ml")))
        assertEquals(5, ml.points.size)
        assertTrue(ml.points.all { it.accuracy >= BigDecimal.ZERO })
    }

    @Test fun scoringAndRegimeAreExplicitlyResearchOnly() = runTest {
        val transport = DemoTransport()
        val scoring = transport.request("GET", api("scoring", "NVDA"), mapOf("horizonDays" to "180")).objectValue()
        assertNull(scoring.decimal("reliabilityScore"))
        assertEquals(180L, scoring.id("horizonDays"))
        val regime = transport.request("GET", api("regimes", "NVDA")).objectValue()
        assertEquals("RESEARCH_ONLY", regime.required("status"))
    }

    @Test fun neverFillsInMissingOutcomesOrBadScenarioData() = runTest {
        val demo = DemoTransport()
        val predictions = demo.request("GET", api("charts", "AAPL", "predictions")).objectValue()
        val pending = predictions.list("points").last()
        pending.addProperty("realizedPrice", 10.0)
        assertThrows(ContractFailure::class.java) { predictionResearchChart(predictions) }

        val scenarios = demo.request("GET", api("charts", "AAPL", "scenarios")).objectValue()
        scenarios.list("points").first().addProperty("central", -1)
        assertThrows(ContractFailure::class.java) { scenarioResearchChart(scenarios) }
    }

    @Test fun historicalSeriesRejectsDuplicateDates() = runTest {
        val history = DemoTransport().request("GET", api("charts", "NVDA", "history")).objectValue()
        val points = history.list("points")
        points[1].add("timestamp", points[0].get("timestamp"))
        assertThrows(ContractFailure::class.java) { historicalResearchChart(history) }
    }
}
