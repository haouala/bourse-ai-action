package lu.bourse.engine.provider;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {
        "market.twelve-data.api-key=test-only-secret",
        "market.eodhd.api-key=test-only-secret",
        "market.fmp.api-key=test-only-secret",
        "market.quotas.providers.TWELVE_DATA.per-minute=2"
})
@AutoConfigureMockMvc
class ProviderFlowIntegrationTest extends DatabaseIntegrationSupport {
    private static final AtomicInteger calls = new AtomicInteger();
    private static final AtomicInteger responseStatus = new AtomicInteger(200);
    private static final AtomicReference<String> responseBody = new AtomicReference<>("[]");
    private static final AtomicReference<String> redirect = new AtomicReference<>();
    private static final AtomicReference<String> requestQuery = new AtomicReference<>();
    private static final HttpServer server = startServer();
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;

    private static HttpServer startServer() {
        try {
            HttpServer stub = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            stub.createContext("/", exchange -> {
                calls.incrementAndGet();
                requestQuery.set(exchange.getRequestURI().getRawQuery());
                byte[] body = responseBody.get().getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                if (redirect.get() != null) exchange.getResponseHeaders().set("Location", redirect.get());
                exchange.sendResponseHeaders(responseStatus.get(), body.length);
                try (var output = exchange.getResponseBody()) { output.write(body); }
            });
            stub.start();
            return stub;
        } catch (IOException exception) { throw new IllegalStateException(exception); }
    }

    @DynamicPropertySource
    static void providerUrls(DynamicPropertyRegistry registry) {
        String address = "http://127.0.0.1:" + server.getAddress().getPort();
        for (String provider : new String[]{"twelve-data", "eodhd", "fmp", "gdelt", "sec"}) {
            registry.add("market." + provider + ".base-url", () -> address);
        }
    }

    @BeforeEach
    void reset() {
        for (String table : new String[]{"provider_api_calls", "market_news", "financial_facts",
                "company_profiles", "asset_provider_symbols", "provider_price_observations", "market_candles", "assets", "import_jobs"}) {
            jdbc.update("DELETE FROM " + table);
        }
        jdbc.update("UPDATE provider_quota_locks SET blocked_until=NULL");
        calls.set(0);
        responseStatus.set(200);
        responseBody.set("[]");
        redirect.set(null);
    }

    @AfterAll static void stopServer() { server.stop(0); }

