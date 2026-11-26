package lu.bourse.engine.orchestration;

import lu.bourse.engine.persistence.SqlIds;
import lu.bourse.engine.service.DataQualityService;
import lu.bourse.engine.service.IntelligentScoringService;
import lu.bourse.engine.service.MarketRegimeService;
import lu.bourse.engine.service.ResearchAlertsService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

/**
 * Coordinates existing local financial analyses after an observed DAILY candle is imported.
 *
 * <p>Does not call Twelve Data/EODHD, does not generate forecasts, train models,
 * trade or send notifications. Those responsibilities remain in their respective
 * services. All pipeline results are persisted, and a successful candle is never
 * reanalysed merely because the application restarts.</p>
 *
 * <p>Claim transactions hold a short row lock, while calculations execute OUTSIDE
 * the transaction. Expired leases are reclaimable. Scheduling is opt-in.</p>
 */
@Service
public class BourseAiOrchestratorService {
    private static final int MAX_BATCH = 20;
    private static final int MAX_ATTEMPTS = 3;
    private static final Duration LEASE = Duration.ofMinutes(10);
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final DataQualityService quality;
    private final IntelligentScoringService scoring;
    private final MarketRegimeService regimes;
    private final ResearchAlertsService alerts;
    private final boolean enabled;
    private final int batchSize;

    public record Summary(int queued, int processed, int successful, int partial, int deferred,
                          int failed, String disclaimer) {}
    public record Outcome(long assessmentId, String symbol, String status,
                          String quality, String score, String regime, int alertsTriggered,
                          String reason) {}
    private record Claimed(long id, long assetId, String symbol, Instant candleTime, int attempts) {}

    public BourseAiOrchestratorService(
            JdbcTemplate jdbc, PlatformTransactionManager manager, Clock clock,
            DataQualityService quality, IntelligentScoringService scoring,
            MarketRegimeService regimes, ResearchAlertsService alerts,
            @Value("${orchestrator.enabled:false}") boolean enabled,
            @Value("${orchestrator.max-per-tick:5}") int batchSize) {
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(manager);
        this.clock = clock;
        this.quality = quality;
        this.scoring = scoring;
        this.regimes = regimes;
        this.alerts = alerts;
        this.enabled = enabled;
        if (batchSize < 1 || batchSize > MAX_BATCH)
            throw new IllegalArgumentException("ORCHESTRATOR_MAX_PER_TICK must be 1..20");
        this.batchSize = batchSize;
    }

