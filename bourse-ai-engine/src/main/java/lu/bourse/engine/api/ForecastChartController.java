package lu.bourse.engine.api;

import lu.bourse.engine.service.ForecastChartService;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/charts")
public class ForecastChartController {
 private final ForecastChartService charts;
 public ForecastChartController(ForecastChartService charts){this.charts=charts;}

 @GetMapping("/{symbol}/history")
 public ForecastChartService.HistoricalChart history(@PathVariable String symbol,
  @RequestParam(defaultValue="250") int limit){return charts.historical(symbol,limit);}

 @GetMapping("/{symbol}/scenarios")
 public ForecastChartService.ScenarioChart scenarios(@PathVariable String symbol){
  return charts.scenarios(symbol);
 }

 @GetMapping("/{symbol}/predictions")
 public ForecastChartService.PredictionChart predictionErrors(@PathVariable String symbol,
  @RequestParam(defaultValue="100") int limit){return charts.predictionErrors(symbol,limit);}

 @GetMapping("/{symbol}/ml")
 public ForecastChartService.MlChart mlHistory(@PathVariable String symbol,
  @RequestParam(defaultValue="100") int limit){return charts.mlExperiments(symbol,limit);}
}
