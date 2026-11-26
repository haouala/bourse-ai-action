package lu.bourse.engine.service;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import java.math.BigDecimal;
import java.sql.*;
import java.time.Instant;
import java.util.*;

/** Audited research-only ML experiments, not an order or forward-price service. */
@Service
public class MachineLearningService {
 private final JdbcTemplate jdbc;
 private final DataQualityService quality;
 public MachineLearningService(JdbcTemplate jdbc,DataQualityService quality){
  this.jdbc=jdbc;this.quality=quality;
 }
 public record Run(long id,String symbol,String algorithm,String version,int horizonSessions,
                   int trainSamples,int testSamples,Instant trainingCutoff,Instant lastTestTime,
                   double directionAccuracy,double baselineAccuracy,double brierScore,double baselineBrier,
                   String status,String notes,Instant createdTime){}
 public record Training(Run run,DirectionLearning.Evaluation validation){}
 private record Price(Instant time,double close){}
 private static String symbol(String raw){
  if(raw==null||!raw.matches("[A-Za-z0-9._-]{1,40}"))
   throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid asset symbol");
  return raw.toUpperCase(Locale.ROOT);
 }

 @Transactional
 public Training train(String rawSymbol,int horizonSessions){
  String ticker=symbol(rawSymbol);
  if(!Set.of(5,10,20).contains(horizonSessions))
   throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Horizon must be 5, 10 or 20 sessions");
  DataQualityService.Report report=quality.check(ticker,"1d");
  if("BLOCKED".equals(report.status()))
   throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,"OHLCV quality gate failed");
  List<Price> prices=jdbc.query(
   "SELECT c.open_time,c.close_price FROM market_candles c JOIN assets a ON a.id=c.asset_id WHERE a.symbol=? AND c.timeframe='1d' AND c.open_time<=CURRENT_TIMESTAMP ORDER BY c.open_time DESC LIMIT 10000",
   (rs,i)->new Price(rs.getTimestamp(1).toInstant(),rs.getBigDecimal(2).doubleValue()),ticker);
  Collections.reverse(prices);
  if(prices.size()<240)throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,"At least 240 daily closes required");
  Set<java.time.LocalDate> dates=new HashSet<>();
  for(Price p:prices){
   if(!dates.add(p.time().atZone(java.time.ZoneOffset.UTC).toLocalDate()))
    throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,"Duplicate daily dates");
  }
  DirectionLearning.Evaluation validation;
  try {validation=DirectionLearning.evaluate(prices.stream().map(Price::close).toList(),horizonSessions);}
  catch(IllegalArgumentException ex){
   throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,"Invalid training series or folds");
  }
  int cutoffIndex=prices.size()*85/100;
  Instant cutoff=prices.get(cutoffIndex).time();
  Instant lastTest=prices.get(prices.size()-1).time();
  String status=validation.brier()<validation.baselineBrier() &&
    validation.accuracy()>=validation.baselineAccuracy()?"RESEARCH_ONLY":"BASELINE_NOT_BEATEN";
  String notes="Expanding 55/70/85% chronological holdouts; purged labels; 3 price-only features; "+
    "no splits/dividend adjustments, market factors, external fundamental/news inputs or calibration. "+
    "Historical classification is not a price forecast.";
  Long assetId=jdbc.queryForObject("SELECT id FROM assets WHERE symbol=?",Long.class,ticker);
  GeneratedKeyHolder keys=new GeneratedKeyHolder();
  jdbc.update(connection->{
   PreparedStatement ps=connection.prepareStatement(
    "INSERT INTO ml_training_runs(asset_id,algorithm,version,horizon_sessions,feature_spec,train_samples,test_samples,training_cutoff,last_test_time,direction_accuracy,baseline_accuracy,brier_score,baseline_brier,status,notes) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
    new String[]{"id"});
   ps.setLong(1,assetId);
   ps.setString(2,"LOGISTIC_L2_GRADIENT_DESCENT");ps.setString(3,DirectionLearning.VERSION);
   ps.setInt(4,horizonSessions);ps.setString(5,DirectionLearning.FEATURES);
   ps.setInt(6,validation.trainSamples());ps.setInt(7,validation.testSamples());
   ps.setTimestamp(8,Timestamp.from(cutoff));ps.setTimestamp(9,Timestamp.from(lastTest));
   ps.setBigDecimal(10,decimal(validation.accuracy()));ps.setBigDecimal(11,decimal(validation.baselineAccuracy()));
   ps.setBigDecimal(12,decimal(validation.brier()));ps.setBigDecimal(13,decimal(validation.baselineBrier()));
   ps.setString(14,status);ps.setString(15,notes);
   return ps;
  },keys);
  return new Training(get(keys.getKey().longValue()),validation);
 }
 private static BigDecimal decimal(double n){return BigDecimal.valueOf(n).setScale(8,java.math.RoundingMode.HALF_UP);}
 @Transactional(readOnly=true)
 public Run get(long id){
  List<Run> found=jdbc.query(
   "SELECT r.id,a.symbol,r.algorithm,r.version,r.horizon_sessions,r.train_samples,r.test_samples,r.training_cutoff,r.last_test_time,r.direction_accuracy,r.baseline_accuracy,r.brier_score,r.baseline_brier,r.status,r.notes,r.created_time FROM ml_training_runs r JOIN assets a ON a.id=r.asset_id WHERE r.id=?",
   (r,i)->map(r),id);
  if(found.isEmpty())throw new ResponseStatusException(HttpStatus.NOT_FOUND,"ML run not found");
  return found.get(0);
 }
 private static Run map(ResultSet r)throws SQLException{
  return new Run(r.getLong(1),r.getString(2),r.getString(3),r.getString(4),r.getInt(5),r.getInt(6),r.getInt(7),
   r.getTimestamp(8).toInstant(),r.getTimestamp(9).toInstant(),r.getBigDecimal(10).doubleValue(),
   r.getBigDecimal(11).doubleValue(),r.getBigDecimal(12).doubleValue(),r.getBigDecimal(13).doubleValue(),
   r.getString(14),r.getString(15),r.getTimestamp(16).toInstant());
 }
 @Transactional(readOnly=true)
 public List<Run> history(String rawSymbol,int limit){
  String ticker=symbol(rawSymbol);
  if(limit<1||limit>100)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Limit must be 1..100");
  return jdbc.query(
   "SELECT r.id,a.symbol,r.algorithm,r.version,r.horizon_sessions,r.train_samples,r.test_samples,r.training_cutoff,r.last_test_time,r.direction_accuracy,r.baseline_accuracy,r.brier_score,r.baseline_brier,r.status,r.notes,r.created_time FROM ml_training_runs r JOIN assets a ON a.id=r.asset_id WHERE a.symbol=? ORDER BY r.created_time DESC,r.id DESC LIMIT ?",
   (r,i)->map(r),ticker,limit);
 }
}
