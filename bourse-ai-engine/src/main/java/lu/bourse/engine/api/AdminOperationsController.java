package lu.bourse.engine.api;

import jakarta.servlet.http.HttpServletResponse;
import lu.bourse.engine.information.InformationAnalysisService;
import lu.bourse.engine.persistence.SqlIds;
import lu.bourse.engine.seasonality.SeasonalityService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;

import static org.springframework.http.HttpStatus.BAD_REQUEST;

@RestController
@RequestMapping("/api/v1/admin")
public class AdminOperationsController {
    private final JdbcTemplate db;
    private final Clock clock;
    private final String token;
    private final ProviderController providers;
    private final ProviderQuotaController quotas;
    private final MarketMonitoringController market;
    private final BourseAiOrchestratorController orchestrator;
    private final InformationController information;
    private final SeasonalityController seasonality;
    private final DataQualityController quality;
    private final ModelMonitoringController models;

    public AdminOperationsController(JdbcTemplate db, Clock clock, @Value("${admin.monitor-token:}") String token,
                                     ProviderController providers, ProviderQuotaController quotas,
                                     MarketMonitoringController market, BourseAiOrchestratorController orchestrator,
                                     InformationController information, SeasonalityController seasonality,
                                     DataQualityController quality, ModelMonitoringController models) {
        this.db = db;
        this.clock = clock;
        this.token = token;
        this.providers = providers;
        this.quotas = quotas;
        this.market = market;
        this.orchestrator = orchestrator;
        this.information = information;
        this.seasonality = seasonality;
        this.quality = quality;
        this.models = models;
    }

    public record Input(String symbol, Integer number, String provider, String adjustment,
                        String evidenceUrl, String reason, String reviewToken) {}

    @GetMapping("/dashboard")
    public Map<String, Object> dashboard(@RequestHeader(value = "Authorization", required = false) String authorization,
                                         HttpServletResponse response) {
        authorize(authorization, response);
        Instant now = clock.instant();
        Timestamp since = Timestamp.from(now.minus(Duration.ofHours(24)));
        return Map.of(
                "checkedAt", now,
                "activeAssets", db.queryForObject("SELECT COUNT(*) FROM watchlist WHERE enabled=TRUE", Long.class),
                "imports24h", db.queryForList("""
                        SELECT provider AS "provider", status AS "status", COUNT(*) AS "count"
                        FROM import_jobs WHERE started_time>=? GROUP BY provider,status ORDER BY provider,status
                        """, since),
                "analyses24h", db.queryForObject("SELECT COUNT(*) FROM information_ai_analyses WHERE created_time>=?", Long.class, since),
                "recentAnalyses", db.queryForList("""
                        SELECT a.id AS "analysisId", j.item_id AS "itemId", a.created_time AS "finishedAt",
                               a.review_status AS "reviewStatus", j.model AS "model"
                        FROM information_ai_analyses a JOIN information_ai_jobs j ON j.id=a.job_id
                        ORDER BY a.created_time DESC,a.id DESC LIMIT 12
                        """),
                "recentAiJobs", db.queryForList("""
                        SELECT id AS "id", status AS "status", attempts AS "attempts",
                               updated_time AS "updatedAt", error_code AS "errorCode"
                        FROM information_ai_jobs ORDER BY updated_time DESC,id DESC LIMIT 12
                        """),
                "recentOperations", db.queryForList("""
                        SELECT operation AS "operation", symbol AS "symbol", status AS "status",
                               started_at AS "startedAt", finished_at AS "finishedAt"
                        FROM admin_operation_audit ORDER BY started_at DESC,id DESC LIMIT 25
                        """));
    }

    @PostMapping("/operations/{operation}")
    public Object operate(@PathVariable Operation operation, @RequestBody Input input,
                          @RequestHeader(value = "Authorization", required = false) String authorization,
                          HttpServletResponse response) {
        authorize(authorization, response);
        String symbol = input.symbol();
        if (symbol != null) {
            if (!symbol.matches("[A-Za-z0-9._-]{1,40}")) throw invalid("Invalid symbol");
            symbol = symbol.toUpperCase(Locale.ROOT);
        }
        if (operation.symbolRequired && symbol == null) throw invalid("Symbol required");
        long audit = operation.mutation ? SqlIds.insert(db, """
                INSERT INTO admin_operation_audit(operation,symbol,started_at,status)
                VALUES (?,?,?,'RUNNING')
                """, operation.name(), symbol, Timestamp.from(clock.instant())) : 0;
        try {
            Object result = execute(operation, input, symbol);
            if (audit != 0) finish(audit, "SUCCEEDED");
            return result;
        } catch (RuntimeException failure) {
            if (audit != 0) finish(audit, "FAILED");
            throw failure;
        }
    }

