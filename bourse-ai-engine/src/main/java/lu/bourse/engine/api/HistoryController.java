package lu.bourse.engine.api;
import lu.bourse.engine.service.HistoryService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
@RestController
@RequestMapping("/api/v1/history")
public class HistoryController {
 private final HistoryService service;
 public HistoryController(HistoryService service){this.service=service;}
 // Local development endpoint: must be authenticated/disabled before production deployment.
 @PostMapping("/import") @ResponseStatus(HttpStatus.CREATED)
 public HistoryService.ImportResult importHistory(@RequestBody HistoryService.ImportRequest request){return service.importBars(request);}
 @GetMapping("/analysis/{symbol}")
 public HistoryService.Analysis analyze(@PathVariable String symbol,@RequestParam(defaultValue="1d") String timeframe){return service.analyze(symbol,timeframe);}
}
