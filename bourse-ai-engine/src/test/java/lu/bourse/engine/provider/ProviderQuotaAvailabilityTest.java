package lu.bourse.engine.provider;

import lu.bourse.engine.provider.quota.MarketProvider;
import lu.bourse.engine.provider.quota.ProviderQuotaService.Usage;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import static org.junit.jupiter.api.Assertions.*;

class ProviderQuotaAvailabilityTest {
    @Test
    void disabledAndPausedProvidersDoNotBlockLocalTasks() {
        var now = Instant.parse("2026-11-24T15:45:00Z");
        assertFalse(usage(false, null).canCall(now));
        assertFalse(usage(true, now.plusSeconds(1)).canCall(now));
        assertTrue(usage(true, now).canCall(now));
    }

    private Usage usage(boolean enabled, Instant next) {
        return new Usage(MarketProvider.TWELVE_DATA, "ROLLING_24H", 800, 0, 800,
                8, 0, 8, enabled, null, next);
    }
}
