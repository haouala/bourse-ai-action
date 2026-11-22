package lu.bourse.engine.service;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import java.time.Instant;
import java.util.*;

@Service
public class MarketRegimeService {
 private final JdbcTemplate jdbc;
 private final DataQualityService quality;
 public MarketRegimeService(JdbcTemplate jdbc,DataQualityService quality){this.jdbc=jdbc;this.quality=quality;}
 public record Report(String symbol,Instant asOf,RegimeRules.Signal signal,String status,List<String> warnings){}
 private record Candle(Instant time,double close){}
 @Transactional(readOnly=true)
 public Report assess(String rawSymbol){
  if(rawSymbol==null||!rawSymbol.matches("[A-Za-z0-9._-]{1,40}"))
   throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid asset symbol");
  String ticker=rawSymbol.toUpperCase(Locale.ROOT);
  var qc=quality.check(ticker,"1d");
  if("BLOCKED".equals(qc.status()))return new Report(ticker,qc.latestCandle(),null,"DATA_BLOCKED",List.of("Historical data quality blocked"));
  List<Candle> descending=jdbc.query(
   "SELECT c.open_time,c.close_price FROM market_candles c JOIN assets a ON a.id=c.asset_id WHERE a.symbol=? AND c.timeframe='1d' AND c.open_time<=CURRENT_TIMESTAMP ORDER BY c.open_time DESC LIMIT 100",
   (r,i)->new Candle(r.getTimestamp(1).toInstant(),r.getBigDecimal(2).doubleValue()),ticker);
  Collections.reverse(descending);
  if(descending.size()<65)return new Report(ticker,qc.latestCandle(),null,"INSUFFICIENT_HISTORY",List.of("At least 65 daily closes needed"));
  Set<java.time.LocalDate> dates=new HashSet<>();
  for(Candle c:descending)if(!dates.add(c.time().atZone(java.time.ZoneOffset.UTC).toLocalDate()))
   return new Report(ticker,qc.latestCandle(),null,"DATA_BLOCKED",List.of("Duplicate UTC daily dates"));
  RegimeRules.Signal signal;
  try{signal=RegimeRules.classify(descending.stream().map(Candle::close).toList());}
  catch(IllegalArgumentException e){return new Report(ticker,qc.latestCandle(),null,"DATA_BLOCKED",List.of("Invalid close prices"));}
  List<String> warnings=new ArrayList<>(signal.caveats());
  if(!qc.issues().isEmpty())warnings.add("Data quality warnings: "+qc.issues().size());
  return new Report(ticker,descending.get(descending.size()-1).time(),signal,"RESEARCH_ONLY",List.copyOf(warnings));
 }
}
