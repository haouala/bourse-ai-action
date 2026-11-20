package lu.bourse.mobile

import kotlinx.coroutines.test.runTest
import lu.bourse.mobile.data.*
import org.junit.Assert.*
import org.junit.Test

class InformationTest {
    @Test fun publicationDateAndCollectionDateRemainSeparate() = runTest {
        val items = newsItems(DemoTransport().request("GET", api("information", "items")))
        assertNotEquals(items.first().publishedAt, items.first().collectedAt)
        assertNull(items.first { it.id == 3L }.publishedAt)
        assertEquals("MISSING_PUBLICATION_DATE", items.first { it.id == 3L }.review)
    }
    @Test fun sourceFilterDoesNotInventOrReclassifyItems() = runTest {
        val rows = newsItems(DemoTransport().request("GET", api("information", "items"), mapOf("source" to "DEMO_POLICY")))
        assertEquals(1, rows.size); assertEquals("PENDING_REVIEW", rows.first().review)
    }
    @Test fun exactQuoteNeedsDatedTextAndSafeEvidence() = runTest {
        val items = newsItems(DemoTransport().request("GET", api("information", "items")))
        val item = items.first()
        assertThrows(IllegalArgumentException::class.java) { attributionPayload(item, "1", "These words do not occur", "https://example.com") }
        assertThrows(IllegalArgumentException::class.java) { attributionPayload(items.last(), "1", items.last().summary!!, "https://example.com") }
        assertThrows(IllegalArgumentException::class.java) { attributionPayload(item, "1", "Nous envisageons de nouveaux droits de douane.", "javascript:bad") }
    }
    @Test fun quoteAttributionIsIdempotentAndPreservesOriginalSource() = runTest {
        val demo = DemoTransport(); val item = newsItems(demo.request("GET", api("information", "items"))).first()
        val body = attributionPayload(item, "1", "Nous envisageons de nouveaux droits de douane.", "https://example.com/evidence")
        val first = demo.request("POST", api("information", "items", 1, "attributions"), body = body)
        val again = demo.request("POST", api("information", "items", 1, "attributions"), body = body)
        assertEquals(first, again)
        val publication = demo.request("GET", api("social", "publications")).rows().last()
        assertEquals(item.originalUrl, publication.text("sourceUrl")); assertEquals("HUMAN_VERIFIED_QUOTE", publication.text("attributionKind"))
        val mention = demo.request("GET", api("information", "items", 1, "mentions")).rows().first()
        assertEquals("HUMAN_VERIFIED_QUOTE", mention.text("attributionStatus"))
    }
    @Test fun feedsAreOptInAndManualImportsKeepAJournal() = runTest {
        val demo = DemoTransport(); val path = api("information", "sources", "DEMO_POLICY")
        assertTrue(demo.request("GET", api("information", "sources")).rows().none { it.flag("enabled") == true })
        val blocked = try { demo.request("POST", path + "sync"); null } catch (e: ApiFailure) { e }
        assertEquals(409, blocked?.status)
        demo.request("PUT", path + "enabled", body = obj("enabled" to true)); demo.request("POST", path + "sync")
        assertEquals("SUCCEEDED", demo.request("GET", api("information", "runs")).rows().single().text("status"))
    }
    @Test fun nameMatchDoesNotAutomaticallyConfirmIdentityOrQuote() = runTest {
        val demo = DemoTransport()
        assertFalse(demo.request("GET", api("social", "actors")).rows().first().flag("identityConfirmed")!!)
        assertEquals("NAME_MATCH_ONLY", demo.request("GET", api("information", "items", 1, "mentions")).rows().first().text("attributionStatus"))
        assertEquals("UNCONFIRMED", demo.request("GET", api("social", "events")).rows().first().text("confirmation"))
    }
}
