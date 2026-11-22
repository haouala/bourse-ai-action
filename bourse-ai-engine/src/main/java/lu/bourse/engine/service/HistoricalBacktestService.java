package lu.bourse.engine.service;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.*;

@Service
public class HistoricalBacktestService {
 private final JdbcTemplate jdbc;
 private final DataQualityService quality;
 public HistoricalBacktestService(JdbcTemplate jdbc,DataQualityService quality){this.jdbc=jdbc;this.quality=quality;}
 public record Point(Instant date,double equity,double buyAndHold,boolean invested){}
 public record Result(String symbol,String strategy,int observations,int trades,double feeBps,
                      double initialCapital,double finalEquity,double returnPct,double benchmarkReturnPct,
                      double maxDrawdownPct,List<Point> curve,List<String> cautions) {}
 private record Close(Instant date,double price){}
 public Result run(String rawSymbol,int shortWindow,int longWindow,int feeBps){
  if(rawSymbol==null||!rawSymbol.matches("[A-Za-z0-9._-]{1,40}")||shortWindow<2||longWindow<=shortWindow||
      longWindow>200||feeBps<0||feeBps>500)
   throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid backtest parameters");
  String symbol=rawSymbol.toUpperCase(Locale.ROOT);
  DataQualityService.Report qc=quality.check(symbol,"1d");
  if("BLOCKED".equals(qc.status()))throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,"Unreliable price history");
  List<Close> candles=jdbc.query("SELECT c.open_time,c.close_price FROM market_candles c JOIN assets a ON a.id=c.asset_id WHERE a.symbol=? AND c.timeframe='1d' ORDER BY c.open_time ASC",
   (r,i)->new Close(r.getTimestamp(1).toInstant(),r.getBigDecimal(2).doubleValue()),symbol);
  if(candles.size()<longWindow+2)throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,"Insufficient daily candles for strategy");
  // Signals at closing time i use only prices <= i. Execution is at the NEXT close (i+1),
  // a deliberately conservative daily approximation; intraday fills are not simulated.
  double cash=10000,shares=0,peak=cash,maxDd=0,initial=10000,benchmarkShares=initial/candles.get(0).price();
  int trades=0;List<Point> curve=new ArrayList<>();
  for(int i=0;i<candles.size();i++){
   Close bar=candles.get(i);
   if(bar.price()<=0||!Double.isFinite(bar.price()))throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,"Invalid close");
   if(i>=longWindow){
    double fast=average(candles,i-1,shortWindow),slow=average(candles,i-1,longWindow);
    boolean desired=fast>slow;
    if(desired && shares==0){shares=cash*(1-feeBps/10000.0)/bar.price();cash=0;trades++;}
    else if(!desired && shares>0){cash=shares*bar.price()*(1-feeBps/10000.0);shares=0;trades++;}
   }
   double equity=cash+shares*bar.price();
   peak=Math.max(peak,equity);maxDd=Math.max(maxDd,(peak-equity)/peak*100);
   curve.add(new Point(bar.date(),round(equity),round(benchmarkShares*bar.price()),shares>0));
  }
  double finalEquity=cash+shares*candles.get(candles.size()-1).price();
  double benchmark=benchmarkShares*candles.get(candles.size()-1).price();
  List<String> cautions=new ArrayList<>();
  cautions.add("No future observations enter signal calculation; signal generated at preceding close.");
  cautions.add("Next-close execution is an approximation, not a realistic guaranteed fill.");
  cautions.add("Dividends, taxes, exchange rates, borrow fees and spread are not modeled.");
  cautions.add("Survivorship bias, delistings, corporate actions and data adjustments require independent validation.");
  if(!qc.issues().isEmpty())cautions.add("Price quality report has "+qc.issues().size()+" warning(s); review before interpreting results.");
  return new Result(symbol,"SMA_CROSS_NEXT_CLOSE",candles.size(),trades,feeBps,initial,round(finalEquity),
   round((finalEquity/initial-1)*100),round((benchmark/initial-1)*100),round(maxDd),List.copyOf(curve),List.copyOf(cautions));
 }
 private static double average(List<Close> prices,int end,int window) {
  double sum=0;for(int i=end-window+1;i<=end;i++)sum+=prices.get(i).price();
  return sum/window;
 }
 private static double round(double v){return BigDecimal.valueOf(v).setScale(4,RoundingMode.HALF_UP).doubleValue();}
}