    private Object execute(Operation action, Input input, String symbol) {
        return switch (action) {
            case SEC_SYNC -> providers.financials(symbol, number(input, 1, Integer.MAX_VALUE));
            case SEC_FACTS -> providers.facts(symbol);
            case FMP_SYNC -> providers.profile(symbol);
            case FMP_PROFILE -> providers.getProfile(symbol);
            case DAILY_IMPORT -> providers.prices(symbol, number(input, 1, 10));
            case QUALIFY_DAILY -> {
                seasonality.qualify(symbol, new SeasonalityService.Qualification(input.provider(),
                        input.adjustment(), input.evidenceUrl()));
                yield Map.of("status", "SUCCEEDED");
            }
            case AI_ANALYSES -> information.analyses(number(input, 1, Integer.MAX_VALUE));
            case AI_REVIEWS -> information.reviews(number(input, 1, Integer.MAX_VALUE));
            case AI_APPROVE, AI_REJECT -> {
                information.review(number(input, 1, Integer.MAX_VALUE), input.reviewToken(),
                        new InformationAnalysisService.Review(action == Operation.AI_APPROVE,
                                input.reason(), Map.of()));
                yield Map.of("status", "SUCCEEDED");
            }
            case DAILY_REPAIR -> orchestrator.repairDaily(new BourseAiOrchestratorController.RepairRequest(
                    symbol == null ? null : java.util.List.of(symbol), null, null, number(input, 1, 20)));
            case MARKET_ENQUEUE -> market.enqueue();
            case MARKET_RUN_NEXT -> market.run();
            case ORCHESTRATOR_ENQUEUE -> orchestrator.enqueue();
            case ORCHESTRATOR_RUN -> orchestrator.run(number(input, 1, 20));
            case ORCHESTRATOR_STATUS -> orchestrator.status();
            case ORCHESTRATOR_RUNS -> orchestrator.runs();
            case MARKET_TASKS -> market.tasks();
            case PRICE_QUALITY -> quality.report(symbol, "1d");
            case MODEL_MONITORING -> models.report(symbol);
            case QUOTAS -> quotas.usage();
        };
    }

    private void finish(long id, String status) {
        db.update("UPDATE admin_operation_audit SET status=?,finished_at=? WHERE id=?",
                status, Timestamp.from(clock.instant()), id);
    }

    private void authorize(String authorization, HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        AdminStatusController.requireToken(token, authorization);
    }

    private static int number(Input input, int min, int max) {
        if (input.number() == null || input.number() < min || input.number() > max) throw invalid("Number out of range");
        return input.number();
    }

    private static ResponseStatusException invalid(String detail) {
        return new ResponseStatusException(BAD_REQUEST, detail);
    }

    public enum Operation {
        SEC_SYNC(true, true), SEC_FACTS(true, false), FMP_SYNC(true, true), FMP_PROFILE(true, false),
        DAILY_IMPORT(true, true), QUALIFY_DAILY(true, true),
        AI_ANALYSES(false, false), AI_REVIEWS(false, false), AI_APPROVE(false, true), AI_REJECT(false, true),
        DAILY_REPAIR(false, true), MARKET_ENQUEUE(false, true), MARKET_RUN_NEXT(false, true),
        ORCHESTRATOR_ENQUEUE(false, true), ORCHESTRATOR_RUN(false, true),
        ORCHESTRATOR_STATUS(false, false), ORCHESTRATOR_RUNS(false, false), MARKET_TASKS(false, false),
        PRICE_QUALITY(true, false), MODEL_MONITORING(true, false), QUOTAS(false, false);

        final boolean symbolRequired;
        final boolean mutation;

        Operation(boolean symbolRequired, boolean mutation) {
            this.symbolRequired = symbolRequired;
            this.mutation = mutation;
        }
    }
}
