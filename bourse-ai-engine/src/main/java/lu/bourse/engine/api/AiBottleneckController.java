package lu.bourse.engine.api;
import lu.bourse.engine.service.AiBottleneckService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/ai-bottlenecks")
public class AiBottleneckController {
 private final AiBottleneckService service;
 public AiBottleneckController(AiBottleneckService service){this.service=service;}
 // Analyst-only endpoint: protect before production.
 @PostMapping @ResponseStatus(HttpStatus.CREATED)
 public AiBottleneckService.Evidence add(@RequestBody AiBottleneckService.Submission request){return service.add(request);}
 @GetMapping("/{symbol}")
 public AiBottleneckService.Report assess(@PathVariable String symbol){return service.assess(symbol);}
}
