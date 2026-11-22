package lu.bourse.engine.api;

import lu.bourse.engine.service.DataQualityService;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/quality")
public class DataQualityController {
 private final DataQualityService service;
 public DataQualityController(DataQualityService service){this.service=service;}
 @GetMapping("/{symbol}")
 public DataQualityService.Report report(@PathVariable String symbol,@RequestParam(defaultValue="1d") String timeframe){
  return service.check(symbol,timeframe);
 }
}
