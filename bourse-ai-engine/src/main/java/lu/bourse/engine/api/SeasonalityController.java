package lu.bourse.engine.api;
import lu.bourse.engine.seasonality.SeasonalityService;
import org.springframework.web.bind.annotation.*;
import java.time.Instant;
import java.util.*;
@RestController
@RequestMapping("/api/v1/seasonality/{symbol}")
public class SeasonalityController {
    private final SeasonalityService service;
    public SeasonalityController(SeasonalityService service){this.service=service;}
    @GetMapping public SeasonalityService.Analysis analyze(@PathVariable String symbol,@RequestParam(required=false) Instant asOf){return service.analyze(symbol,asOf);}
    @PutMapping("/qualification") public void qualify(@PathVariable String symbol,@RequestBody SeasonalityService.Qualification q){service.qualify(symbol,q);}
    @PostMapping("/snapshots") public Map<String,Object> save(@PathVariable String symbol){return service.save(symbol);}
    @GetMapping("/snapshots") public List<Map<String,Object>> history(@PathVariable String symbol){return service.history(symbol);}
}
