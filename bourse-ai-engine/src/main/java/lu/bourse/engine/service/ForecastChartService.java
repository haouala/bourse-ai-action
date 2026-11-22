package lu.bourse.engine.service;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;

/** Chart-ready, read-only datasets. No invented intermediate projections. */
@Service
public class ForecastChartService {
 private final JdbcTemplate jdbc;
 private final FuturePriceForecastService forecasts;
 private final PredictionTrackingService predictions;
 private final MachineLearningService ml;

 public ForecastChartService(JdbcTemplate jdbc,FuturePriceForecastService forecasts,
  PredictionTrackingService predictions,MachineLearningService ml) {
  this.jdbc=jdbc;this.forecasts=forecasts;this.predictions=predictions;this.ml=ml;
 }

 public record HistoricalPoint(Instant timestamp,BigDecimal close){}
 public record HistoricalChart(String symbol,String currency,String seriesType,
  List<HistoricalPoint> points,String disclaimer){}
 public record ScenarioPoint(int horizonDays,LocalDate targetDate,BigDecimal pessimistic,
  BigDecimal central,BigDecimal optimistic,String status,int trainingExamples,int holdoutExamples,
  double holdoutErrorPct,double noChangeErrorPct,double bandCoverage){}
 public record ScenarioFailure(int horizonDays,String reason){}
 public record ScenarioChart(String symbol,String currency,Instant referenceTime,
  BigDecimal referencePrice,String method,String version,List<ScenarioPoint> points,
  List<ScenarioFailure> unavailable,List<String> cautions){}
 public record PredictionPoint(long id,Instant issuedAt,Instant targetTime,Instant observedAt,
  int horizonDays,String modelName,String modelVersion,BigDecimal referencePrice,
  BigDecimal predictedPrice,BigDecimal realizedPrice,BigDecimal absoluteErrorPct,
  Boolean directionCorrect,String status){}
 public record PredictionChart(String symbol,int count,List<PredictionPoint> points,
  String disclaimer){}
 public record MlMetricPoint(long runId,Instant trainedAt,int horizonSessions,String modelVersion,
  int testSamples,double directionAccuracyPct,double baselineAccuracyPct,double brierScore,
  double baselineBrier,String status){}
 public record MlChart(String symbol,int count,List<MlMetricPoint> points,String disclaimer){}

 private static String ticker(String raw){
  if(raw==null||!raw.matches("[A-Za-z0-9._-]{1,40}"))
   throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid symbol");
  return raw.toUpperCase(Locale.ROOT);
 }
 private String currency(String symbol){
  List<String> values=jdbc.query("SELECT currency FROM assets WHERE symbol=?",
   (r,i)->r.getString(1),symbol);
  if(values.isEmpty())throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Unknown asset");
  return values.get(0);
 }

 @Transactional(readOnly=true)
 public HistoricalChart historical(String rawSymbol,int limit){
  String symbol=ticker(rawSymbol);
  if(limit<2||limit>1000)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"limit must be 2..1000");
  String unit=currency(symbol);
  List<HistoricalPoint> prices=jdbc.query(
   "SELECT c.open_time,c.close_price FROM market_candles c JOIN assets a ON a.id=c.asset_id WHERE a.symbol=? AND c.timeframe='1d' AND c.open_time<=CURRENT_TIMESTAMP ORDER BY c.open_time DESC LIMIT ?",
   (r,i)->new HistoricalPoint(r.getTimestamp(1).toInstant(),r.getBigDecimal(2)),symbol,limit);
  Collections.reverse(prices);
  return new HistoricalChart(symbol,unit,"OBSERVED_DAILY_CLOSE",List.copyOf(prices),
   "Observed canonical closes only; verify corporate-action adjustments, exchanges and freshness.");
 }

 @Transactional(readOnly=true)
 public ScenarioChart scenarios(String rawSymbol){
  String symbol=ticker(rawSymbol);
  String unit=currency(symbol);
  List<ScenarioPoint> points=new ArrayList<>();
  List<ScenarioFailure> failures=new ArrayList<>();
  List<String> cautions=new ArrayList<>();
  Instant referenceTime=null;BigDecimal referencePrice=null;String method=null,version=null;
  for(int horizon:List.of(7,30,90,180)){
   try{
    var f=forecasts.preview(symbol,horizon);
    if(referenceTime==null){referenceTime=f.referenceTime();referencePrice=f.referencePrice();
      method=f.method();version=f.version();cautions.addAll(f.cautions());}
    points.add(new ScenarioPoint(horizon,f.approximateTargetDate(),f.pessimistic(),f.central(),
     f.optimistic(),f.status(),f.trainingExamples(),f.holdoutExamples(),
     f.holdoutMedianAbsolutePctError(),f.holdoutNaiveMedianAbsolutePctError(),
     f.holdoutBandCoverage()));
   }catch(ResponseStatusException e){
    if(e.getStatusCode()!=HttpStatus.UNPROCESSABLE_ENTITY)throw e;
    failures.add(new ScenarioFailure(horizon,"INSUFFICIENT_OR_UNRELIABLE_HISTORY"));
   }
  }
  // The reference close is not presented as a forecast: it is a true observed price.
  cautions.add("Plot only the returned dated horizon points. Connecting endpoints is a visual aid, NOT a validated daily price path.");
  cautions.add("The 10th/90th empirical scenarios are NOT calibrated confidence bounds.");
  return new ScenarioChart(symbol,unit,referenceTime,referencePrice,method,version,
   List.copyOf(points),List.copyOf(failures),List.copyOf(new LinkedHashSet<>(cautions)));
 }

 @Transactional(readOnly=true)
 public PredictionChart predictionErrors(String rawSymbol,int limit){
  String symbol=ticker(rawSymbol);
  currency(symbol); // distinguish unknown asset from an empty prediction history
  List<PredictionPoint> points=new ArrayList<>();
  for(var p:predictions.list(symbol,limit)){
   points.add(new PredictionPoint(p.id(),p.predictedAt(),p.targetTime(),p.realizedTime(),
    p.horizonDays(),p.modelName(),p.modelVersion(),p.referencePrice(),
    p.predictedPrice(),p.realizedPrice(),p.absoluteErrorPct(),p.directionCorrect(),p.status()));
  }
  // Grouping or joining independently issued models must never silently produce false comparable lines.
  Collections.reverse(points);
  return new PredictionChart(symbol,points.size(),List.copyOf(points),
   "Only EVALUATED rows have observed prices and errors. PENDING rows must show a gap, never zero.");
 }

 @Transactional(readOnly=true)
 public MlChart mlExperiments(String rawSymbol,int limit){
  String symbol=ticker(rawSymbol);
  currency(symbol);
  List<MlMetricPoint> points=new ArrayList<>();
  for(var run:ml.history(symbol,limit)){
   points.add(new MlMetricPoint(run.id(),run.createdTime(),run.horizonSessions(),
    run.version(),run.testSamples(),run.directionAccuracy()*100,
    run.baselineAccuracy()*100,run.brierScore(),run.baselineBrier(),run.status()));
  }
  Collections.reverse(points);
  return new MlChart(symbol,points.size(),List.copyOf(points),
   "Historical holdout metrics are not live probability of profit. Filter by model version and horizon before comparing.");
 }
}
