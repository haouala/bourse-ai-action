package lu.bourse.engine.api;
import lu.bourse.engine.service.ResearchAlertsService;
import org.springframework.web.bind.annotation.*;
import java.math.BigDecimal;
import java.util.List;
@RestController
@RequestMapping("/api/v1/research-alerts")
public class ResearchAlertsController {
 private final ResearchAlertsService service;
 public ResearchAlertsController(ResearchAlertsService service){this.service=service;}
 public record Create(String symbol,String type,BigDecimal threshold){}
 @PostMapping("/rules")
 public ResearchAlertsService.Rule create(@RequestBody Create r){return service.create(r.symbol(),r.type(),r.threshold());}
 @GetMapping("/rules/{symbol}")
 public List<ResearchAlertsService.Rule> rules(@PathVariable String symbol){return service.rules(symbol);}
 @PostMapping("/rules/{id}/disable")
 public ResearchAlertsService.Rule disable(@PathVariable long id){return service.disable(id);}
 @PostMapping("/{symbol}/check")
 public ResearchAlertsService.Check check(@PathVariable String symbol){return service.check(symbol);}
 @GetMapping("/{symbol}/events")
 public List<ResearchAlertsService.Event> events(@PathVariable String symbol,
  @RequestParam(defaultValue="50") int limit){return service.events(symbol,limit);}
}
