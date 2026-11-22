package lu.bourse.engine.api;
import lu.bourse.engine.service.MarketRegimeService;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/regimes")
public class MarketRegimeController {
 private final MarketRegimeService service;
 public MarketRegimeController(MarketRegimeService service){this.service=service;}
 @GetMapping("/{symbol}")
 public MarketRegimeService.Report current(@PathVariable String symbol){return service.assess(symbol);}
}
