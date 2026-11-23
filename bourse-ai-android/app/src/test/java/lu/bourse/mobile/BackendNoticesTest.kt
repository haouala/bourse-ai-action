package lu.bourse.mobile

import kotlinx.coroutines.test.runTest
import lu.bourse.mobile.data.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class BackendNoticesTest {
    @Test fun structuredRateLimitIsAWarningWithRetryAndRequestId() {
        val failure=serverFailure(429,
            """{"code":"GDELT_RATE_LIMIT","severity":"WARNING","message":"GDELT temporairement indisponible.","retryable":true,"retryAfterSeconds":3600,"requestId":"req-123"}""")
        assertEquals(429,failure.status)
        assertEquals("WARNING",failure.severity)
        assertEquals("GDELT_RATE_LIMIT",failure.code)
        assertEquals(3600L,failure.retryAfterSeconds)
        assertEquals("req-123",failure.requestId)
    }
    @Test fun unknownBackendErrorBodiesAreNeverDisplayed() {
        val failure=serverFailure(502,"my_secret_token_123")
        assertEquals("WARNING",failure.severity)
        assertFalse(failure.message!!.contains("my_secret_token"))
        assertNull(failure.code)
        val untrusted=serverFailure(400,"""{"message":"password=my-secret"}""")
        assertFalse(untrusted.message!!.contains("my-secret"))
    }
    @Test fun httpTransportPreservesStructuredBackendError() = runTest {
        val server=MockWebServer();server.start()
        try {
            server.enqueue(MockResponse().setResponseCode(503).setBody(
                """{"code":"GDELT_BATCH_NOT_PUBLISHED","severity":"WARNING","message":"Archive non disponible, réessayez plus tard.","retryAfterSeconds":900}"""))
            val failure=try {
                HttpTransport(ConnectionConfig(false,server.url("/").toString()),true)
                    .request("POST",api("news-collection","run"),mapOf("method" to "NGRAMS"))
                null
            } catch(e:ApiFailure) {e}
            assertEquals(503,failure?.status)
            assertEquals("GDELT_BATCH_NOT_PUBLISHED",failure?.code)
            assertEquals(900L,failure?.retryAfterSeconds)
            assertEquals("/api/v1/news-collection/run?method=NGRAMS",server.takeRequest().path)
        } finally {server.shutdown()}
    }
}
