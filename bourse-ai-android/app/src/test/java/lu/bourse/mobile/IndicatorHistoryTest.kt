package lu.bourse.mobile

import lu.bourse.mobile.data.indicatorHistory
import org.junit.Assert.*
import org.junit.Test

class IndicatorHistoryTest {
    @Test fun indicatorWarmupAndValuesFollowClosingPriceOrder() {
        val history = indicatorHistory((1..40).map(Int::toDouble))
        assertNull(history.sma[18])
        assertEquals(10.5, history.sma[19]!!, 1e-9)
        assertNull(history.rsi[13])
        assertEquals(100.0, history.rsi[14]!!, 1e-9)
        assertNull(history.macd[24])
        assertEquals(7.0, history.macd[25]!!, 1e-9)
        assertNull(history.signal[32])
        assertEquals(7.0, history.signal[33]!!, 1e-9)
        assertEquals(0.0, history.histogram.last()!!, 1e-9)
    }

    @Test fun flatAndShortSeriesRetainNullsUntilIndicatorsAreAvailable() {
        val flat = indicatorHistory(List(40) { 50.0 })
        assertEquals(50.0, flat.rsi.last()!!, 1e-9)
        assertEquals(0.0, flat.macd.last()!!, 1e-9)
        val short = indicatorHistory(listOf(50.0))
        assertEquals(listOf(null), short.signal)
        assertEquals(listOf(null), short.histogram)
    }
}