    @Test
    void watchesOnlyDailyImportsWithVerifiedUsExchangeMetadata() throws Exception {
        responseBody.set(twelvePrices().replace("\"currency\":\"USD\"", "\"currency\":\"USD\",\"mic_code\":\"XNGS\""));
        mvc.perform(post("/api/v1/providers/twelve-data/sync/NVDA")).andExpect(status().isOk());
        responseBody.set(twelvePrices());
        mvc.perform(post("/api/v1/providers/twelve-data/sync/UNKNOWN")).andExpect(status().isOk());
        mvc.perform(post("/api/v1/watchlist/register-imported-us"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.registered").value(1));
        mvc.perform(post("/api/v1/watchlist/register-imported-us"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.registered").value(0));
        assertThat(jdbc.queryForList("SELECT provider_symbol FROM watchlist", String.class))
                .containsExactly("NVDA");
    }

    @Test
    void dailyPricesFlowThroughPersistenceIndicatorsNewsAndExplanation() throws Exception {
        responseBody.set(twelvePrices());
        mvc.perform(post("/api/v1/providers/twelve-data/sync/NVDA"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.inserted").value(40));
        mvc.perform(post("/api/v1/providers/twelve-data/sync/NVDA"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.skipped").value(40));
        // Local quota rejection must happen before a third HTTP request.
        mvc.perform(post("/api/v1/providers/twelve-data/sync/NVDA"))
                .andExpect(status().isTooManyRequests()).andExpect(header().exists("Retry-After"));
        assertThat(calls.get()).isEqualTo(2);
        mvc.perform(get("/api/v1/history/analysis/NVDA"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.candleCount").value(40));

        responseBody.set("{\"articles\":[{\"url\":\"https://example.com/earnings\",\"title\":\"NVIDIA raises guidance\",\"domain\":\"example.com\",\"seendate\":\"20261118T143000Z\"}]}");
        mvc.perform(post("/api/v1/providers/gdelt/sync/NVDA"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.inserted").value(1));
        mvc.perform(post("/api/v1/news/analyze/NVDA"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.positive").value(1));
        mvc.perform(get("/api/v1/investment/explain/NVDA"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.verdict").value("REVIEW_ONLY"))
                .andExpect(jsonPath("$.technical.candleCount").value(40))
                .andExpect(jsonPath("$.news[0].sentimentScore").value(1))
                .andExpect(jsonPath("$.news[0].publishedTime").value("2026-11-18T14:30:00Z"));
        var usage = mvc.perform(get("/api/v1/providers/quotas")).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].dailyUsed").value(2)).andReturn().getResponse().getContentAsString();
        assertThat(usage).doesNotContain("test-only-secret", "apikey");
        assertThat(calls.get()).isEqualTo(3); // Reads use the database, not the provider.
    }

    @Test
    void eodhdAndFmpImportsAreMeteredAndTheirReadEndpointsSpendNothing() throws Exception {
        responseBody.set("[{\"date\":\"2026-01-05\",\"open\":100,\"high\":102,\"low\":99,\"close\":101,\"volume\":1000}]");
        mvc.perform(post("/api/v1/providers/eodhd/sync/BNP.PA").param("providerSymbol", "BNP.PA"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.observations").value(1));
        responseBody.set("[{\"symbol\":\"BNP.PA\",\"companyName\":\"BNP Paribas\",\"marketCap\":1000}]");
        mvc.perform(post("/api/v1/providers/fmp/sync/BNP.PA"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.name").value("BNP Paribas"));
        mvc.perform(get("/api/v1/company/BNP.PA")).andExpect(status().isOk());
        mvc.perform(get("/api/v1/providers/eodhd/observations/BNP.PA")).andExpect(status().isOk());
        mvc.perform(get("/api/v1/providers/quotas"))
                .andExpect(status().isOk()).andExpect(jsonPath("$[1].dailyUsed").value(1))
                .andExpect(jsonPath("$[2].dailyUsed").value(1));
        assertThat(calls.get()).isEqualTo(2);
    }

    @Test
    void eodhdRequestedDepthAndCryptoTypeAreConfigurableWithoutClaimingFullCoverage() throws Exception {
        responseBody.set("[{\"date\":\"2020-01-05\",\"open\":100,\"high\":102,\"low\":99,\"close\":101,\"volume\":1000}]");
        mvc.perform(post("/api/v1/providers/eodhd/sync/BTC-USD").param("providerSymbol","BTC-USD.CC").param("currency","USD").param("assetType","CRYPTO").param("years","10"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.observations").value(1))
                .andExpect(jsonPath("$.coverageNotice").value(org.hamcrest.Matchers.containsString("Requested 10 years")));
        assertThat(requestQuery.get()).contains("from="+LocalDate.now(java.time.ZoneOffset.UTC).minusYears(10));
        assertThat(jdbc.queryForObject("SELECT asset_type FROM assets WHERE symbol='BTC-USD'",String.class)).isEqualTo("CRYPTO");
    }

    @Test
    void failedResponseStillConsumesOneCreditWithoutLeakingTheKey() throws Exception {
        responseStatus.set(500);
        responseBody.set("test-only-secret provider failure");
        var error = mvc.perform(post("/api/v1/providers/twelve-data/sync/NVDA"))
                .andExpect(status().isBadGateway()).andReturn().getResolvedException();
        assertThat(error).hasNoCause();
        assertThat(error.getMessage()).doesNotContain("test-only-secret");
        assertThat(jdbc.queryForObject("SELECT error_message FROM import_jobs", String.class))
                .doesNotContain("test-only-secret", "apikey");
        mvc.perform(get("/api/v1/providers/quotas")).andExpect(jsonPath("$[0].dailyUsed").value(1));
        assertThat(calls.get()).isEqualTo(1);
    }

    @Test
    void http429PausesFurtherCallsInsteadOfRetrying() throws Exception {
        responseStatus.set(429);
        mvc.perform(post("/api/v1/providers/twelve-data/sync/NVDA")).andExpect(status().isTooManyRequests());
        mvc.perform(post("/api/v1/providers/twelve-data/sync/NVDA")).andExpect(status().isTooManyRequests());
        mvc.perform(get("/api/v1/providers/quotas"))
                .andExpect(jsonPath("$[0].dailyUsed").value(1)).andExpect(jsonPath("$[0].blockedUntil").isNotEmpty());
        assertThat(calls.get()).isEqualTo(1);
    }

    @Test
    void twelveDataJson429AlsoPausesCalls() throws Exception {
        responseBody.set("{\"status\":\"error\",\"code\":429,\"message\":\"test-only-secret\"}");
        mvc.perform(post("/api/v1/providers/twelve-data/sync/NVDA")).andExpect(status().isTooManyRequests());
        mvc.perform(post("/api/v1/providers/twelve-data/sync/NVDA")).andExpect(status().isTooManyRequests());
        assertThat(calls.get()).isEqualTo(1);
    }

    @Test
    void invalidInputAndMissingAssetDoNotSpendCredits() throws Exception {
        mvc.perform(post("/api/v1/providers/twelve-data/sync/NVDA").param("years", "0"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/providers/fmp/sync/UNKNOWN")).andExpect(status().isNotFound());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM provider_api_calls", Integer.class)).isZero();
        assertThat(calls.get()).isZero();
    }

    @Test
    void redirectDoesNotSilentlyCauseAnExtraOutgoingRequest() throws Exception {
        responseStatus.set(302);
        redirect.set("http://127.0.0.1:" + server.getAddress().getPort() + "/redirect-target");
        mvc.perform(post("/api/v1/providers/twelve-data/sync/NVDA")).andExpect(status().isBadGateway());
        assertThat(calls.get()).isEqualTo(1);
    }

    private static String twelvePrices() {
        String bars = IntStream.rangeClosed(1, 40).mapToObj(day ->
                "{\"datetime\":\"" + LocalDate.now(java.time.ZoneOffset.UTC).minusDays(day)
                        + "\",\"open\":\"100\",\"high\":\"150\",\"low\":\"90\",\"close\":\""
                        + (140 - day) + "\",\"volume\":\"1000\"}").collect(Collectors.joining(","));
        return "{\"meta\":{\"currency\":\"USD\",\"name\":\"NVIDIA\"},\"values\":[" + bars + "]}";
    }
}

