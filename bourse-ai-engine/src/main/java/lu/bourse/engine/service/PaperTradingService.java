package lu.bourse.engine.service;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.*;
import java.time.*;
import java.util.*;

/** Simulated USD cash equity fills. No network calls to brokerage APIs. */
@Service
public class PaperTradingService {
 private static final BigDecimal FEE_RATE=new BigDecimal("0.001");
 private final JdbcTemplate jdbc;
 private final DataQualityService quality;
 public PaperTradingService(JdbcTemplate jdbc,DataQualityService quality){this.jdbc=jdbc;this.quality=quality;}

 public record Account(long id,String name,String currency,BigDecimal initialCash,BigDecimal cashBalance){}
 public record Fill(long id,long accountId,String symbol,String clientOrderId,String side,
   BigDecimal quantity,BigDecimal simulatedPrice,BigDecimal simulatedFee,Instant quoteTime,Instant executedTime){}
 public record Order(long accountId,String clientOrderId,String symbol,String side,BigDecimal quantity){}
 public record Position(String symbol,BigDecimal quantity,BigDecimal lastPrice,BigDecimal marketValue){}
 public record Snapshot(Account account,List<Position> positions,BigDecimal markedEquity,
  BigDecimal totalSimulatedFees,String disclaimer){}
 private record Price(long assetId,String currency,BigDecimal close,Instant quoteTime){}

 @Transactional
 public Account create(String name,BigDecimal initialCash){
  if(name==null||!name.matches("[A-Za-z0-9._-]{3,80}")||
    initialCash==null||initialCash.signum()<=0||initialCash.precision()>16||
    initialCash.compareTo(new BigDecimal("10000000"))>0)
   throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid paper account");
  var key=new GeneratedKeyHolder();
  jdbc.update(c->{
   var ps=c.prepareStatement("INSERT INTO paper_accounts(account_name,currency,initial_cash,cash_balance) VALUES (?,'USD',?,?)",
    new String[]{"id"});
   ps.setString(1,name);ps.setBigDecimal(2,initialCash);ps.setBigDecimal(3,initialCash);return ps;
  },key);
  return account(key.getKey().longValue());
 }

 @Transactional(readOnly=true)
 public Account account(long id){
  List<Account> found=jdbc.query("SELECT id,account_name,currency,initial_cash,cash_balance FROM paper_accounts WHERE id=?",
   (r,i)->new Account(r.getLong(1),r.getString(2),r.getString(3),r.getBigDecimal(4),r.getBigDecimal(5)),id);
  if(found.isEmpty())throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Paper account not found");
  return found.get(0);
 }

