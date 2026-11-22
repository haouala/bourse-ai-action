package lu.bourse.engine.service;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;

/**
 * Stateless what-if holdings; no brokerage state and no trade execution.
 * Reject mixed currencies rather than silently compare unconverted cash values.
 */
@Service
public class PortfolioRiskService {
 private final JdbcTemplate jdbc;
 private final DataQualityService quality;
 public PortfolioRiskService(JdbcTemplate jdbc,DataQualityService quality){this.jdbc=jdbc;this.quality=quality;}
 public record Holding(String symbol,BigDecimal quantity){}
 public record Request(List<Holding> holdings){}
 public record Position(String symbol,BigDecimal quantity,String currency,BigDecimal latestClose,
  BigDecimal marketValue,double weightPct){}
 public record Report(String currency,BigDecimal totalValue,List<Position> positions,
  PortfolioRiskMath.Measures historicalRisk,String status,List<String> warnings,String disclaimer){}
 private record Quote(LocalDate date,double price){}
 @Transactional(readOnly=true)
 public Report analyze(Request request){
  if(request==null||request.holdings()==null||request.holdings().isEmpty()||request.holdings().size()>10)
   throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"1..10 positions required");
  Set<String> unique=new HashSet<>();
  List<List<PortfolioRiskMath.Point>> prices=new ArrayList<>();
  List<String> symbols=new ArrayList<>(),currencies=new ArrayList<>();
  List<BigDecimal> quantities=new ArrayList<>(),latestPrices=new ArrayList<>();
  List<String> warnings=new ArrayList<>();
  double[] q=new double[request.holdings().size()];
  for(int i=0;i<request.holdings().size();i++){
   Holding holding=request.holdings().get(i);
   if(holding==null||holding.symbol()==null||!holding.symbol().matches("[A-Za-z0-9._-]{1,40}")||
      holding.quantity()==null||holding.quantity().signum()<=0||
      holding.quantity().precision()>16||holding.quantity().doubleValue()>1e9)
    throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid holdings");
   String symbol=holding.symbol().toUpperCase(Locale.ROOT);
   if(!unique.add(symbol))throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Duplicate asset");
   var qc=quality.check(symbol,"1d");
   if("BLOCKED".equals(qc.status()))
    throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,"Invalid history for "+symbol);
   if(!qc.issues().isEmpty())warnings.add(symbol+": "+qc.issues().size()+" price quality warnings");
   List<String> unit=jdbc.query("SELECT currency FROM assets WHERE symbol=?",
    (r,j)->r.getString(1),symbol);
   if(unit.isEmpty())throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Unknown asset");
   if(!currencies.isEmpty()&&!currencies.get(0).equals(unit.get(0)))
    throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,"Mixed currencies require FX conversion");
   currencies.add(unit.get(0));
   List<Quote> rows=jdbc.query("SELECT c.open_time,c.close_price FROM market_candles c JOIN assets a ON a.id=c.asset_id WHERE a.symbol=? AND c.timeframe='1d' AND c.open_time<=CURRENT_TIMESTAMP ORDER BY c.open_time DESC LIMIT 100",
    (r,j)->new Quote(r.getTimestamp(1).toInstant().atZone(ZoneOffset.UTC).toLocalDate(),
      r.getBigDecimal(2).doubleValue()),symbol);
   Collections.reverse(rows);
   if(rows.size()<61)throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,"Not enough daily prices");
   LocalDate latest=rows.get(rows.size()-1).date();
   if(latest.isBefore(LocalDate.now(ZoneOffset.UTC).minusDays(7)))
    throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,"Quote is stale");
   List<PortfolioRiskMath.Point> series=new ArrayList<>();
   for(Quote p:rows)series.add(new PortfolioRiskMath.Point(p.date(),p.price()));
   prices.add(series);
   symbols.add(symbol);quantities.add(holding.quantity());
   q[i]=holding.quantity().doubleValue();latestPrices.add(BigDecimal.valueOf(rows.get(rows.size()-1).price()));
  }
  PortfolioRiskMath.Measures measures;
  try{measures=PortfolioRiskMath.measure(prices,q);}
  catch(IllegalArgumentException ex){throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,"Insufficient aligned prices or invalid holdings");}
  BigDecimal value=BigDecimal.ZERO;
  List<BigDecimal> marketValues=new ArrayList<>();
  for(int i=0;i<symbols.size();i++){
   BigDecimal v=quantities.get(i).multiply(latestPrices.get(i));marketValues.add(v);value=value.add(v);
  }
  if(value.signum()<=0||value.precision()>24)
   throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,"Portfolio valuation overflow");
  List<Position> positions=new ArrayList<>();
  for(int i=0;i<symbols.size();i++){
   positions.add(new Position(symbols.get(i),quantities.get(i),currencies.get(i),
    latestPrices.get(i),marketValues.get(i),
    marketValues.get(i).divide(value,8,java.math.RoundingMode.HALF_UP).doubleValue()*100));
  }
  warnings.add("Historical 95th-percentile single-session loss is a retrospective empirical estimate, not a VaR guarantee");
  warnings.add("Shares held constant; no cash, debt, leverage, FX hedging, transaction costs or tax modeled");
  warnings.add("Portfolio historical dates are aligned by UTC date, not individual exchange closing sessions");
  return new Report(currencies.get(0),value,List.copyOf(positions),measures,
   "RESEARCH_ONLY",List.copyOf(warnings),
   "Risk scenario for hypothetical holdings only; no orders are placed. Historical losses do not bound future losses.");
 }
}
