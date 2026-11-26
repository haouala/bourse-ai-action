package lu.bourse.engine.api;

import lu.bourse.engine.service.PredictionTrackingService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequestMapping("/api/v1/predictions")
public class PredictionTrackingController {
 private final PredictionTrackingService service;
 public PredictionTrackingController(PredictionTrackingService service){this.service=service;}

 // Ingestion is for trusted backend/model integrations only: protect with auth before deployment.
 @PostMapping @ResponseStatus(HttpStatus.CREATED)
 public PredictionTrackingService.Prediction submit(@RequestBody PredictionTrackingService.Submission request){
  return service.submit(request);
 }
 @GetMapping("/id/{id}")
 public PredictionTrackingService.Prediction byId(@PathVariable long id){return service.get(id);}
 @GetMapping("/{symbol}")
 public List<PredictionTrackingService.Prediction> history(@PathVariable String symbol,
  @RequestParam(defaultValue="100") int limit){return service.list(symbol,limit);}
 @PostMapping("/{symbol}/evaluate")
 public PredictionTrackingService.Evaluation evaluate(@PathVariable String symbol){return service.evaluate(symbol);}
 @GetMapping("/{symbol}/metrics")
 public List<PredictionTrackingService.Metrics> metrics(@PathVariable String symbol){return service.metrics(symbol);}
}
