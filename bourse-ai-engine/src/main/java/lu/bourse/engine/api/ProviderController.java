package lu.bourse.engine.api;
import lu.bourse.engine.provider.*;
import lu.bourse.engine.service.HistoryService;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.time.LocalDate;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
@RestController
@RequestMapping("/api/v1")
public class ProviderController {
 private final TwelveDataImportService prices;private final GdeltNewsService news;private final SecEdgarService sec;private final FmpProfileService fmp;private final NewsHeuristicAnalysisService newsAnalysis;private final EodhdEuropeService europe;
 public ProviderController(TwelveDataImportService prices,GdeltNewsService news,SecEdgarService sec,FmpProfileService fmp,NewsHeuristicAnalysisService newsAnalysis,EodhdEuropeService europe){this.prices=prices;this.news=news;this.sec=sec;this.fmp=fmp;this.newsAnalysis=newsAnalysis;this.europe=europe;}
 @PostMapping("/providers/twelve-data/sync/{symbol}")
 public HistoryService.ImportResult prices(@PathVariable String symbol,@RequestParam(defaultValue="5") int years){return prices.sync(symbol,years);}
 public record RepairRequest(String symbol, LocalDate date, String timeframe){}
 @PostMapping("/market-data/repair")
 public TwelveDataImportService.RepairResult repair(@RequestBody RepairRequest request) {
  if (request == null || !"1d".equals(request.timeframe())) {
   throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Repair requires timeframe 1d");
  }
  return prices.repairDaily(request.symbol(), request.date());
 }
 @PostMapping("/providers/gdelt/sync/{symbol}")
 public GdeltNewsService.NewsSyncResult news(@PathVariable String symbol,@RequestParam(defaultValue="25") int max){return news.sync(symbol,max);}
 @GetMapping("/news/{symbol}") public List<GdeltNewsService.NewsRow> newsList(@PathVariable String symbol){return news.list(symbol);}
 @PostMapping("/providers/sec/sync/{symbol}")
 public SecEdgarService.SecResult financials(@PathVariable String symbol,@RequestParam int cik){return sec.sync(symbol,cik);}
 @GetMapping("/fundamentals/{symbol}") public List<SecEdgarService.Fact> facts(@PathVariable String symbol){return sec.latest(symbol);}
 @PostMapping("/providers/fmp/sync/{symbol}") public FmpProfileService.Profile profile(@PathVariable String symbol){return fmp.sync(symbol);}
 @GetMapping("/company/{symbol}") public FmpProfileService.Profile getProfile(@PathVariable String symbol){return fmp.get(symbol);}
 @PostMapping("/news/analyze/{symbol}") public NewsHeuristicAnalysisService.Result analyzeNews(@PathVariable String symbol){return newsAnalysis.analyze(symbol);}
 @PostMapping("/providers/eodhd/sync/{symbol}") public EodhdEuropeService.SyncResult syncEurope(@PathVariable String symbol,@RequestParam String providerSymbol,@RequestParam(defaultValue="EUR") String currency,@RequestParam(required=false) String name,@RequestParam(defaultValue="1") int years,@RequestParam(defaultValue="STOCK") String assetType){return europe.sync(symbol,providerSymbol,currency,name,years,assetType);}
 @GetMapping("/providers/eodhd/observations/{symbol}") public List<EodhdEuropeService.ProviderPrice> europePrices(@PathVariable String symbol){return europe.observations(symbol);}
}

