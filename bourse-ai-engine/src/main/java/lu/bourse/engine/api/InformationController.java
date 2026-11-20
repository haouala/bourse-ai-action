package lu.bourse.engine.api;
import lu.bourse.engine.information.*;
import org.springframework.web.bind.annotation.*;
import java.util.*;
@RestController
@RequestMapping("/api/v1/information")
public class InformationController {
    private final InformationService service;private final InformationWorkService work;private final InformationAnalysisService analyses;
    public InformationController(InformationService service,InformationWorkService work,InformationAnalysisService analyses){this.service=service;this.work=work;this.analyses=analyses;}
    public record Enable(boolean enabled){}
    @GetMapping("/sources") public List<Map<String,Object>> sources(){return service.sources();}
    @PutMapping("/sources/{code}/enabled") public void enable(@PathVariable String code,@RequestBody Enable request){service.enable(code,request.enabled());}
    @PostMapping("/sources/{code}/sync") public Map<String,Object> sync(@PathVariable String code){return service.sync(code);}
    @GetMapping("/items") public List<Map<String,Object>> items(@RequestParam(required=false) String source){return service.items(source);}
    @GetMapping("/items/{id}/mentions") public List<Map<String,Object>> mentions(@PathVariable long id){return service.mentions(id);}
    @GetMapping("/runs") public List<Map<String,Object>> runs(){return service.runs();}
    @PostMapping("/items/{id}/attributions") public Map<String,Long> attribute(@PathVariable long id,@RequestBody InformationService.Attribution r){return service.attribute(id,r);}
    @GetMapping("/jobs") public List<Map<String,Object>> jobs(){return work.jobs();}
    @GetMapping("/items/{id}/contents") public List<Map<String,Object>> contents(@PathVariable long id){return work.contents(id);}
    @GetMapping("/items/{id}/analyses") public List<Map<String,Object>> analyses(@PathVariable long id){return analyses.analyses(id);}
    @GetMapping("/assets/{symbol}") public Map<String,Object> asset(@PathVariable String symbol,@RequestParam(defaultValue="DAY")String horizon){return analyses.asset(symbol,horizon);}
    @PostMapping("/analyses/{id}/review") public void review(@PathVariable long id,@RequestBody InformationAnalysisService.Review request){analyses.review(id,request);}
}

