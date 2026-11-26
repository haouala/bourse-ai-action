package lu.bourse.engine.orchestration;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@SpringBootTest
@Transactional
class DailyCandleRepairSchedulerTest {
    private static final Instant NOW = Instant.parse("2026-11-25T22:00:00Z");
    @Autowired JdbcTemplate jdbc;
    @MockitoBean DailyCandleRepairOrchestrator repair;

    @Test void disabledScheduleDoesNotCallTheOrchestrator() {
        new DailyCandleRepairScheduler(repair, jdbc, Clock.fixed(NOW, ZoneOffset.UTC), false, 5).run();
        verifyNoInteractions(repair);
    }

    @Test void enabledScheduleRecordsEvenAHealthyRunWithNoGaps() {
        when(repair.repair(List.of(), null, null, 3)).thenReturn(
                new DailyCandleRepairOrchestrator.Summary(NOW, 0, List.of()));

        new DailyCandleRepairScheduler(repair, jdbc, Clock.fixed(NOW, ZoneOffset.UTC), true, 3).run();

        verify(repair).repair(List.of(), null, null, 3);
        assertThat(jdbc.queryForObject("SELECT status FROM daily_repair_runs", String.class)).isEqualTo("SUCCESS");
        assertThat(jdbc.queryForObject("SELECT missing_count FROM daily_repair_runs", Integer.class)).isZero();
    }
}
