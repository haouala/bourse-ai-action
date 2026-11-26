package lu.bourse.engine.api;

import lu.bourse.engine.service.TechnicalAnalysisService;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/technical-analysis")
public class TechnicalAnalysisController {
    private final TechnicalAnalysisService service;
    public TechnicalAnalysisController(TechnicalAnalysisService service){this.service=service;}
    @GetMapping("/{symbol}")
    public TechnicalAnalysisService.Report analyze(@PathVariable String symbol,@RequestParam(defaultValue="1d")String timeframe){return service.analyze(symbol,timeframe);}
}
