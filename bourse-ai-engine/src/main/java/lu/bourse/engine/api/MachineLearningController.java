package lu.bourse.engine.api;

import lu.bourse.engine.service.MachineLearningService;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequestMapping("/api/v1/ml")
public class MachineLearningController {
 private final MachineLearningService service;
 public MachineLearningController(MachineLearningService service){this.service=service;}
 // Administrative CPU-intensive endpoint; require auth, quota and job queue before production.
 @PostMapping("/train/{symbol}")
 public MachineLearningService.Training train(@PathVariable String symbol,
  @RequestParam(defaultValue="10") int horizonSessions){
  return service.train(symbol,horizonSessions);
 }
 @GetMapping("/runs/{id}")
 public MachineLearningService.Run run(@PathVariable long id){return service.get(id);}
 @GetMapping("/history/{symbol}")
 public List<MachineLearningService.Run> history(@PathVariable String symbol,
  @RequestParam(defaultValue="20") int limit){return service.history(symbol,limit);}
}
