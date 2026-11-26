package lu.bourse.engine.api;
import lu.bourse.engine.provider.GdeltNewsService;
import lu.bourse.engine.provider.SecEdgarService;
import lu.bourse.engine.service.HistoryService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.util.*;
@RestController
@RequestMapping("/api/v1/investment")
public class InvestmentAnalysisController {
 private final HistoryService history;private final GdeltNewsService news;private final SecEdgarService sec;
 public InvestmentAnalysisController(HistoryService history,GdeltNewsService news,SecEdgarService sec){this.history=history;this.news=news;this.sec=sec;}
 public record Explanation(String symbol,String verdict,String method,String explanation,Object technical,List<GdeltNewsService.NewsRow> news,List<SecEdgarService.Fact> fundamentals,List<String> cautions){}
 @GetMapping("/explain/{symbol}")
 public Explanation explain(@PathVariable String symbol){
  var articles=news.list(symbol);var facts=sec.latest(symbol);
  HistoryService.Analysis technical=null;
  try{technical=history.analyze(symbol,"1d");}catch(ResponseStatusException e){if(e.getStatusCode()!=HttpStatus.UNPROCESSABLE_ENTITY)throw e;}
  List<String> notes=new ArrayList<>();
  if(technical==null)notes.add("At least 35 imported daily candles required for technical analysis");
  if(facts.isEmpty())notes.add("No verified SEC financial statements imported");
  if(articles.isEmpty())notes.add("No news imported");
  notes.add("This legacy endpoint uses headline data only; structured AI evidence is available through /api/v1/information/assets and /api/v1/explanations");
  notes.add("A score or an indicator cannot establish a probability of profit");
  String text=technical==null?"Insufficient prices for SMA20/RSI14/MACD":
   "SMA20 is the average of 20 closing prices; RSI14 compares smoothed up/down moves; MACD is EMA12 minus EMA26 and its signal is EMA9 of MACD. Current indicators are available in technical.";
  return new Explanation(symbol.toUpperCase(Locale.ROOT),"REVIEW_ONLY","DETERMINISTIC_FORMULAS_NO_LLM",text,technical,articles.stream().limit(10).toList(),facts.stream().limit(20).toList(),notes);
 }
}

