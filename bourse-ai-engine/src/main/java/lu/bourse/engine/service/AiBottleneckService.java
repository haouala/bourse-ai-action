package lu.bourse.engine.service;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import java.net.URI;
import java.sql.Date;
import java.time.LocalDate;
import java.util.*;

@Service
public class AiBottleneckService {
 private final JdbcTemplate jdbc;
 public AiBottleneckService(JdbcTemplate jdbc){this.jdbc=jdbc;}
 public record Submission(String symbol,String signalType,String direction,String confidence,String sourceUrl,String summary,LocalDate observedDate){}
 public record Evidence(long id,String signalType,String direction,String confidence,String sourceUrl,String summary,LocalDate observedDate){}
 public record Report(String symbol,BottleneckRules.Result assessment,List<Evidence> evidence,String disclaimer){}
 private static String symbol(String raw){
  if(raw==null||!raw.matches("[A-Za-z0-9._-]{1,40}"))
   throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid symbol");
  return raw.toUpperCase(Locale.ROOT);
 }
 @Transactional
 public Evidence add(Submission request){
  if(request==null)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Missing evidence");
  String ticker=symbol(request.symbol());
  if(!Set.of("SUPPLY_SHORTAGE","CAPACITY_EXPANSION","ORDER_BACKLOG","VALUATION_RISK","EXECUTION_RISK").contains(request.signalType())||
    !Set.of("POSITIVE","NEGATIVE").contains(request.direction())||
    !Set.of("LOW","MEDIUM","HIGH").contains(request.confidence())||
    request.summary()==null||request.summary().isBlank()||request.summary().length()>1500||
    request.observedDate()==null||request.observedDate().isAfter(LocalDate.now()))
   throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid signal metadata");
  try{
   URI uri=URI.create(request.sourceUrl());
   if(request.sourceUrl().length()>1200||!"https".equalsIgnoreCase(uri.getScheme())||
      uri.getHost()==null||uri.getUserInfo()!=null||uri.getFragment()!=null)
    throw new IllegalArgumentException();
  }catch(Exception e){throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"HTTPS source required");}
  List<Long> ids=jdbc.query("SELECT id FROM assets WHERE symbol=?",(r,i)->r.getLong(1),ticker);
  if(ids.isEmpty())throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Unknown asset");
  Long asset=ids.get(0);
  List<Long> duplicates=jdbc.query("SELECT id FROM ai_bottleneck_evidence WHERE asset_id=? AND signal_type=? AND source_url=?",
   (r,i)->r.getLong(1),asset,request.signalType(),request.sourceUrl());
  if(!duplicates.isEmpty())throw new ResponseStatusException(HttpStatus.CONFLICT,"Source evidence already registered");
  jdbc.update("INSERT INTO ai_bottleneck_evidence(asset_id,signal_type,direction,confidence,source_url,summary,observed_date) VALUES (?,?,?,?,?,?,?)",
   asset,request.signalType(),request.direction(),request.confidence(),request.sourceUrl(),request.summary(),
   Date.valueOf(request.observedDate()));
  return list(ticker).stream().filter(e->e.sourceUrl().equals(request.sourceUrl())&&
    e.signalType().equals(request.signalType())).findFirst().orElseThrow();
 }
 @Transactional(readOnly=true)
 public List<Evidence> list(String raw){
  String ticker=symbol(raw);
  return jdbc.query("SELECT e.id,e.signal_type,e.direction,e.confidence,e.source_url,e.summary,e.observed_date FROM ai_bottleneck_evidence e JOIN assets a ON a.id=e.asset_id WHERE a.symbol=? ORDER BY e.observed_date DESC,e.id DESC LIMIT 200",
   (r,i)->new Evidence(r.getLong(1),r.getString(2),r.getString(3),r.getString(4),
    r.getString(5),r.getString(6),r.getDate(7).toLocalDate()),ticker);
 }
 @Transactional(readOnly=true)
 public Report assess(String raw){
  String ticker=symbol(raw);
  List<Evidence> all=list(ticker),recent=new ArrayList<>();
  for(Evidence e:all)if(!e.observedDate().isBefore(LocalDate.now().minusDays(180)))recent.add(e);
  List<BottleneckRules.Signal> rules=new ArrayList<>();
  for(Evidence e:recent)rules.add(new BottleneckRules.Signal(e.signalType(),e.direction(),e.confidence()));
  return new Report(ticker,BottleneckRules.evaluate(rules),List.copyOf(recent),
   "Curated evidence only; source URL is recorded but neither fetched nor verified here. No investment recommendation.");
 }
}
