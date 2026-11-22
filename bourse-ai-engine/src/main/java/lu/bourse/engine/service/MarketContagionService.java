package lu.bourse.engine.service;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.*;

/** Read-only statistical exploration of sourced business graph neighbours. */
@Service
public class MarketContagionService {
 private static final int MIN_PAIRS=30;
 private static final List<Integer> LAGS=List.of(0,1,2,5);
 private final JdbcTemplate jdbc;
 private final StockRelationshipService graph;
 private final DataQualityService quality;
 public MarketContagionService(JdbcTemplate jdbc,StockRelationshipService graph,DataQualityService quality){
  this.jdbc=jdbc;this.graph=graph;this.quality=quality;
 }
 public record PairAnalysis(String source,String target,List<String> relationshipTypes,
  int commonReturnDates,List<ContagionStatistics.Correlation> leadingCorrelations,
  String status,List<String> cautions){}
 public record Report(String root,int lookbackSessions,int minimumPairs,int linksEvaluated,
  List<PairAnalysis> comparisons,String method,String disclaimer){}
 private record Close(LocalDate date,double close){}

 @Transactional(readOnly=true)
 public Report analyze(String rawSymbol,int lookbackSessions,int limit){
  if(rawSymbol==null||!rawSymbol.matches("[A-Za-z0-9._-]{1,40}")||
     lookbackSessions<60||lookbackSessions>1000||limit<1||limit>30)
   throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid symbol, lookback or limit");
  String root=rawSymbol.toUpperCase(Locale.ROOT);
  DataQualityService.Report rootQuality=quality.check(root,"1d");
  if("BLOCKED".equals(rootQuality.status()))
   throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,"Root price data failed quality checks");
  List<StockRelationshipService.Edge> edges=graph.direct(root,false);
  Map<String,Set<String>> peers=new TreeMap<>();
  for(var edge:edges){
   String other=edge.fromSymbol().equals(root)?edge.toSymbol():edge.fromSymbol();
   peers.computeIfAbsent(other,key->new TreeSet<>()).add(edge.relationshipType());
  }
  List<ContagionStatistics.ReturnPoint> rootReturns=loadReturns(root,lookbackSessions);
  List<PairAnalysis> comparisons=new ArrayList<>();
  for(var entry:peers.entrySet()){
   if(comparisons.size()>=limit)break;
   String other=entry.getKey();
   DataQualityService.Report otherQuality=quality.check(other,"1d");
   List<String> cautions=new ArrayList<>();
   if(!rootQuality.issues().isEmpty())cautions.add("Root price quality: "+rootQuality.status()+"; "+rootQuality.issues().size()+" issues");
   if(!otherQuality.issues().isEmpty())cautions.add("Peer price quality: "+otherQuality.status()+"; "+otherQuality.issues().size()+" issues");
   if("BLOCKED".equals(otherQuality.status())){
    comparisons.add(new PairAnalysis(root,other,List.copyOf(entry.getValue()),0,List.of(),
     "PEER_DATA_BLOCKED",List.copyOf(cautions)));continue;
   }
   List<ContagionStatistics.ReturnPoint> targetReturns=loadReturns(other,lookbackSessions);
   Map<LocalDate,Double> sourceDates=new HashMap<>();
   for(var p:rootReturns)sourceDates.put(p.date(),p.dailyReturn());
   int overlap=0;
   for(var p:targetReturns)if(sourceDates.containsKey(p.date()))overlap++;
   List<ContagionStatistics.Correlation> calculated=new ArrayList<>();
   for(int lag:LAGS)calculated.add(ContagionStatistics.correlate(rootReturns,targetReturns,lag,MIN_PAIRS));
   String status=calculated.get(0).status().equals("OK")?"EXPLORATORY":"INSUFFICIENT_DATA";
   cautions.add("Daily log returns aligned by UTC date; leading lags use prior common observation, not fixed calendar days");
   cautions.add("No market-factor adjustment, multiple-testing correction, corporate-action verification or out-of-sample validation");
   comparisons.add(new PairAnalysis(root,other,List.copyOf(entry.getValue()),overlap,
    List.copyOf(calculated),status,List.copyOf(cautions)));
  }
  return new Report(root,lookbackSessions,MIN_PAIRS,comparisons.size(),List.copyOf(comparisons),
   "PEARSON_LOG_RETURNS_UTC_ALIGNED_LAGS_0_1_2_5",
   "Historical association only; lagged correlation is not evidence of causation, trade profitability or future price impact.");
 }
 private List<ContagionStatistics.ReturnPoint> loadReturns(String symbol,int lookback){
  // One extra price for first return, 5 extra observations for lag analysis.
  List<Close> descending=jdbc.query(
   "SELECT c.open_time,c.close_price FROM market_candles c JOIN assets a ON a.id=c.asset_id WHERE a.symbol=? AND c.timeframe='1d' AND c.open_time<=CURRENT_TIMESTAMP ORDER BY c.open_time DESC LIMIT ?",
   (r,i)->new Close(r.getTimestamp(1).toInstant().atZone(ZoneOffset.UTC).toLocalDate(),
    r.getBigDecimal(2).doubleValue()),symbol,lookback+6);
  Collections.reverse(descending);
  NavigableMap<LocalDate,Double> prices=new TreeMap<>();
  for(Close c:descending){
   if(prices.putIfAbsent(c.date(),c.close())!=null)
    throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,"Multiple daily candles on a UTC date");
  }
  try{return ContagionStatistics.dailyReturns(prices);}
  catch(IllegalArgumentException e){
   throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,"Invalid daily close price");
  }
 }
}
