package lu.bourse.mobile

import com.google.gson.*
import kotlinx.coroutines.test.runTest
import lu.bourse.mobile.data.*
import org.junit.Assert.*
import org.junit.Test
import java.math.BigDecimal

class AnalysisTest {
    private fun rules(name: String = "CUSTOM") = mapOf("name" to name, "technicalMax" to "10", "fundamentalMax" to "10", "informationMax" to "10", "marginChangeThreshold" to "3", "rsiOversold" to "30", "rsiOverbought" to "70")
    @Test fun everyHorizonCalculatesWithNoExecutionOrProbabilityClaim() = runTest {
        val demo = DemoTransport()
        for ((horizon, _) in horizons) {
            val result = scoreAnalysis(demo.request("POST", api("explanations", "NVDA"), mapOf("horizon" to horizon)))
            assertTrue(result.score!! >= BigDecimal.ZERO && result.score <= BigDecimal(100))
            assertEquals("DEMO_ONLY", result.raw.text("method")); assertNull(result.previous)
            assertEquals(0, result.factors.first { it.text("key") == "SEASONALITY" }.decimal("points")!!.signum())
        }
    }
    @Test fun newRulesAreVersionedAndScoreChangesAreExactlyDecomposed() = runTest {
        val demo = DemoTransport()
        val originalRules = demo.request("GET", api("explanations", "rulesets")).deepCopy()
        val first = scoreAnalysis(demo.request("POST", api("explanations", "NVDA"), mapOf("horizon" to "MONTH")))
        val id = demo.request("POST", api("explanations", "rulesets"), body = scoreRules(rules())).objectValue().id()!!
        val second = scoreAnalysis(demo.request("POST", api("explanations", "NVDA"), mapOf("horizon" to "MONTH", "rulesetId" to id.toString())))
        assertTrue(second.raw.flag("rulesetChanged")!!)
        assertEquals(0, (second.score!! - first.score!!).compareTo(second.change))
        val sum = second.raw.child("contributionChanges")!!.entrySet().fold(BigDecimal.ZERO) { total, entry -> total + entry.value.asBigDecimal }
        assertEquals(0, sum.compareTo(second.change))
        val current = demo.request("GET", api("explanations", "rulesets")).rows()
        assertEquals(originalRules.asJsonArray[0], current[0])
        assertEquals(first.raw, demo.request("GET", api("explanations", "snapshots", first.id)))
        assertEquals(2, demo.request("GET", api("explanations", "NVDA", "history"), mapOf("horizon" to "MONTH")).rows().size)
    }
    @Test fun rulesRejectReservedNamesUnboundedWeightsAndReversedRsi() {
        assertThrows(IllegalArgumentException::class.java) { scoreRules(rules("DEFAULT_STOLEN")) }
        assertThrows(IllegalArgumentException::class.java) { scoreRules(rules() + ("technicalMax" to "40")) }
        assertThrows(IllegalArgumentException::class.java) { scoreRules(rules() + ("rsiOversold" to "80")) }
        assertThrows(IllegalArgumentException::class.java) { scoreRules(rules() + ("technicalMax" to "1.5")) }
    }
    @Test fun missingScoreStaysNullAndInconsistentChangesAreRejected() {
        val missing = obj("id" to 1, "score" to null, "coverageScore" to 0, "factors" to JsonArray())
        assertNull(scoreAnalysis(missing).score)
        missing.addProperty("coverageScore", 4294967296L); assertThrows(ContractFailure::class.java) { scoreAnalysis(missing) }
        val bad = obj("id" to 2, "score" to 40, "previousScore" to 50, "scoreChange" to 10, "coverageScore" to 100)
        assertThrows(ContractFailure::class.java) { scoreAnalysis(bad) }
    }
    @Test fun seasonalityContainsOnlyMonthQuarterAndYearAndHasNoScoreWeight() = runTest {
        val result = DemoTransport().request("GET", api("seasonality", "NVDA")).objectValue()
        assertFalse(result.flag("usableForScoring")!!)
        val stats = result.child("statistics")!!
        assertEquals(12, stats.list("monthly").size); assertEquals(4, stats.list("quarterly").size); assertEquals(1, stats.list("annual").size)
        assertNull(stats.field("hourly"))
    }
    @Test fun evidenceExtractionRejectsUnsafeSchemesAndDeduplicatesLinks() {
        val evidence = obj("unsafe" to "javascript:bad", "source" to "https://example.com/original", "copy" to obj("url" to "https://example.com/original"), "plainHttp" to "http://example.com")
        assertEquals(listOf("https://example.com/original"), evidenceLinks(evidence))
    }
}
