package lu.bourse.engine.provider;

import lu.bourse.engine.provider.quota.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.*;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.IntStream;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(properties = {
        "market.quotas.providers.TWELVE_DATA.daily=3",
        "market.quotas.providers.TWELVE_DATA.per-minute=2"
})
class QuotaIntegrationTest extends DatabaseIntegrationSupport {
    @Autowired ProviderQuotaService quotas;
    @Autowired QuotaProperties properties;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactions;
    @Autowired MutableClock clock;

    @TestConfiguration
    static class TimeConfiguration {
        @Bean @Primary MutableClock testClock() { return new MutableClock(); }
    }

    static class MutableClock extends Clock {
        private final AtomicReference<Instant> current = new AtomicReference<>();
        void set(Instant instant) { current.set(instant); }
        void advance(Duration duration) { current.updateAndGet(t -> t.plus(duration)); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return current.get(); }
    }

    @BeforeEach
    void reset() {
        jdbc.update("DELETE FROM provider_api_calls");
        jdbc.update("UPDATE provider_quota_locks SET blocked_until=NULL");
        clock.set(Instant.parse("2026-11-17T23:59:30Z"));
    }

    @Test
    void minuteAndDailyLimitsSurviveMidnightUntilRollingWindowExpires() {
        quotas.reserve(MarketProvider.TWELVE_DATA);
        quotas.reserve(MarketProvider.TWELVE_DATA);
        assertThatThrownBy(() -> quotas.reserve(MarketProvider.TWELVE_DATA)).isInstanceOf(QuotaExceededException.class);
        clock.advance(Duration.ofSeconds(60));
        quotas.reserve(MarketProvider.TWELVE_DATA);
        assertThatThrownBy(() -> quotas.reserve(MarketProvider.TWELVE_DATA)).isInstanceOf(QuotaExceededException.class);
        assertThat(usage(MarketProvider.TWELVE_DATA).dailyUsed()).isEqualTo(3);
        clock.advance(Duration.ofHours(24).minusSeconds(60));
        quotas.reserve(MarketProvider.TWELVE_DATA);
        assertThat(usage(MarketProvider.TWELVE_DATA).dailyUsed()).isEqualTo(2);
    }

    @Test
    void independentInstancesShareTheSameBudgetUnderConcurrency() throws Exception {
        ProviderQuotaService other = new ProviderQuotaService(jdbc, properties, clock, transactions);
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newFixedThreadPool(12)) {
            var tasks = IntStream.range(0, 12).mapToObj(index -> executor.submit(() -> {
                start.await();
                try {
                    (index % 2 == 0 ? quotas : other).reserve(MarketProvider.FMP);
                    return true;
                } catch (QuotaExceededException exception) { return false; }
            })).toList();
            start.countDown();
            int accepted = 0;
            for (Future<Boolean> task : tasks) if (task.get(20, TimeUnit.SECONDS)) accepted++;
            assertThat(accepted).isEqualTo(5);
        }
        assertThat(other.listUsage().stream().filter(u -> u.provider() == MarketProvider.FMP)
                .findFirst().orElseThrow().dailyUsed()).isEqualTo(5);
    }

    @Test
    void reservationRemainsWhenAnEnclosingImportRollsBack() {
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            quotas.reserve(MarketProvider.EODHD);
            status.setRollbackOnly();
        });
        var recreated = new ProviderQuotaService(jdbc, properties, clock, transactions);
        assertThat(recreated.listUsage().stream().filter(u -> u.provider() == MarketProvider.EODHD)
                .findFirst().orElseThrow().dailyUsed()).isEqualTo(1);
    }

    @Test
    void upstreamPausePersistsAndIsIsolatedByProvider() {
        quotas.reserve(MarketProvider.EODHD);
        quotas.pauseAfterUpstreamLimit(MarketProvider.EODHD);
        assertThatThrownBy(() -> quotas.reserve(MarketProvider.EODHD)).isInstanceOf(QuotaExceededException.class);
        quotas.reserve(MarketProvider.FMP);
        clock.advance(Duration.ofHours(24));
        quotas.reserve(MarketProvider.EODHD);
        assertThat(usage(MarketProvider.EODHD).dailyUsed()).isEqualTo(1);
    }

    @Test
    void fmpDailyBudgetStopsAt240AndCanOnlyResumeAfterExpiry() {
        Instant now = clock.instant();
        for (int index = 0; index < 240; index++) {
            jdbc.update("INSERT INTO provider_api_calls(provider,reserved_at,credits) VALUES ('FMP',?,1)",
                    java.sql.Timestamp.from(now.minusSeconds(120 + index)));
        }
        assertThat(usage(MarketProvider.FMP).minuteRemaining()).isEqualTo(5);
        assertThatThrownBy(() -> quotas.reserve(MarketProvider.FMP)).isInstanceOf(QuotaExceededException.class);
        assertThat(usage(MarketProvider.FMP).dailyUsed()).isEqualTo(240);
        clock.advance(Duration.ofHours(24));
        quotas.reserve(MarketProvider.FMP);
        assertThat(usage(MarketProvider.FMP).dailyUsed()).isEqualTo(1);
    }

    @Test
    void zeroBudgetDisablesCallsAndInvalidConfigurationFailsClosed() {
        var zero = new QuotaProperties(Map.of(
                MarketProvider.TWELVE_DATA, new QuotaProperties.Limits(0, 0),
                MarketProvider.EODHD, new QuotaProperties.Limits(18, 20),
                MarketProvider.FMP, new QuotaProperties.Limits(240, 5)));
        var disabled = new ProviderQuotaService(jdbc, zero, clock, transactions);
        assertThatThrownBy(() -> disabled.reserve(MarketProvider.TWELVE_DATA)).isInstanceOf(QuotaExceededException.class);
        assertThat(usage(MarketProvider.TWELVE_DATA).dailyUsed()).isZero();
        assertThatThrownBy(() -> new QuotaProperties(Map.of())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new QuotaProperties(Map.of(
                MarketProvider.TWELVE_DATA, new QuotaProperties.Limits(801, 8),
                MarketProvider.EODHD, new QuotaProperties.Limits(18, 20),
                MarketProvider.FMP, new QuotaProperties.Limits(240, 5)))).isInstanceOf(IllegalArgumentException.class);
    }

    private ProviderQuotaService.Usage usage(MarketProvider provider) {
        return quotas.listUsage().stream().filter(u -> u.provider() == provider).findFirst().orElseThrow();
    }
}
