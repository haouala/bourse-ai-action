package lu.bourse.engine.api;

import lu.bourse.engine.service.HistoricalBacktestService;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/backtests")
public class HistoricalBacktestController {
 private final HistoricalBacktestService service;
 public HistoricalBacktestController(HistoricalBacktestService service){this.service=service;}
 @GetMapping("/{symbol}")
 public HistoricalBacktestService.Result run(@PathVariable String symbol,
  @RequestParam(defaultValue="10") int shortWindow,
  @RequestParam(defaultValue="30") int longWindow,
  @RequestParam(defaultValue="10") int feeBps){
  return service.run(symbol,shortWindow,longWindow,feeBps);
 }
}