 private Price quote(String ticker){
  List<Price> prices=jdbc.query("SELECT a.id,a.currency,c.close_price,c.open_time FROM assets a JOIN market_candles c ON c.asset_id=a.id WHERE a.symbol=? AND c.timeframe='1d' AND c.open_time<=CURRENT_TIMESTAMP ORDER BY c.open_time DESC LIMIT 1",
   (r,i)->new Price(r.getLong(1),r.getString(2),r.getBigDecimal(3),r.getTimestamp(4).toInstant()),ticker);
  if(prices.isEmpty())throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,"No market close for simulation");
  Price p=prices.get(0);
  if(!"USD".equals(p.currency()))throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,"Only USD securities supported");
  if(p.close().signum()<=0||p.quoteTime().isBefore(Instant.now().minus(Duration.ofDays(7))))
   throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,"Price invalid or stale");
  return p;
 }

 @Transactional
 public Fill execute(Order request){
  if(request==null||request.accountId()<=0||request.clientOrderId()==null||
   !request.clientOrderId().matches("[A-Za-z0-9._-]{1,80}")||
   request.symbol()==null||!request.symbol().matches("[A-Za-z0-9._-]{1,40}")||
   !Set.of("BUY","SELL").contains(request.side())||request.quantity()==null||
   request.quantity().signum()<=0||request.quantity().precision()>16||
   request.quantity().compareTo(new BigDecimal("1000000"))>0)
   throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid simulated order");
  String symbol=request.symbol().toUpperCase(Locale.ROOT);
  // Serialize all orders for one simulation account, including repeated IDs.
  List<BigDecimal> balances=jdbc.query("SELECT cash_balance FROM paper_accounts WHERE id=? FOR UPDATE",
   (r,i)->r.getBigDecimal(1),request.accountId());
  if(balances.isEmpty())throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Paper account missing");
  List<Long> old=jdbc.query("SELECT id FROM paper_trades WHERE account_id=? AND client_order_id=?",
   (r,i)->r.getLong(1),request.accountId(),request.clientOrderId());
  if(!old.isEmpty())return getFill(old.get(0)); // retries cannot double-spend

  if("BLOCKED".equals(quality.check(symbol,"1d").status()))
   throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,"Price history failed quality checks");
  Price price=quote(symbol);
  BigDecimal notional=request.quantity().multiply(price.close());
  BigDecimal fee=notional.multiply(FEE_RATE).setScale(8,RoundingMode.HALF_UP);
  BigDecimal cashChange=request.side().equals("BUY")?notional.add(fee).negate():notional.subtract(fee);
  if(balances.get(0).add(cashChange).signum()<0)
   throw new ResponseStatusException(HttpStatus.CONFLICT,"Insufficient simulated USD cash");
  if(request.side().equals("SELL")){
   BigDecimal held=positionQuantity(request.accountId(),price.assetId());
   if(held.compareTo(request.quantity())<0)
    throw new ResponseStatusException(HttpStatus.CONFLICT,"Cannot sell more than simulated holdings");
  }
  jdbc.update("UPDATE paper_accounts SET cash_balance=cash_balance+? WHERE id=?",cashChange,request.accountId());
  GeneratedKeyHolder key=new GeneratedKeyHolder();
  jdbc.update(c->{
   PreparedStatement ps=c.prepareStatement(
    "INSERT INTO paper_trades(account_id,asset_id,client_order_id,side,quantity,simulated_price,simulated_fee,quote_time) VALUES (?,?,?,?,?,?,?,?)",
    new String[]{"id"});
   ps.setLong(1,request.accountId());ps.setLong(2,price.assetId());
   ps.setString(3,request.clientOrderId());ps.setString(4,request.side());
   ps.setBigDecimal(5,request.quantity());ps.setBigDecimal(6,price.close());
   ps.setBigDecimal(7,fee);ps.setTimestamp(8,Timestamp.from(price.quoteTime()));return ps;
  },key);
  return getFill(key.getKey().longValue());
 }
 private BigDecimal positionQuantity(long account,long asset){
  BigDecimal value=jdbc.queryForObject(
   "SELECT COALESCE(SUM(CASE WHEN side='BUY' THEN quantity ELSE -quantity END),0) FROM paper_trades WHERE account_id=? AND asset_id=?",
   BigDecimal.class,account,asset);
  return value==null?BigDecimal.ZERO:value;
 }
 @Transactional(readOnly=true)
 public Fill getFill(long id){
  List<Fill> fills=jdbc.query("SELECT t.id,t.account_id,a.symbol,t.client_order_id,t.side,t.quantity,t.simulated_price,t.simulated_fee,t.quote_time,t.executed_time FROM paper_trades t JOIN assets a ON a.id=t.asset_id WHERE t.id=?",
   (r,i)->new Fill(r.getLong(1),r.getLong(2),r.getString(3),r.getString(4),r.getString(5),r.getBigDecimal(6),r.getBigDecimal(7),r.getBigDecimal(8),r.getTimestamp(9).toInstant(),r.getTimestamp(10).toInstant()),id);
  if(fills.isEmpty())throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Paper trade not found");
  return fills.get(0);
 }
 @Transactional(readOnly=true)
 public List<Fill> history(long account,int limit){
  account(account);
  if(limit<1||limit>200)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"limit must be 1..200");
  return jdbc.query("SELECT id FROM paper_trades WHERE account_id=? ORDER BY id DESC LIMIT ?",
   (r,i)->getFill(r.getLong(1)),account,limit);
 }
 @Transactional(readOnly=true)
 public Snapshot snapshot(long id){
  Account a=account(id);
  List<Position> holdings=jdbc.query(
   "SELECT x.symbol,x.shares,x.last_price FROM (SELECT a.symbol, a.id AS asset_id,SUM(CASE WHEN t.side='BUY' THEN t.quantity ELSE -t.quantity END) AS shares, (SELECT c.close_price FROM market_candles c WHERE c.asset_id=a.id AND c.timeframe='1d' AND c.open_time<=CURRENT_TIMESTAMP ORDER BY c.open_time DESC LIMIT 1) AS last_price FROM paper_trades t JOIN assets a ON a.id=t.asset_id WHERE t.account_id=? GROUP BY a.symbol,a.id) x WHERE x.shares>0 ORDER BY x.symbol",
   (r,i)->{
    BigDecimal shares=r.getBigDecimal(2),price=r.getBigDecimal(3);
    return new Position(r.getString(1),shares,price,
     price==null?null:shares.multiply(price));
   },id);
  BigDecimal equity=a.cashBalance();
  for(Position p:holdings){
   if(p.marketValue()==null)throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,"Missing mark price");
   equity=equity.add(p.marketValue());
  }
  BigDecimal fees=jdbc.queryForObject("SELECT COALESCE(SUM(simulated_fee),0) FROM paper_trades WHERE account_id=?",
   BigDecimal.class,id);
  return new Snapshot(a,List.copyOf(holdings),equity,fees,
   "Paper trades use latest imported daily close as a simulated fill, not an executable quote. No broker order, spread or tax.");
 }
}
