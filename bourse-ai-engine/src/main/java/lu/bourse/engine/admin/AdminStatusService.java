package lu.bourse.engine.admin;

import lu.bourse.engine.persistence.SqlIds;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.function.Supplier;

@Service
public class AdminStatusService {
    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final AdminMarketCoverage market;
    private final lu.bourse.engine.provider.AlpacaIntradayService alpaca;
    private final boolean repairEnabled;
    private final CronExpression repairCron;

    public record Snapshot(Instant checkedAt, String backend, String database, Repair repair,
                           AdminMarketCoverage.Coverage market, List<Batch> batches,
                           List<Provider> providers, Ml ml, lu.bourse.engine.provider.AlpacaIntradayService.Status alpaca) {}
    public record Repair(String state, Instant expectedAt, Instant nextAt, Instant startedAt, Instant finishedAt,
                         Integer missing, Integer attempted, Integer inserted) {}
    public record Batch(String name, String state, Instant lastSuccess, Instant lastActivity,
                        int due, int expired, int failed24h) {}
    public record Provider(String code, Instant lastSuccess, Instant lastActivity,
                           int successful24h, int failed24h) {}
    public record Ml(String mode, String horizon, Instant lastRun, Instant dataCutoff, int predictions) {}

    public AdminStatusService(JdbcTemplate jdbc, Clock clock, AdminMarketCoverage market,
            lu.bourse.engine.provider.AlpacaIntradayService alpaca,
            @Value("${orchestrator.daily-repair.enabled:false}") boolean repairEnabled,
            @Value("${orchestrator.daily-repair.cron:0 0 17 * * MON-FRI}") String repairCron) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.market = market;
        this.alpaca = alpaca;
        this.repairEnabled = repairEnabled && !"-".equals(repairCron);
        this.repairCron = this.repairEnabled ? CronExpression.parse(repairCron) : null;
    }

    public Snapshot read() {
        Instant now = clock.instant();
        try {
            jdbc.queryForObject("SELECT 1", Integer.class);
        } catch (DataAccessException unavailable) {
            return new Snapshot(now, "UP", "DOWN", null, null, null, null, null, null);
        }
        return new Snapshot(now, "UP", "UP", check(() -> repair(now)), check(() -> market.read(now)),
                List.of(
                        batch("MARKET_SYNC", "market_sync_tasks", "SUCCEEDED", "FAILED", now),
                        batch("RESEARCH", "orchestration_assessments", "SUCCEEDED", "FAILED", now),
                        batch("INFORMATION_AI", "information_ai_jobs", "SUCCEEDED", "FAILED", now),
                        batch("GDELT", "gdelt_bulk_runs", "SUCCESS", "FAILED", now)),
                check(() -> providers(now)), check(this::ml), check(alpaca::status));
    }

    private Repair repair(Instant now) {
        var latest = jdbc.queryForList("""
                SELECT started_at,finished_at,status,missing_count,attempted_count,inserted_count
                FROM daily_repair_runs ORDER BY started_at DESC,id DESC LIMIT 1
                """).stream().findFirst();
        Instant previous = null;
        Instant nextAt = null;
        if (repairEnabled) {
            var localNow = now.atZone(ZoneId.of("America/New_York"));
            var next = repairCron.next(localNow);
            nextAt = next == null ? null : next.toInstant();
            long low = now.minus(8, ChronoUnit.DAYS).getEpochSecond();
            long high = now.getEpochSecond();
            // Find the last scheduled trigger without iterating every occurrence of a custom cron.
            while (low <= high) {
                long middle = low + (high - low) / 2;
                var trigger = repairCron.next(Instant.ofEpochSecond(middle).atZone(localNow.getZone()));
                if (trigger != null && !trigger.isAfter(localNow)) {
                    previous = trigger.toInstant();
                    low = middle + 1;
                } else {
                    high = middle - 1;
                }
            }
        }
        var row = latest.orElse(null);
        Instant started = row == null ? null : instant(row.get("started_at"));
        Instant finished = row == null ? null : instant(row.get("finished_at"));
        String state;
        if (!repairEnabled) state = "DISABLED";
        else if (previous != null && (started == null || started.isBefore(previous))) {
            state = now.isAfter(previous.plus(30, ChronoUnit.MINUTES)) ? "LATE" : "WAITING";
        } else if (row == null) state = "NEVER_RUN";
        else if ("RUNNING".equals(row.get("status")) && started.plus(30, ChronoUnit.MINUTES).isBefore(now)) state = "LATE";
        else state = (String) row.get("status");
        return new Repair(state, previous, nextAt, started, finished,
                row == null ? null : (Integer) row.get("missing_count"),
                row == null ? null : (Integer) row.get("attempted_count"),
                row == null ? null : (Integer) row.get("inserted_count"));
    }

    private Batch batch(String name, String table, String success, String failure, Instant now) {
        Batch result = check(() -> jdbc.queryForObject("""
                SELECT MAX(CASE WHEN status=? THEN updated_time END) AS last_success,
                       MAX(updated_time) AS last_activity,
                       COUNT(CASE WHEN status IN ('PENDING','DEFERRED') AND next_attempt_at<? THEN 1 END) AS due,
                       COUNT(CASE WHEN status='RUNNING' AND lease_until<? THEN 1 END) AS expired,
                       COUNT(CASE WHEN status=? AND updated_time>=? THEN 1 END) AS failed
                FROM %s
                """.formatted(table), (row, index) -> {
            int due = row.getInt("due"), expired = row.getInt("expired"), failed = row.getInt("failed");
            Instant activity = instant(row.getObject("last_activity"));
            return new Batch(name, due > 0 || expired > 0 ? "LATE" : failed > 0 ? "ISSUES" :
                    activity == null ? "NEVER_RUN" : "NO_BACKLOG", instant(row.getObject("last_success")),
                    activity, due, expired, failed);
        }, success, Timestamp.from(now.minus(15, ChronoUnit.MINUTES)), Timestamp.from(now), failure,
                Timestamp.from(now.minus(1, ChronoUnit.DAYS))));
        return result == null ? new Batch(name, "UNKNOWN", null, null, 0, 0, 0) : result;
    }

    private List<Provider> providers(Instant now) {
        Timestamp since = Timestamp.from(now.minus(1, ChronoUnit.DAYS));
        return jdbc.query("""
                SELECT provider,MAX(CASE WHEN status='SUCCESS' THEN finished_time END) AS last_success,
                  MAX(updated_time) AS last_activity,
                  COUNT(CASE WHEN status='SUCCESS' AND finished_time>=? THEN 1 END) AS successful,
                  COUNT(CASE WHEN status='ERROR' AND finished_time>=? THEN 1 END) AS failed
                FROM import_jobs GROUP BY provider ORDER BY provider
                """, (row, index) -> new Provider(row.getString("provider"), instant(row.getObject("last_success")),
                instant(row.getObject("last_activity")), row.getInt("successful"), row.getInt("failed")), since, since);
    }

    private Ml ml() {
        return jdbc.query("""
                SELECT r.horizon,r.created_at,r.data_cutoff,
                    (SELECT COUNT(*) FROM ml_market_predictions p WHERE p.run_id=r.run_id) AS predictions
                FROM ml_market_runs r ORDER BY r.created_at DESC,r.run_id DESC LIMIT 1
                """, (row, index) -> new Ml("MANUAL", row.getString("horizon"), instant(row.getObject("created_at")),
                instant(row.getObject("data_cutoff")), row.getInt("predictions")))
                .stream().findFirst().orElse(new Ml("MANUAL", null, null, null, 0));
    }

    private static Instant instant(Object value) {
        return value == null ? null : SqlIds.instant(value);
    }

    private static <T> T check(Supplier<T> query) {
        try {
            return query.get();
        } catch (DataAccessException unavailable) {
            return null;
        }
    }
}
