package lu.bourse.engine.api;

import lu.bourse.engine.portfolio.*;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController
@RequestMapping("/api/v1/portfolios")
public class PortfolioController {
    private final PortfolioService service;
    public PortfolioController(PortfolioService service){this.service=service;}
    @PostMapping public Map<String,Long> create(@RequestBody PortfolioService.CreatePortfolio r){return Map.of("id",service.create(r));}
    @PostMapping("/{id}/positions") public Map<String,Long> buy(@PathVariable long id,@RequestBody PortfolioService.Buy r){return Map.of("positionId",service.buy(id,r));}
    @GetMapping("/{id}/positions") public List<PortfolioService.Position> positions(@PathVariable long id){return service.positions(id);}
    @PostMapping("/{id}/positions/{positionId}/trades") public Map<String,Long> trade(@PathVariable long id,@PathVariable long positionId,@RequestBody PortfolioService.Trade r){return Map.of("tradeId",service.recordTrade(id,positionId,r));}
    @PutMapping("/{id}/positions/{positionId}/mark") public void mark(@PathVariable long id,@PathVariable long positionId,@RequestBody PortfolioService.Mark r){service.mark(id,positionId,r);}
    @PostMapping("/{id}/evaluate") public PortfolioService.Risk evaluate(@PathVariable long id){return service.evaluate(id);}
    @GetMapping("/{id}/positions/{positionId}/plan") public Map<String,Object> plan(@PathVariable long id,@PathVariable long positionId){return service.plan(id,positionId);}
    @GetMapping("/{id}/alerts") public List<Map<String,Object>> alerts(@PathVariable long id){return service.alerts(id);}
    @PostMapping("/{id}/alerts/{alertId}/acknowledge") public void acknowledge(@PathVariable long id,@PathVariable long alertId){service.acknowledge(id,alertId);}
}
