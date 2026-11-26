package lu.bourse.engine.api;
import lu.bourse.engine.explanation.ExplanationService;
import org.springframework.web.bind.annotation.*;
import java.util.*;
@RestController
@RequestMapping("/api/v1/explanations")
public class ExplanationController {
    private final ExplanationService service;
    public ExplanationController(ExplanationService service){this.service=service;}
    @GetMapping("/rulesets") public List<Map<String,Object>> rules(){return service.rules();}
    @PostMapping("/rulesets") public Map<String,Long> rules(@RequestBody ExplanationService.NewRules r){return Map.of("id",service.createRules(r));}
    @PostMapping("/{symbol}") public ExplanationService.Analysis calculate(@PathVariable String symbol,@RequestParam(defaultValue="MONTH") ExplanationService.Horizon horizon,@RequestParam(required=false) Long rulesetId){return service.calculate(symbol,horizon,rulesetId);}
    @GetMapping("/{symbol}/history") public List<Map<String,Object>> history(@PathVariable String symbol,@RequestParam(defaultValue="MONTH") ExplanationService.Horizon horizon){return service.history(symbol,horizon);}
    @GetMapping("/snapshots/{id}") public Map<String,Object> get(@PathVariable long id){return service.get(id);}
}
