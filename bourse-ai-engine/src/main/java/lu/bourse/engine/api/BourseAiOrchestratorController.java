package lu.bourse.engine.api;

import lu.bourse.engine.orchestration.BourseAiOrchestratorService;
import lu.bourse.engine.orchestration.DailyCandleRepairOrchestrator;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * Local development operations only. Do not expose these endpoints publicly before
 * operator authentication/authorization and rate limits are implemented.
 */
@RestController
@RequestMapping("/api/v1/orchestrator")
public class BourseAiOrchestratorController {
    private final BourseAiOrchestratorService orchestrator;
    private final DailyCandleRepairOrchestrator dailyRepair;
    public BourseAiOrchestratorController(BourseAiOrchestratorService orchestrator,
                                         DailyCandleRepairOrchestrator dailyRepair) {
        this.orchestrator=orchestrator;
        this.dailyRepair=dailyRepair;
    }
    @GetMapping("/status")
    public Map<String,Object> status() { return orchestrator.status(); }

    @GetMapping("/runs")
    public List<Map<String,Object>> runs() { return orchestrator.recent(); }

    /** Explicit local start, bounded to 1..20 assessments. No external provider calls. */
    @PostMapping("/run")
    public BourseAiOrchestratorService.Summary run(
            @RequestParam(defaultValue="5") int maxAssets) {
        if(maxAssets<1 || maxAssets>20)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"maxAssets must be 1..20");
        return orchestrator.runBatch(maxAssets);
    }

    public record RepairRequest(List<String> symbols, LocalDate start, Instant asOf, Integer maxRepairs) {}

    @PostMapping("/repair-daily")
    public DailyCandleRepairOrchestrator.Summary repairDaily(@RequestBody RepairRequest request) {
        return dailyRepair.repair(request.symbols() == null ? List.of() : request.symbols(),
                request.start(), request.asOf(), request.maxRepairs() == null ? 5 : request.maxRepairs());
    }

    /** Prepare only newer canonical daily candles. This action is idempotent. */
    @PostMapping("/enqueue")
    public Map<String,Integer> enqueue() {
        return Map.of("queued",orchestrator.enqueue());
    }
}
