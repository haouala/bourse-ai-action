package lu.bourse.engine.api;
import lu.bourse.engine.service.IntelligentScoringService;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/scoring")
public class IntelligentScoringController {
 private final IntelligentScoringService service;
 public IntelligentScoringController(IntelligentScoringService service){this.service=service;}
 @GetMapping("/{symbol}")
 public IntelligentScoringService.Report score(@PathVariable String symbol,
  @RequestParam(defaultValue="30") int horizonDays){return service.score(symbol,horizonDays);}
}
