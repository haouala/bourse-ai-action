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
    @Test fun selectedFeaturesAndAblationRemainExplicitAndOlderReportsStayCompatible(){
        val payload=JsonParser.parseString("""[
          {"horizon":"DAY","model":"LOGISTIC","asOf":"2026-11-24T21:03:00Z","status":"RESEARCH_ONLY","estimate":0.63,
           "featureSet":"TECHNICAL_ENRICHED","featureComparison":[{"featureSet":"PRICE_ONLY","testBrier":0.25,"validationBrier":0.25},{"featureSet":"TECHNICAL_ENRICHED","testBrier":0.23,"validationBrier":0.22}]},
          {"horizon":"MONTH","model":"LOGISTIC","asOf":"2026-11-24T21:03:00Z","status":"RESEARCH_ONLY","estimate":0.60}
        ]""")
        val parsed=marketModelResults(payload)
        assertEquals("TECHNICAL_ENRICHED",parsed[0].featureSet);assertEquals(2,parsed[0].comparison.size)
        assertEquals("PRICE_ONLY",parsed[1].featureSet);assertTrue(parsed[1].comparison.isEmpty())
    }
}
