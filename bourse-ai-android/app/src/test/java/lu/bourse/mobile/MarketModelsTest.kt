package lu.bourse.mobile

import com.google.gson.JsonParser
import lu.bourse.mobile.data.marketModelResults
import org.junit.Assert.*
import org.junit.Test
import java.math.BigDecimal

class MarketModelsTest {
    @Test fun onlyCurrentResearchEstimatesAreDisplayed() {
        val payload = JsonParser.parseString("""
            [
              {"horizon":"DAY","model":"LOGISTIC","asOf":"2026-11-24T21:03:00Z","status":"RESEARCH_ONLY",
               "estimate":0.63,"testCount":100,"brier":0.24,"constantBrier":0.25},
              {"horizon":"MONTH","model":"LOGISTIC","asOf":"2026-10-01T20:03:00Z","status":"STALE_OR_UNVERIFIED",
               "estimate":0.71}
            ]
        """.trimIndent())
        val (current, stale) = marketModelResults(payload)
        assertEquals(BigDecimal("0.63"), current.estimate)
        assertEquals(100L, current.testCount)
        assertNull(stale.estimate)
    }
}
