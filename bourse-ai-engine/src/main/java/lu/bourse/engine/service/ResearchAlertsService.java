package lu.bourse.engine.service;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import java.math.BigDecimal;
import java.sql.*;
import java.time.*;
import java.util.*;

@Service
public class ResearchAlertsService {
 private final JdbcTemplate jdbc;
 private final IntelligentScoringService scoring;
 public ResearchAlertsService(JdbcTemplate jdbc,IntelligentScoringService scoring){this.jdbc=jdbc;this.scoring=scoring;}
 public record Rule(long id,String symbol,String type,BigDecimal threshold,boolean enabled){}
 public record Event(long id,long ruleId,BigDecimal observedValue,Instant observationTime){}
 public record Check(int checked,int triggered,List<Event> events,String disclaimer){}
 private static String symbol(String s){
  if(s==null||!s.matches("[A-Za-z0-9._-]{1,40}"))
   throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid symbol");
  return s.toUpperCase(Locale.ROOT);
 }
 @Transactional
 public Rule create(String ticker,String type,BigDecimal threshold){
  ticker=symbol(ticker);
  if(!Set.of("PRICE_ABOVE","PRICE_BELOW","RISK_SCORE_ABOVE").contains(type)||
    threshold==null||threshold.signum()<=0||threshold.precision()>20||
    (type.equals("RISK_SCORE_ABOVE")&&threshold.compareTo(BigDecimal.valueOf(100))>0))
   throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid rule");
  List<Long> ids=jdbc.query("SELECT id FROM assets WHERE symbol=?",(r,i)->r.getLong(1),ticker);
  if(ids.isEmpty())throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Unknown asset");
  var key=new GeneratedKeyHolder();long asset=ids.get(0);
  jdbc.update(conn->{
   PreparedStatement ps=conn.prepareStatement("INSERT INTO research_alert_rules(asset_id,rule_type,threshold) VALUES (?,?,?)",new String[]{"id"});
   ps.setLong(1,asset);ps.setString(2,type);ps.setBigDecimal(3,threshold);return ps;
  },key);
  return get(key.getKey().longValue());
 }
 @Transactional(readOnly=true)
 public Rule get(long id){
  List<Rule> rows=jdbc.query("SELECT r.id,a.symbol,r.rule_type,r.threshold,r.enabled FROM research_alert_rules r JOIN assets a ON a.id=r.asset_id WHERE r.id=?",
   (r,i)->new Rule(r.getLong(1),r.getString(2),r.getString(3),r.getBigDecimal(4),r.getBoolean(5)),id);
  if(rows.isEmpty())throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Rule not found");
  return rows.get(0);
 }
 @Transactional(readOnly=true)
 public List<Rule> rules(String raw){
  return jdbc.query("SELECT r.id,a.symbol,r.rule_type,r.threshold,r.enabled FROM research_alert_rules r JOIN assets a ON a.id=r.asset_id WHERE a.symbol=? ORDER BY r.id LIMIT 200",
   (r,i)->new Rule(r.getLong(1),r.getString(2),r.getString(3),r.getBigDecimal(4),r.getBoolean(5)),symbol(raw));
 }
 @Transactional
 public Rule disable(long id){
  if(jdbc.update("UPDATE research_alert_rules SET enabled=FALSE WHERE id=?",id)==0)
   throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Rule not found");
  return get(id);
 }
 /**
  * Explicit evaluation: no polling or push notifications are enabled by default.
  * Repeated checks with the same observed daily candle cannot duplicate an event.
  */
 @Transactional
 public Check check(String raw){
  String ticker=symbol(raw);List<Rule> enabled=new ArrayList<>();
  for(Rule r:rules(ticker))if(r.enabled())enabled.add(r);
  List<Event> events=new ArrayList<>();
  for(Rule r:enabled){
   BigDecimal value;Instant when;
   if(r.type().equals("RISK_SCORE_ABOVE")){
    var score=scoring.score(ticker,30);
    if(score.riskScore()==null||score.referenceTime()==null)continue;
    value=BigDecimal.valueOf(score.riskScore());when=score.referenceTime();
   }else{
    List<Object[]> quotes=jdbc.query("SELECT c.close_price,c.open_time FROM market_candles c JOIN assets a ON a.id=c.asset_id WHERE a.symbol=? AND c.timeframe='1d' AND c.open_time<=CURRENT_TIMESTAMP ORDER BY c.open_time DESC LIMIT 1",
     (rs,i)->new Object[]{rs.getBigDecimal(1),rs.getTimestamp(2).toInstant()},ticker);
    if(quotes.isEmpty())continue;
    value=(BigDecimal)quotes.get(0)[0];when=(Instant)quotes.get(0)[1];
   }
   if(when.isBefore(Instant.now().minus(Duration.ofDays(7))))continue;
   boolean match=switch(r.type()){
    case "PRICE_ABOVE","RISK_SCORE_ABOVE"->value.compareTo(r.threshold())>=0;
    default->value.compareTo(r.threshold())<=0;
   };
   if(!match)continue;
   // Serialize checks of the same rule across parallel workers to prevent duplicate inserts.
   jdbc.queryForObject("SELECT id FROM research_alert_rules WHERE id=? FOR UPDATE",Long.class,r.id());
   int exists=jdbc.queryForObject("SELECT COUNT(*) FROM research_alert_events WHERE rule_id=? AND observation_time=?",
    Integer.class,r.id(),Timestamp.from(when));
   if(exists>0)continue;
   GeneratedKeyHolder key=new GeneratedKeyHolder();
   final BigDecimal captured=value;
   final Instant capturedTime=when;
   jdbc.update(conn->{
    PreparedStatement ps=conn.prepareStatement(
     "INSERT INTO research_alert_events(rule_id,observed_value,observation_time) VALUES (?,?,?)",
     new String[]{"id"});
    ps.setLong(1,r.id());ps.setBigDecimal(2,captured);ps.setTimestamp(3,Timestamp.from(capturedTime));
    return ps;
   },key);
   events.add(new Event(key.getKey().longValue(),r.id(),value,when));
  }
  return new Check(enabled.size(),events.size(),List.copyOf(events),
   "Local persisted events only; no email, phone or push delivery implemented");
 }
 @Transactional(readOnly=true)
 public List<Event> events(String raw,int limit){
  if(limit<1||limit>200)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid limit");
  return jdbc.query("SELECT e.id,e.rule_id,e.observed_value,e.observation_time FROM research_alert_events e JOIN research_alert_rules r ON r.id=e.rule_id JOIN assets a ON a.id=r.asset_id WHERE a.symbol=? ORDER BY e.id DESC LIMIT ?",
   (rs,i)->new Event(rs.getLong(1),rs.getLong(2),rs.getBigDecimal(3),rs.getTimestamp(4).toInstant()),symbol(raw),limit);
 }
}
