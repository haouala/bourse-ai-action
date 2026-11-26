package lu.bourse.engine.api;
import lu.bourse.engine.service.PortfolioRiskService;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/portfolio-risk")
public class PortfolioRiskController {
 private final PortfolioRiskService service;
 public PortfolioRiskController(PortfolioRiskService service){this.service=service;}
 /** Body is a hypothetical basket, never brokerage positions. Read-only DB operation. */
 @PostMapping("/analyze")
 public PortfolioRiskService.Report assess(@RequestBody PortfolioRiskService.Request request){
  return service.analyze(request);
 }
}
