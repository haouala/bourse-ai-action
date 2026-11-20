package lu.bourse.engine.api;
import lu.bourse.engine.information.InformationService;
import org.springframework.web.bind.annotation.*;
import java.util.*;
@RestController
@RequestMapping("/api/v1/information")
public class InformationController {
    private final InformationService service;
    public InformationController(InformationService service){this.service=service;}
    public record Enable(boolean enabled){}
    @GetMapping("/sources") public List<Map<String,Object>> sources(){return service.sources();}
    @PutMapping("/sources/{code}/enabled") public void enable(@PathVariable String code,@RequestBody Enable request){service.enable(code,request.enabled());}
    @PostMapping("/sources/{code}/sync") public Map<String,Object> sync(@PathVariable String code){return service.sync(code);}
    @GetMapping("/items") public List<Map<String,Object>> items(@RequestParam(required=false) String source){return service.items(source);}
    @GetMapping("/items/{id}/mentions") public List<Map<String,Object>> mentions(@PathVariable long id){return service.mentions(id);}
    @GetMapping("/runs") public List<Map<String,Object>> runs(){return service.runs();}
    @PostMapping("/items/{id}/attributions") public Map<String,Long> attribute(@PathVariable long id,@RequestBody InformationService.Attribution r){return service.attribute(id,r);}
}
