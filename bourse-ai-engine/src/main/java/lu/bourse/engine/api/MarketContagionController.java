package lu.bourse.engine.api;

import lu.bourse.engine.service.MarketContagionService;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/contagion")
public class MarketContagionController {
 private final MarketContagionService service;
 public MarketContagionController(MarketContagionService service){this.service=service;}
 @GetMapping("/{symbol}")
 public MarketContagionService.Report analyze(@PathVariable String symbol,
  @RequestParam(defaultValue="250") int lookbackSessions,
  @RequestParam(defaultValue="15") int limit){
  return service.analyze(symbol,lookbackSessions,limit);
 }
}
