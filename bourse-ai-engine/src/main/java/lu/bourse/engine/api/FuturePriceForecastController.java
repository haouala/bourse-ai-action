package lu.bourse.engine.api;

import lu.bourse.engine.service.FuturePriceForecastService;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/forecasts")
public class FuturePriceForecastController {
 private final FuturePriceForecastService service;
 public FuturePriceForecastController(FuturePriceForecastService service){this.service=service;}

 /** Read-only historical price scenario preview (no forecast registered). */
 @GetMapping("/{symbol}")
 public FuturePriceForecastService.Forecast preview(@PathVariable String symbol,
  @RequestParam(defaultValue="30") int horizonDays){
  return service.preview(symbol,horizonDays);
 }

 /** Explicitly register a central price forecast with the Module 3 tracker. */
 @PostMapping("/{symbol}/issue")
 public FuturePriceForecastService.Issued issue(@PathVariable String symbol,
  @RequestParam(defaultValue="30") int horizonDays){
  return service.issue(symbol,horizonDays);
 }
}