    /** Idempotent enqueue; only the latest observed daily candle for an enabled asset. */
    public int enqueue() {
        return Objects.requireNonNull(tx.execute(status -> {
            jdbc.queryForObject("SELECT id FROM orchestration_guard WHERE id=1 FOR UPDATE", Integer.class);
            int queued = 0;
            var latest = jdbc.queryForList("""
                SELECT w.asset_id,MAX(c.open_time) AS candle_time
                FROM watchlist w JOIN market_candles c ON c.asset_id=w.asset_id
                WHERE w.enabled=TRUE AND c.timeframe='1d' AND c.open_time<=CURRENT_TIMESTAMP
                GROUP BY w.asset_id ORDER BY w.asset_id
                """);
            for (var row : latest) {
                long assetId = ((Number) row.get("asset_id")).longValue();
                Timestamp candleTime = Timestamp.from(SqlIds.instant(row.get("candle_time")));
                int existing = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM orchestration_assessments WHERE asset_id=? AND candle_time=?",
                    Integer.class, assetId, candleTime);
                if (existing != 0) continue;
                queued += jdbc.update("""
                    INSERT INTO orchestration_assessments(asset_id,candle_time,status,next_attempt_at)
                    VALUES (?,?,'DEFERRED',?)
                    """, assetId, candleTime, Timestamp.from(clock.instant()));
            }
            return queued;
        }));
    }

    /** Claim exactly one due assessment without holding locks while doing financial analysis. */
    private Claimed claim() {
        return tx.execute(status -> {
            jdbc.queryForObject("SELECT id FROM orchestration_guard WHERE id=1 FOR UPDATE", Integer.class);
            Instant now = clock.instant();
            var due = jdbc.queryForList("""
                SELECT o.id,o.asset_id,o.candle_time,o.attempts,a.symbol
                FROM orchestration_assessments o
                JOIN assets a ON a.id=o.asset_id
                JOIN watchlist w ON w.asset_id=o.asset_id
                WHERE w.enabled=TRUE
                  AND ((o.status='DEFERRED' AND o.next_attempt_at<=?)
                     OR (o.status='RUNNING' AND o.lease_until<=?))
                  AND o.attempts<?
                ORDER BY o.candle_time,o.id LIMIT 1
                """, Timestamp.from(now),Timestamp.from(now),MAX_ATTEMPTS);
            if (due.isEmpty()) return null;
            var row = due.getFirst();
            long id = ((Number)row.get("id")).longValue();
            int count = ((Number)row.get("attempts")).intValue()+1;
            jdbc.update("""
                UPDATE orchestration_assessments
                SET status='RUNNING',attempts=?,started_at=?,lease_until=?,updated_time=?
                WHERE id=?
                """,count,Timestamp.from(now),Timestamp.from(now.plus(LEASE)),Timestamp.from(now),id);
            return new Claimed(id, ((Number)row.get("asset_id")).longValue(),
                (String)row.get("symbol"), SqlIds.instant(row.get("candle_time")), count);
        });
    }

    /** Processes one task. A missing or unreliable input is an explicit partial, never a bullish zero. */
    public Outcome runNext() {
        Claimed task = claim();
        if (task == null) return new Outcome(0,"","IDLE",null,null,null,0,"NO_DUE_ASSESSMENT");
        String qualityStatus = null, scoreStatus = null, regimeStatus = null;
        int triggered = 0;
        try {
            var report = quality.check(task.symbol(),"1d");
            qualityStatus = report.status();
            // Avoid generating positive market signals on blocked or obsolete data.
            if ("BLOCKED".equals(report.status()) || report.latestCandle() == null ||
                !report.latestCandle().equals(task.candleTime())) {
                finish(task,"PARTIAL",qualityStatus,"NOT_EVALUATED","NOT_EVALUATED",0,
                    "DATA_BLOCKED_OR_SUPERSEDED",clock.instant());
                return new Outcome(task.id(),task.symbol(),"PARTIAL",qualityStatus,
                    "NOT_EVALUATED","NOT_EVALUATED",0,"DATA_BLOCKED_OR_SUPERSEDED");
            }
            // Regime requires 65 independent daily closes. A shorter history is a final
            // data-readiness state for this candle, not an operational failure to retry
            // repeatedly while the provider is still backfilling.
            if (report.candleCount()<65) {
                finish(task,"PARTIAL",qualityStatus,"INSUFFICIENT_HISTORY",
                    "INSUFFICIENT_HISTORY",0,"NEED_65_DAILY_CLOSES",clock.instant());
                return new Outcome(task.id(),task.symbol(),"PARTIAL",qualityStatus,
                    "INSUFFICIENT_HISTORY","INSUFFICIENT_HISTORY",0,"NEED_65_DAILY_CLOSES");
            }
            // Scoring and regime are exploratory. Insufficient data is NOT a transient provider error.
            var score = scoring.score(task.symbol(),30);
            scoreStatus = score.status();
            var regime = regimes.assess(task.symbol());
            regimeStatus = regime.status();
            // Research alerts use a separate idempotency key (rule + observed candle).
            // Evaluate only after quality is usable and the calculation has completed.
            triggered = alerts.check(task.symbol()).triggered();
            boolean ready = score.opportunityScore()!=null && regime.signal()!=null;
            String result = ready ? "SUCCEEDED" : "PARTIAL";
            finish(task,result,qualityStatus,scoreStatus,regimeStatus,triggered,
                ready?null:"INSUFFICIENT_RESEARCH_HISTORY",clock.instant());
            return new Outcome(task.id(),task.symbol(),result,qualityStatus,scoreStatus,regimeStatus,
                triggered,ready?null:"INSUFFICIENT_RESEARCH_HISTORY");
        } catch (ResponseStatusException e) {
            int http = e.getStatusCode().value();
            if (http == 422 || http == 404 || http == 400) {
                finish(task,"PARTIAL",qualityStatus,scoreStatus,regimeStatus,triggered,
                    "ANALYSIS_HTTP_"+http,clock.instant());
                return new Outcome(task.id(),task.symbol(),"PARTIAL",qualityStatus,scoreStatus,
                    regimeStatus,triggered,"ANALYSIS_HTTP_"+http);
            }
            return retry(task,qualityStatus,scoreStatus,regimeStatus,"ANALYSIS_HTTP_"+http);
        } catch (RuntimeException e) {
            // Never log exception strings here: they may contain provider secrets or sensitive data.
            return retry(task,qualityStatus,scoreStatus,regimeStatus,"ANALYSIS_FAILURE");
        }
    }

    private Outcome retry(Claimed task,String q,String s,String r,String reason) {
        String state=task.attempts()>=MAX_ATTEMPTS?"FAILED":"DEFERRED";
        Instant retry=clock.instant().plus(Duration.ofMinutes(5L * task.attempts()));
        finish(task,state,q,s,r,0,reason,retry);
        return new Outcome(task.id(),task.symbol(),state,q,s,r,0,reason);
    }

    private void finish(Claimed task,String state,String q,String s,String r,int alertsCount,
                        String error,Instant next) {
        Instant now=clock.instant();
        // Compare-and-set by assessment id, attempt number and status; a stale worker
        // must not overwrite a newer lease owner's result.
        int updated=jdbc.update("""
            UPDATE orchestration_assessments
            SET status=?,quality_status=?,scoring_status=?,regime_status=?,
                alerts_triggered=?,error_code=?,lease_until=NULL,next_attempt_at=?,
                finished_at=?,updated_time=?
            WHERE id=? AND attempts=? AND status='RUNNING'
            """,state,q,s,r,alertsCount,error,Timestamp.from(next),
            Timestamp.from(now),Timestamp.from(now),task.id(),task.attempts());
        if (updated == 0) throw new IllegalStateException("ORCHESTRATION_LEASE_LOST");
    }

    /** Small bounded batch, no infinite loops, no model training or provider credit spending. */
    public Summary runBatch(int requested) {
        if (requested<1 || requested>MAX_BATCH)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"batch size must be 1..20");
        int queued=enqueue();
        int succeeded=0,partial=0,deferred=0,failed=0,processed=0;
        for (int i=0;i<requested;i++) {
            Outcome outcome=runNext();
            if ("IDLE".equals(outcome.status())) break;
            processed++;
            switch(outcome.status()){
                case "SUCCEEDED" -> succeeded++;
                case "PARTIAL" -> partial++;
                case "DEFERRED" -> deferred++;
                case "FAILED" -> failed++;
                default -> throw new IllegalStateException("Unexpected pipeline status");
            }
        }
        return new Summary(queued,processed,succeeded,partial,deferred,failed,
            "Research only. No orders, provider calls, ML training or push delivery.");
    }

    /** Scheduler is opt-in; market/news schedulers remain independent and retain their quotas. */
    @Scheduled(fixedDelayString="${orchestrator.delay-ms:300000}")
    public void scheduled() {
        if(enabled) runBatch(batchSize);
    }

    public List<Map<String,Object>> recent() {
        return jdbc.queryForList("""
            SELECT o.id,a.symbol,o.candle_time,o.status,o.attempts,o.quality_status,
              o.scoring_status,o.regime_status,o.alerts_triggered,o.error_code,
              o.started_at,o.finished_at,o.next_attempt_at
            FROM orchestration_assessments o JOIN assets a ON a.id=o.asset_id
            ORDER BY o.id DESC LIMIT 100
            """);
    }

    public Map<String,Object> status() {
        var counts=jdbc.queryForList(
            "SELECT status,COUNT(*) AS total FROM orchestration_assessments GROUP BY status");
        return Map.of("enabled",enabled,"maxPerTick",batchSize,"assessments",counts,
            "mode","LOCAL_RESEARCH_ONLY","providerCollection","HANDLED_BY_EXISTING_MARKET_AND_INFORMATION_SCHEDULERS");
    }
}
