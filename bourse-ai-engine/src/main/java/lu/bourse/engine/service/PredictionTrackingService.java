package lu.bourse.engine.service;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

@Service
public class PredictionTrackingService {
 private static final Set<Integer> HORIZONS=Set.of(7,30,90,180);
 private final JdbcTemplate jdbc;
 private final DataQualityService quality;
 public PredictionTrackingService(JdbcTemplate jdbc,DataQualityService quality){this.jdbc=jdbc;this.quality=quality;}

 public record Submission(String symbol,int horizonDays,BigDecimal predictedPrice,String modelName,String modelVersion){}
 public record Prediction(long id,String symbol,String modelName,String modelVersion,int horizonDays,
                          BigDecimal referencePrice,Instant referenceTime,BigDecimal predictedPrice,
                          Instant predictedAt,Instant targetTime,String status,BigDecimal realizedPrice,
                          Instant realizedTime,BigDecimal signedErrorPct,BigDecimal absoluteErrorPct,Boolean directionCorrect){}
 public record Evaluation(String symbol,int evaluated,int stillPending){}
 public record Metrics(String symbol,String modelName,String modelVersion,int horizonDays,int evaluated,
                       double meanAbsoluteErrorPct,double directionAccuracyPct){}
 private record Observation(Instant date,BigDecimal close){}

