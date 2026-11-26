package lu.bourse.engine.api;

import lu.bourse.engine.provider.InsiderFilingService;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequestMapping("/api/v1/insiders")
public class InsiderController {
 private final InsiderFilingService service;
 public InsiderController(InsiderFilingService service){this.service=service;}

 // SEC import triggers a network call. Restrict to authenticated operations before deployment.
 @PostMapping("/sync/{symbol}")
 public InsiderFilingService.SyncResult sync(@PathVariable String symbol,@RequestParam int cik,
  @RequestParam(defaultValue="15") int maxFilings){return service.sync(symbol,cik,maxFilings);}
 @GetMapping("/{symbol}")
 public List<InsiderFilingService.InsiderRow> history(@PathVariable String symbol,
  @RequestParam(defaultValue="100") int limit){return service.history(symbol,limit);}
 @GetMapping("/{symbol}/alerts")
 public List<InsiderFilingService.Alert> alerts(@PathVariable String symbol,
  @RequestParam(defaultValue="90") int days){return service.alerts(symbol,days);}
}
