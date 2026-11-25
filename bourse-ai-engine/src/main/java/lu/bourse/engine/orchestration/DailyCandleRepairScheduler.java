package lu.bourse.engine.orchestration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class DailyCandleRepairScheduler {
    private static final Logger log = LoggerFactory.getLogger(DailyCandleRepairScheduler.class);
    private final DailyCandleRepairOrchestrator repair;
    private final boolean enabled;
    private final int maxRepairs;

    public DailyCandleRepairScheduler(
            DailyCandleRepairOrchestrator repair,
            @Value("${orchestrator.daily-repair.enabled:false}") boolean enabled,
            @Value("${orchestrator.daily-repair.max-repairs:5}") int maxRepairs) {
        if (maxRepairs < 1 || maxRepairs > 20) {
            throw new IllegalArgumentException("ORCHESTRATOR_DAILY_REPAIR_MAX_REPAIRS must be 1..20");
        }
        this.repair = repair;
        this.enabled = enabled;
        this.maxRepairs = maxRepairs;
    }

    @Scheduled(cron = "${orchestrator.daily-repair.cron:0 0 17 * * MON-FRI}", zone = "America/New_York")
    public void run() {
        if (!enabled) return;
        var summary = repair.repair(List.of(), null, null, maxRepairs);
        log.info("Scheduled daily candle repair: asOf={}, missing={}, repairs={}",
                summary.asOf(), summary.missing(), summary.repairs());
    }
}
