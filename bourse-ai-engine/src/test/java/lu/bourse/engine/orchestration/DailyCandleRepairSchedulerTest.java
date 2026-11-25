package lu.bourse.engine.orchestration;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.mockito.Mockito.*;

class DailyCandleRepairSchedulerTest {
    @Test void disabledScheduleDoesNotCallTheOrchestrator() {
        var repair = mock(DailyCandleRepairOrchestrator.class);
        new DailyCandleRepairScheduler(repair, false, 5).run();
        verifyNoInteractions(repair);
    }

    @Test void enabledScheduleRepairsTheWatchlistWithTheConfiguredLimit() {
        var repair = mock(DailyCandleRepairOrchestrator.class);
        when(repair.repair(List.of(), null, null, 3)).thenReturn(
                new DailyCandleRepairOrchestrator.Summary(Instant.EPOCH, 0, List.of()));

        new DailyCandleRepairScheduler(repair, true, 3).run();

        verify(repair).repair(List.of(), null, null, 3);
        verifyNoMoreInteractions(repair);
    }
}
