package lu.bourse.engine.api;
import lu.bourse.engine.market.*;
import lu.bourse.engine.portfolio.*;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController
@RequestMapping("/api/v1")
public class MarketMonitoringController {
    private final WatchlistService watchlist;private final MarketTaskService tasks;private final PortfolioMonitor monitor;
    public MarketMonitoringController(WatchlistService watchlist,MarketTaskService tasks,PortfolioMonitor monitor){this.watchlist=watchlist;this.tasks=tasks;this.monitor=monitor;}
    @PutMapping("/watchlist") public Map<String,Long> register(@RequestBody WatchlistService.Entry r){return Map.of("assetId",watchlist.register(r));}
    @PostMapping("/watchlist/register-imported-us") public Map<String,Integer> registerImportedUs(){return Map.of("registered",watchlist.registerImportedUs());}
    @GetMapping("/watchlist") public List<Map<String,Object>> watchlist(){return watchlist.list();}
    @PostMapping("/watchlist/enqueue") public Map<String,Integer> enqueue(){return Map.of("enqueued",watchlist.enqueue());}
    @GetMapping("/market/tasks") public List<Map<String,Object>> tasks(){return tasks.list();}
    @PostMapping("/market/tasks/run-next") public Map<String,Object> run(){return tasks.runNext();}
    @PostMapping("/portfolios/{id}/monitor") public PortfolioService.Risk monitor(@PathVariable long id){return monitor.evaluate(id);}
}
