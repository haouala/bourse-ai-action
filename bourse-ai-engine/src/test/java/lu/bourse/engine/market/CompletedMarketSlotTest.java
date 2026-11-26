package lu.bourse.engine.market;

import lu.bourse.engine.calendar.MarketCalendarService.State;
import org.junit.jupiter.api.Test;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

class CompletedMarketSlotTest {
    @Test
    void schedulesOnlyCompletedBarsInVerifiedSessions() {
        Instant lastClose = Instant.parse("2026-11-27T18:00:00Z");
        assertEquals(lastClose, WatchlistService.completedSlot(new State("US_EQUITIES", "CLOSED", lastClose, null, "EARLY_CLOSE")));
        assertEquals(lastClose, WatchlistService.completedSlot(new State("US_EQUITIES", "OPEN", lastClose, null, "AWAITING_FIRST_BAR")));
        assertNull(WatchlistService.completedSlot(new State("US_EQUITIES", "HALTED", lastClose, null, "HALTED")));
        assertNull(WatchlistService.completedSlot(new State("US_EQUITIES", "UNKNOWN", null, null, "NO_COVERAGE")));
    }
}
