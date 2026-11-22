package lu.bourse.engine.service;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

@Service
public class DataQualityService {
 private final JdbcTemplate jdbc;
 public DataQualityService(JdbcTemplate jdbc) { this.jdbc=jdbc; }
 public record Issue(String code,String severity,Instant time,String detail) {}
 public record Report(String symbol,String timeframe,int candleCount,int independentProviderMatches,
                      String status,Instant latestCandle,List<Issue> issues) {}
 private record Bar(Instant time,BigDecimal open,BigDecimal high,BigDecimal low,BigDecimal close,BigDecimal volume,String provider) {}
 private record Observation(Instant time,BigDecimal close,String provider,String currency,String adjustment) {}
 public Report check(String rawSymbol,String timeframe) {
  if(rawSymbol==null || !rawSymbol.matches("[A-Za-z0-9._-]{1,40}") || !Set.of("1d","1h","1m").contains(timeframe))
   throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid symbol/timeframe");
  String symbol=rawSymbol.toUpperCase(Locale.ROOT);
  List<String> currencies=jdbc.query("SELECT currency FROM assets WHERE symbol=?", (r,i)->r.getString(1),symbol);
  if(currencies.isEmpty())throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Unknown asset");
  String currency=currencies.get(0);
  List<Bar> bars=jdbc.query("SELECT c.open_time,c.open_price,c.high_price,c.low_price,c.close_price,c.volume,c.provider FROM market_candles c JOIN assets a ON a.id=c.asset_id WHERE a.symbol=? AND c.timeframe=? ORDER BY c.open_time ASC",
   (r,i)->new Bar(r.getTimestamp(1).toInstant(),r.getBigDecimal(2),r.getBigDecimal(3),r.getBigDecimal(4),r.getBigDecimal(5),r.getBigDecimal(6),r.getString(7)),symbol,timeframe);
  List<Observation> observations=jdbc.query("SELECT p.open_time,p.close_price,p.provider,p.currency,p.price_adjustment FROM provider_price_observations p JOIN assets a ON a.id=p.asset_id WHERE a.symbol=? AND p.timeframe=?",
   (r,i)->new Observation(r.getTimestamp(1).toInstant(),r.getBigDecimal(2),r.getString(3),r.getString(4),r.getString(5)),symbol,timeframe);
  Map<Instant,List<Observation>> byTime=new HashMap<>();
  for(Observation o:observations)byTime.computeIfAbsent(o.time(),k->new ArrayList<>()).add(o);
  List<Issue> issues=new ArrayList<>();int matches=0;
  if(bars.isEmpty())issues.add(new Issue("NO_CANDLES","BLOCK",null,"No canonical prices"));
  if(bars.size()<35)issues.add(new Issue("SHORT_HISTORY","WARN",null,"Fewer than 35 observations"));
  Instant previous=null; BigDecimal previousClose=null;
  for(Bar b:bars) {
   if(b.open().signum()<=0||b.low().signum()<=0||b.high().compareTo(b.low())<0||
      b.high().compareTo(b.open())<0||b.high().compareTo(b.close())<0||
      b.low().compareTo(b.open())>0||b.low().compareTo(b.close())>0||b.volume().signum()<0)
     issues.add(new Issue("INVALID_OHLCV","BLOCK",b.time(),"Impossible price or negative volume"));
   if(previous!=null && !b.time().isAfter(previous))issues.add(new Issue("NON_MONOTONIC_TIME","BLOCK",b.time(),"Duplicate or disordered candles"));
   if(previousClose!=null && previousClose.signum()>0) {
    BigDecimal move=b.close().subtract(previousClose).abs().divide(previousClose,6,RoundingMode.HALF_UP);
    if(move.compareTo(new BigDecimal("0.40"))>0)issues.add(new Issue("EXTREME_DAILY_MOVE","WARN",b.time(),"Unadjusted move > 40%; verify splits/corporate actions"));
   }
   for(Observation o:byTime.getOrDefault(b.time(),List.of())) {
    if(!currency.equals(o.currency())){issues.add(new Issue("CURRENCY_MISMATCH","BLOCK",b.time(),o.provider()+" currency differs"));continue;}
    if(!"UNSPECIFIED".equalsIgnoreCase(o.adjustment())){issues.add(new Issue("ADJUSTMENT_UNKNOWN_COMPARABILITY","WARN",b.time(),o.provider()+" adjustment needs harmonization"));continue;}
    if(o.provider().equalsIgnoreCase(b.provider()))continue; // independent corroboration only
    matches++;
    if(b.close().signum()>0 && o.close().subtract(b.close()).abs().divide(b.close(),6,RoundingMode.HALF_UP).compareTo(new BigDecimal("0.02"))>0)
     issues.add(new Issue("CROSS_PROVIDER_DIVERGENCE","WARN",b.time(),o.provider()+" close differs > 2%"));
   }
   previous=b.time();previousClose=b.close();
  }
  Instant latest=bars.isEmpty()?null:bars.get(bars.size()-1).time();
  if(latest!=null && Duration.between(latest,Instant.now()).toHours()>(timeframe.equals("1d")?120:timeframe.equals("1h")?8:2))
   issues.add(new Issue("STALE_DATA","WARN",latest,"Latest price may be stale; market holidays not accounted for"));
  if(matches==0)issues.add(new Issue("NO_INDEPENDENT_CONFIRMATION","WARN",latest,"No matching observation from a distinct provider"));
  String status=issues.stream().anyMatch(i->i.severity().equals("BLOCK"))?"BLOCKED":issues.isEmpty()?"PASS":"REVIEW";
  return new Report(symbol,timeframe,bars.size(),matches,status,latest,List.copyOf(issues));
 }
}
