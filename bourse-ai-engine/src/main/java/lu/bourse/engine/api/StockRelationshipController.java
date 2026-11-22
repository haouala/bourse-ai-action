package lu.bourse.engine.api;

import lu.bourse.engine.service.StockRelationshipService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequestMapping("/api/v1/relationships")
public class StockRelationshipController {
 private final StockRelationshipService service;
 public StockRelationshipController(StockRelationshipService service){this.service=service;}
 // Administrative ingestion. Protect with authentication and authorization before deployment.
 @PostMapping @ResponseStatus(HttpStatus.CREATED)
 public StockRelationshipService.Edge add(@RequestBody StockRelationshipService.Submission request){
  return service.add(request);
 }
 @GetMapping("/{symbol}")
 public List<StockRelationshipService.Edge> direct(@PathVariable String symbol,
  @RequestParam(defaultValue="false") boolean includeRetracted){
  return service.direct(symbol,includeRetracted);
 }
 @GetMapping("/{symbol}/graph")
 public StockRelationshipService.Graph graph(@PathVariable String symbol,
  @RequestParam(defaultValue="1") int depth){return service.graph(symbol,depth);}
 // Administrative evidence retraction, never silently delete historical assertions.
 @PostMapping("/{id}/retract")
 public StockRelationshipService.Edge retract(@PathVariable long id){return service.retract(id);}
}
