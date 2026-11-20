package lu.bourse.mobile

import kotlinx.coroutines.test.runTest
import lu.bourse.mobile.data.*
import org.junit.Assert.*
import org.junit.Test
import java.math.BigDecimal

class ChartsTest {
    @Test fun tinyCryptoPricesAreNeverRoundedToZeroForDisplay() {
        assertEquals("0.00000001 USD", priceText(BigDecimal("0.0000000100"), "USD"))
        assertEquals("Indisponible", priceText(null))
    }
    @Test fun everySupportedFrameHasOrderedClosedDemoCandles() = runTest {
        for (frame in chartFrames) {
            val chart = chartData(DemoTransport().request("GET", api("charts", "NVDA"), mapOf("timeframe" to frame)))
            assertEquals(frame, chart.timeframe); assertEquals(48, chart.candles.size)
            assertTrue(chart.candles.zipWithNext().all { it.second.opensAt.isAfter(it.first.opensAt) })
            assertTrue(chart.candles.all { it.closesAt.isAfter(it.opensAt) && it.close > BigDecimal.ZERO })
        }
    }
    @Test fun chartNormalizationHandlesFlatAndVeryLargePrices() {
        assertEquals(listOf(0.5f, 0.5f), normalizedPoints(listOf(BigDecimal.ONE, BigDecimal.ONE)))
        val values = normalizedPoints(listOf(BigDecimal("10000000000000000000000000000000000000000"), BigDecimal("10000000000000000000000000000000000000001")))
        assertEquals(listOf(0f, 1f), values); assertTrue(normalizedPoints(emptyList()).isEmpty())
    }
    @Test fun invalidPricesAndDuplicateDatesDoNotBecomeValidCharts() = runTest {
        val raw = DemoTransport().request("GET", api("charts", "AAPL")).objectValue()
        raw.field("candles")!!.asJsonArray[0].asJsonObject.addProperty("close", -1)
        assertThrows(ContractFailure::class.java) { chartData(raw) }
        val duplicate = DemoTransport().request("GET", api("charts", "AAPL")).objectValue()
        val bars = duplicate.field("candles")!!.asJsonArray; bars[1].asJsonObject.add("openTime", bars[0].asJsonObject.get("openTime"))
        assertThrows(ContractFailure::class.java) { chartData(duplicate) }
    }
    @Test fun missingIndicatorsRemainMissingAndUnknownTimeframesFail() = runTest {
        val raw = DemoTransport().request("GET", api("charts", "AAPL")).objectValue(); raw.remove("latestIndicators")
        assertNull(chartData(raw).indicators)
        raw.addProperty("timeframe", "1m"); assertThrows(ContractFailure::class.java) { chartData(raw) }
    }
    @Test fun demoCalendarIsExplicitlyFictitiousAndSeparateFromCharts() = runTest {
        val state = DemoTransport().request("GET", api("calendars", "assets", "NVDA", "state")).objectValue()
        assertEquals("DEMO_US", state.text("calendarCode")); assertEquals("CLOSED", state.text("marketState")); assertTrue(state.required("reason").contains("fictif"))
    }
}
