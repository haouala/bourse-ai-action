package lu.bourse.engine.api;
import lu.bourse.engine.service.ModelMonitoringService;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/model-monitoring")
public class ModelMonitoringController {
 private final ModelMonitoringService service;
 public ModelMonitoringController(ModelMonitoringService service){this.service=service;}
 @GetMapping("/{symbol}")
 public ModelMonitoringService.Report report(@PathVariable String symbol){return service.monitor(symbol);}
}
