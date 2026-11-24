package lu.bourse.engine.provider;

import org.junit.jupiter.api.Test;
import java.time.Instant;
import static org.junit.jupiter.api.Assertions.assertEquals;

class TwelveDataSeriesServiceTest {
    @Test
    void formatsOverlappingWindowsInUtc() {
        var latest = Instant.parse("2026-11-24T15:45:00Z");
        assertEquals("2026-11-22 15:45:00", TwelveDataSeriesService.startDate(latest, "15m"));
        assertEquals("2026-11-17", TwelveDataSeriesService.startDate(latest, "1d"));
    }
}
