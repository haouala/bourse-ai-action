package lu.bourse.engine.market;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.mockito.Mockito.*;

class MarketSchedulerTest {
    @Test
    void startupQueuesWatchlistOnlyWhenEnabled() {
        var watchlist = mock(WatchlistService.class);
        var scheduler = new MarketScheduler(watchlist, null, null);
        scheduler.enqueueOnStartup();
        verifyNoInteractions(watchlist);
        ReflectionTestUtils.setField(scheduler, "enabled", true);
        scheduler.enqueueOnStartup();
        verify(watchlist).enqueue();
    }
}
