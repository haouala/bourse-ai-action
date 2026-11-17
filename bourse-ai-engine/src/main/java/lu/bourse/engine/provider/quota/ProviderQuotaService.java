package lu.bourse.engine.provider.quota;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;

@Service
public class ProviderQuotaService {
    private final JdbcTemplate jdbc;
    private final QuotaProperties properties;
    private final Clock clock;
    private final TransactionTemplate transaction;

    public ProviderQuotaService(JdbcTemplate jdbc, QuotaProperties properties, Clock clock,
                                PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.properties = properties;
        this.clock = clock;
        transaction = new TransactionTemplate(transactionManager);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transaction.setTimeout(10);
    }

    /** A conservative rolling 24h / 60s window avoids assumptions about supplier reset times. */
    public void reserve(MarketProvider provider) {
        transaction.executeWithoutResult(status -> {
            Instant blocked = lock(provider);
            Instant now = clock.instant();
            Usage usage = usage(provider, now, blocked);
            if (usage.dailyRemaining() == 0 || usage.minuteRemaining() == 0
                    || (blocked != null && blocked.isAfter(now))) {
                throw new QuotaExceededException(provider, usage.nextAvailableAt());
            }
            jdbc.update("INSERT INTO provider_api_calls(provider,reserved_at,credits) VALUES (?,?,1)",
                    provider.name(), Timestamp.from(now));
        });
    }

    /** Unknown upstream 429: stop conservatively for 24h rather than repeatedly spending calls. */
    public void pauseAfterUpstreamLimit(MarketProvider provider) {
        transaction.executeWithoutResult(status -> {
            Instant existing = lock(provider);
            Instant until = clock.instant().plus(Duration.ofHours(24));
            if (existing != null && existing.isAfter(until)) until = existing;
            jdbc.update("UPDATE provider_quota_locks SET blocked_until=?,updated_time=CURRENT_TIMESTAMP WHERE provider=?",
                    Timestamp.from(until), provider.name());
        });
    }

    public List<Usage> listUsage() {
        return Arrays.stream(MarketProvider.values()).map(provider ->
            transaction.execute(status -> usage(provider, clock.instant(), lock(provider))))
            .toList();
    }

    private Instant lock(MarketProvider provider) {
        return jdbc.queryForObject("SELECT blocked_until FROM provider_quota_locks WHERE provider=? FOR UPDATE",
                (rs, row) -> rs.getTimestamp(1) == null ? null : rs.getTimestamp(1).toInstant(), provider.name());
    }

    private Usage usage(MarketProvider provider, Instant now, Instant blockedUntil) {
        QuotaProperties.Limits limits = properties.providers().get(provider);
        Window daily = window(provider, now.minus(Duration.ofHours(24)));
        Window minute = window(provider, now.minusSeconds(60));
        int dailyRemaining = Math.max(0, limits.daily() - daily.used());
        int minuteRemaining = Math.max(0, limits.perMinute() - minute.used());
        Instant next = now;
        if (dailyRemaining == 0) {
            next = later(next, daily.oldest() == null ? null : daily.oldest().plus(Duration.ofHours(24)));
        }
        if (minuteRemaining == 0) {
            next = later(next, minute.oldest() == null ? null : minute.oldest().plusSeconds(60));
        }
        next = later(next, blockedUntil);
        boolean enabled = limits.daily() > 0 && limits.perMinute() > 0;
        // A disabled provider has no scheduled restart.
        return new Usage(provider, "ROLLING_24H", limits.daily(), daily.used(), dailyRemaining,
                limits.perMinute(), minute.used(), minuteRemaining, enabled, blockedUntil,
                enabled ? next : null);
    }

    private Window window(MarketProvider provider, Instant after) {
        return jdbc.queryForObject("SELECT COALESCE(SUM(credits),0),MIN(reserved_at) FROM provider_api_calls WHERE provider=? AND reserved_at>?",
                (rs, row) -> new Window(rs.getInt(1), rs.getTimestamp(2) == null ? null : rs.getTimestamp(2).toInstant()),
                provider.name(), Timestamp.from(after));
    }

    private static Instant later(Instant first, Instant second) {
        return second != null && second.isAfter(first) ? second : first;
    }

    private record Window(int used, Instant oldest) {}
    public record Usage(MarketProvider provider, String dailyWindow, int dailyLimit, int dailyUsed,
                        int dailyRemaining, int minuteLimit, int minuteUsed, int minuteRemaining,
                        boolean enabled, Instant blockedUntil, Instant nextAvailableAt) {}
}
