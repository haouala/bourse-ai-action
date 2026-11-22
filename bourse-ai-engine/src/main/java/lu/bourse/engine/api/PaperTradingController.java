package lu.bourse.engine.api;

import lu.bourse.engine.service.PaperTradingService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import java.math.BigDecimal;
import java.util.List;

@RestController
@RequestMapping("/api/v1/paper")
public class PaperTradingController {
 private final PaperTradingService service;
 public PaperTradingController(PaperTradingService service){this.service=service;}
 public record CreateAccount(String name,BigDecimal initialCash){}
 @PostMapping("/accounts") @ResponseStatus(HttpStatus.CREATED)
 public PaperTradingService.Account create(@RequestBody CreateAccount request){
  return service.create(request.name(),request.initialCash());
 }
 @GetMapping("/accounts/{id}")
 public PaperTradingService.Account get(@PathVariable long id){return service.account(id);}
 @PostMapping("/orders") @ResponseStatus(HttpStatus.CREATED)
 public PaperTradingService.Fill order(@RequestBody PaperTradingService.Order request){
  return service.execute(request);
 }
 @GetMapping("/accounts/{id}/trades")
 public List<PaperTradingService.Fill> trades(@PathVariable long id,
  @RequestParam(defaultValue="100") int limit){return service.history(id,limit);}
 @GetMapping("/accounts/{id}/snapshot")
 public PaperTradingService.Snapshot snapshot(@PathVariable long id){return service.snapshot(id);}
}
