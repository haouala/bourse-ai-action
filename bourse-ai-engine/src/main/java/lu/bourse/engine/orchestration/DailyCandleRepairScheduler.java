package lu.bourse.engine.orchestration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.jdbc.core.JdbcTemplate;
import lu.bourse.engine.persistence.SqlIds;
import java.sql.Timestamp;
import java.time.Clock;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class DailyCandleRepairScheduler {
    private static final Logger log = LoggerFactory.getLogger(DailyCandleRepairScheduler.class);
    private final DailyCandleRepairOrchestrator repair;
    private final boolean enabled;
    private final int maxRepairs;
    private final JdbcTemplate jdbc;
    private final Clock clock;

    public DailyCandleRepairScheduler(
            DailyCandleRepairOrchestrator repair, JdbcTemplate jdbc, Clock clock,
            @Value("${orchestrator.daily-repair.enabled:false}") boolean enabled,
            @Value("${orchestrator.daily-repair.max-repairs:5}") int maxRepairs) {
        if (maxRepairs < 1 || maxRepairs > 20) {
            throw new IllegalArgumentException("ORCHESTRATOR_DAILY_REPAIR_MAX_REPAIRS must be 1..20");
        }
        this.repair = repair;
        this.jdbc = jdbc;
        this.clock = clock;
        this.enabled = enabled;
        this.maxRepairs = maxRepairs;
    }

    @Scheduled(cron = "${orchestrator.daily-repair.cron:0 0 17 * * MON-FRI}", zone = "America/New_York")
    public void run() {
        if (!enabled) return;
        long run = SqlIds.insert(jdbc, """
                INSERT INTO daily_repair_runs(started_at,status) VALUES (?,'RUNNING')
                """, Timestamp.from(clock.instant()));
        try {
            var summary = repair.repair(List.of(), null, null, maxRepairs);
            long resolved = summary.repairs().stream()
                    .filter(r -> "INSERTED".equals(r.status()) || "ALREADY_PRESENT".equals(r.status())).count();
            long inserted = summary.repairs().stream().filter(r -> "INSERTED".equals(r.status())).count();
            jdbc.update("""
                    UPDATE daily_repair_runs SET finished_at=?,status=?,missing_count=?,attempted_count=?,inserted_count=? WHERE id=?
                    """, Timestamp.from(clock.instant()), resolved == summary.missing() ? "SUCCESS" : "PARTIAL",
                    summary.missing(), summary.repairs().size(), inserted, run);
            log.info("Scheduled daily candle repair: asOf={}, missing={}, repairs={}",
                    summary.asOf(), summary.missing(), summary.repairs());
        } catch (RuntimeException failure) {
            jdbc.update("UPDATE daily_repair_runs SET finished_at=?,status='FAILED' WHERE id=?",
                    Timestamp.from(clock.instant()), run);
            log.error("Scheduled daily candle repair failed, run={}", run);
        }
    }
}
