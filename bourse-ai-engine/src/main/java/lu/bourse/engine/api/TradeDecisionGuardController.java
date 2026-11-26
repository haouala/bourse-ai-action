package lu.bourse.engine.api;
import lu.bourse.engine.service.TradeDecisionGuardService;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/trade-guard")
public class TradeDecisionGuardController {
 private final TradeDecisionGuardService service;
 public TradeDecisionGuardController(TradeDecisionGuardService service){this.service=service;}
 @PostMapping("/review")
 public TradeDecisionGuardService.Review review(@RequestBody TradeDecisionGuardService.Request request){
  return service.review(request);
 }
}
