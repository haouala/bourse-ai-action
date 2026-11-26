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
    @Test fun patternReportUsesClosedChartCandlesAndKeepsVolume() = runTest {
        val chart = chartData(DemoTransport().request("GET", api("charts", "NVDA")))
        val pattern = chart.patterns!!.patterns.single()
        assertEquals("HAMMER", pattern.code)
        assertEquals(chart.candles.last().closesAt, pattern.closesAt)
        assertEquals("AWAITING_NEXT_BAR", pattern.confirmation)
        assertNull(pattern.confirmationAt)
        assertEquals(0, chart.candles.last().volume!!.compareTo(BigDecimal("200000")))
        assertEquals(0, pattern.volumeRatio!!.compareTo(BigDecimal("2")))
    }
    @Test fun olderServerOrMalformedOptionalPatternsDoNotHidePrices() = runTest {
        val raw = DemoTransport().request("GET", api("charts", "NVDA")).objectValue()
        raw.remove("patternAnalysis")
        assertNull(chartData(raw).patterns); assertEquals(48, chartData(raw).candles.size)
        val broken = DemoTransport().request("GET", api("charts", "NVDA")).objectValue()
        broken.child("patternAnalysis")!!.addProperty("method", "UNKNOWN_V2")
        assertNull(chartData(broken).patterns); assertEquals(48, chartData(broken).candles.size)
    }
    @Test fun fabricatedPatternDateAndUnobservedConfirmationAreRejected() = runTest {
        val raw = DemoTransport().request("GET", api("charts", "NVDA")).objectValue()
        val pattern = raw.child("patternAnalysis")!!.list("patterns").single()
        pattern.addProperty("confirmation", "PRICE_CONFIRMED")
        pattern.addProperty("confirmationAt", "2099-01-01T00:00:00Z")
        assertNull(chartData(raw).patterns)
        pattern.addProperty("confirmation", "AWAITING_NEXT_BAR"); pattern.remove("confirmationAt")
        pattern.addProperty("openTime", "2099-01-01T00:00:00Z")
        assertNull(chartData(raw).patterns)
    }
}
