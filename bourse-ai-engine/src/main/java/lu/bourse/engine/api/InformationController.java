package lu.bourse.engine.api;
import lu.bourse.engine.information.*;
import org.springframework.web.bind.annotation.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
@RestController
@RequestMapping("/api/v1/information")
public class InformationController {
    private final String reviewToken;private final InformationService service;private final InformationWorkService work;private final InformationAnalysisService analyses;
    public InformationController(InformationService service,InformationWorkService work,InformationAnalysisService analyses,@Value("${information.ai.review-token:}")String reviewToken){this.reviewToken=reviewToken;this.service=service;this.work=work;this.analyses=analyses;}
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
    @PostMapping("/analyses/{id}/review") public void review(@PathVariable long id,@RequestHeader(value="X-Review-Token",required=false)String token,@RequestBody InformationAnalysisService.Review request){
        if(reviewToken.length()<32)throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Review credential not configured");
        if(token==null||!MessageDigest.isEqual(reviewToken.getBytes(StandardCharsets.UTF_8),token.getBytes(StandardCharsets.UTF_8)))throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"Review credential required");
        analyses.review(id,request);
    }
}