 @Transactional
 public Prediction submit(Submission request) {
  if(request==null || request.symbol()==null || !request.symbol().matches("[A-Za-z0-9._-]{1,40}") ||
      !HORIZONS.contains(request.horizonDays()) || request.predictedPrice()==null ||
      request.predictedPrice().signum()<=0 || request.predictedPrice().precision()>24 ||
      !validName(request.modelName()) || !validName(request.modelVersion()))
   throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid prediction request");
  String symbol=request.symbol().toUpperCase(Locale.ROOT);
  if("BLOCKED".equals(quality.check(symbol,"1d").status()))
   throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,"Historical prices failed quality checks");
  List<Observation> latest=jdbc.query(
   "SELECT c.open_time,c.close_price FROM market_candles c JOIN assets a ON a.id=c.asset_id WHERE a.symbol=? AND c.timeframe='1d' AND c.open_time<=CURRENT_TIMESTAMP ORDER BY c.open_time DESC LIMIT 1",
   (r,i)->new Observation(r.getTimestamp(1).toInstant(),r.getBigDecimal(2)),symbol);
  if(latest.isEmpty() || latest.get(0).close().signum()<=0)
   throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,"Missing valid reference price");
  Instant now=Instant.now();
  if(Duration.between(latest.get(0).date(),now).toDays()>7)
   throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,"Reference price is stale");
  Instant target=now.plus(Duration.ofDays(request.horizonDays()));
  Long id=jdbc.queryForObject("SELECT id FROM assets WHERE symbol=?",Long.class,symbol);
  var keys=new GeneratedKeyHolder();
  jdbc.update(con->{
   var ps=con.prepareStatement(
    "INSERT INTO prediction_records(asset_id,model_name,model_version,horizon_days,predicted_price,reference_price,reference_time,predicted_at,target_time) VALUES (?,?,?,?,?,?,?,?,?)",
    new String[]{"id"});
   ps.setLong(1,id);ps.setString(2,request.modelName());ps.setString(3,request.modelVersion());
   ps.setInt(4,request.horizonDays());ps.setBigDecimal(5,request.predictedPrice());
   ps.setBigDecimal(6,latest.get(0).close());ps.setTimestamp(7,Timestamp.from(latest.get(0).date()));
   ps.setTimestamp(8,Timestamp.from(now));ps.setTimestamp(9,Timestamp.from(target));return ps;
  },keys);
  Number inserted=keys.getKey();
  return get(inserted.longValue());
 }
 private static boolean validName(String s) {return s!=null && s.matches("[A-Za-z0-9._-]{1,80}");}

 @Transactional(readOnly=true)
 public Prediction get(long id){
  List<Prediction> found=jdbc.query(
   "SELECT p.id,a.symbol,p.model_name,p.model_version,p.horizon_days,p.reference_price,p.reference_time,p.predicted_price,p.predicted_at,p.target_time,p.status,p.realized_price,p.realized_time,p.signed_error_pct,p.absolute_error_pct,p.direction_correct FROM prediction_records p JOIN assets a ON a.id=p.asset_id WHERE p.id=?",
   (r,i)->new Prediction(r.getLong(1),r.getString(2),r.getString(3),r.getString(4),r.getInt(5),
    r.getBigDecimal(6),r.getTimestamp(7).toInstant(),r.getBigDecimal(8),r.getTimestamp(9).toInstant(),
    r.getTimestamp(10).toInstant(),r.getString(11),r.getBigDecimal(12),
    r.getTimestamp(13)==null?null:r.getTimestamp(13).toInstant(),r.getBigDecimal(14),r.getBigDecimal(15),
    (Boolean)r.getObject(16)),id);
  if(found.isEmpty())throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Prediction not found");
  return found.get(0);
 }
 @Transactional(readOnly=true)
 public List<Prediction> list(String rawSymbol,int limit){
  String symbol=normalize(rawSymbol);
  if(limit<1||limit>200)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Limit must be 1..200");
  return jdbc.query("SELECT id FROM prediction_records WHERE asset_id=(SELECT id FROM assets WHERE symbol=?) ORDER BY predicted_at DESC,id DESC LIMIT ?",
   (r,i)->get(r.getLong(1)),symbol,limit);
 }

 // Only observations with timestamps at or after the due date can settle a prediction.
 // If no market observation within seven calendar days, remain PENDING for human review.
 @Transactional
 public Evaluation evaluate(String rawSymbol){
  String symbol=normalize(rawSymbol);
  if(jdbc.queryForObject("SELECT COUNT(*) FROM assets WHERE symbol=?",Integer.class,symbol)==0)
   throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Unknown asset");
  List<Prediction> pending=jdbc.query(
   "SELECT p.id FROM prediction_records p JOIN assets a ON a.id=p.asset_id WHERE a.symbol=? AND p.status='PENDING' AND p.target_time<=CURRENT_TIMESTAMP ORDER BY p.target_time ASC LIMIT 500",
   (r,i)->get(r.getLong(1)),symbol);
  int processed=0;
  for(Prediction p:pending){
   List<Observation> observations=jdbc.query(
    "SELECT c.open_time,c.close_price FROM market_candles c JOIN assets a ON a.id=c.asset_id WHERE a.symbol=? AND c.timeframe='1d' AND c.open_time>=? AND c.open_time<=? AND c.open_time<=CURRENT_TIMESTAMP ORDER BY c.open_time ASC LIMIT 1",
    (r,i)->new Observation(r.getTimestamp(1).toInstant(),r.getBigDecimal(2)),
    symbol,Timestamp.from(p.targetTime()),Timestamp.from(p.targetTime().plus(Duration.ofDays(7))));
   if(observations.isEmpty()||observations.get(0).close().signum()<=0)continue;
   Observation actual=observations.get(0);
   BigDecimal error=p.predictedPrice().subtract(actual.close()).multiply(BigDecimal.valueOf(100))
    .divide(actual.close(),6,RoundingMode.HALF_UP);
   boolean direction=p.predictedPrice().compareTo(p.referencePrice())==0
    ?actual.close().compareTo(p.referencePrice())==0
    :Integer.signum(p.predictedPrice().compareTo(p.referencePrice()))==Integer.signum(actual.close().compareTo(p.referencePrice()));
   int changed=jdbc.update("UPDATE prediction_records SET status='EVALUATED',realized_time=?,realized_price=?,signed_error_pct=?,absolute_error_pct=?,direction_correct=?,updated_time=CURRENT_TIMESTAMP WHERE id=? AND status='PENDING'",
    Timestamp.from(actual.date()),actual.close(),error,error.abs(),direction,p.id());
   processed+=changed;
  }
  int remaining=jdbc.queryForObject("SELECT COUNT(*) FROM prediction_records p JOIN assets a ON a.id=p.asset_id WHERE a.symbol=? AND p.status='PENDING'",Integer.class,symbol);
  return new Evaluation(symbol,processed,remaining);
 }
 @Transactional(readOnly=true)
 public List<Metrics> metrics(String rawSymbol){
  String symbol=normalize(rawSymbol);
  return jdbc.query(
   "SELECT p.model_name,p.model_version,p.horizon_days,COUNT(*),AVG(p.absolute_error_pct),AVG(CASE WHEN p.direction_correct THEN 100.0 ELSE 0.0 END) FROM prediction_records p JOIN assets a ON a.id=p.asset_id WHERE a.symbol=? AND p.status='EVALUATED' GROUP BY p.model_name,p.model_version,p.horizon_days ORDER BY p.model_name,p.model_version,p.horizon_days",
   (r,i)->new Metrics(symbol,r.getString(1),r.getString(2),r.getInt(3),r.getInt(4),r.getBigDecimal(5).doubleValue(),r.getBigDecimal(6).doubleValue()),symbol);
 }
 private static String normalize(String symbol){
  if(symbol==null||!symbol.matches("[A-Za-z0-9._-]{1,40}"))throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid symbol");
  return symbol.toUpperCase(Locale.ROOT);
 }
}
