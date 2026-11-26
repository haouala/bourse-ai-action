package lu.bourse.engine.service;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.*;
import java.util.*;

/**
 * Historical return scenario estimator. Returns three price scenarios, NOT a
 * calibrated probability distribution or a model-7 ML inference.
 */
@Service
public class FuturePriceForecastService {
 private final JdbcTemplate jdbc;
 private final DataQualityService quality;
 private final PredictionTrackingService tracking;
 private final ClosedMarketHistory history;private final Clock clock;
 public FuturePriceForecastService(JdbcTemplate jdbc,DataQualityService quality,PredictionTrackingService tracking,ClosedMarketHistory history,Clock clock){
  this.jdbc=jdbc;this.quality=quality;this.tracking=tracking;this.history=history;this.clock=clock;
 }
 public record Forecast(String symbol,String currency,Instant referenceTime,BigDecimal referencePrice,
   int horizonDays,LocalDate approximateTargetDate,BigDecimal pessimistic,BigDecimal central,
   BigDecimal optimistic,int trainingExamples,int holdoutExamples,
   double holdoutMedianAbsolutePctError,double holdoutNaiveMedianAbsolutePctError,
   double holdoutBandCoverage,String method,String version,String status,List<String> cautions){}
 public record Issued(Forecast forecast,PredictionTrackingService.Prediction trackingRecord){}
 private record Observed(Instant time,BigDecimal close){}

 @Transactional(readOnly=true)
 public Forecast preview(String rawSymbol,int horizonDays){
  if(rawSymbol==null||!rawSymbol.matches("[A-Za-z0-9._-]{1,40}")||
     !PriceForecastMath.HORIZONS.contains(horizonDays))
   throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid symbol or horizon (7,30,90,180 days)");
  String ticker=rawSymbol.toUpperCase(Locale.ROOT);
  DataQualityService.Report qualityReport=quality.check(ticker,"1d");
  if("BLOCKED".equals(qualityReport.status()))
   throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,"Historical OHLCV failed quality checks");
  List<String> currencies=jdbc.query("SELECT currency FROM assets WHERE symbol=?",
   (rs,i)->rs.getString(1),ticker);
  if(currencies.isEmpty())throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Unknown symbol");
  List<Observed> prices=history.load(ticker,"1d",10000,clock.instant()).bars().stream()
   .map(b->new Observed(b.openTime(),b.close())).toList();
  if(prices.size()<250)throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,"At least 250 daily candles required");
  Observed latest=prices.get(prices.size()-1);
  Instant now=clock.instant();
  if(latest.time().isAfter(now) || Duration.between(latest.time(),now).toDays()>7)
   throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,"Reference price too old or in future");
  List<PriceForecastMath.Close> closes=new ArrayList<>();
  for(Observed p:prices)closes.add(new PriceForecastMath.Close(
   p.time().atZone(ZoneOffset.UTC).toLocalDate(),p.close().doubleValue()));
  PriceForecastMath.Estimate estimate;
  try{estimate=PriceForecastMath.estimate(closes,horizonDays);}
  catch(IllegalArgumentException e){
   throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,"Not enough valid historical outcomes for the requested horizon");
  }
  BigDecimal low=price(latest.close(),estimate.lowReturn());
  BigDecimal middle=price(latest.close(),estimate.medianReturn());
  BigDecimal high=price(latest.close(),estimate.highReturn());
  boolean beatsNaive=estimate.validation().medianAbsolutePctError()<
   estimate.validation().naiveMedianAbsolutePctError();
  List<String> cautions=new ArrayList<>();
  cautions.add("Research-only empirical scenarios, not guaranteed future prices or calibrated probabilities");
  cautions.add("Historical returns may overlap; sample count overstates the number of independent outcomes");
  cautions.add("No split/dividend adjustment verification, macro regime adjustment, currency conversion or fees");
  cautions.add("Dates use UTC daily records and calendar-day targets, evaluated against first close within 7 days");
  if(!qualityReport.issues().isEmpty())
   cautions.add("Historical price-quality warnings: "+qualityReport.issues().size());
  if(!beatsNaive)cautions.add("Historical holdout central price did not beat a no-change price baseline on median absolute percent error");
  // Nominal 10/90 percentiles are empirical scenarios, NOT a calibrated 80% interval.
  return new Forecast(ticker,currencies.get(0),latest.time(),latest.close(),horizonDays,
   latest.time().atZone(ZoneOffset.UTC).toLocalDate().plusDays(horizonDays),
   low,middle,high,estimate.trainingExamples(),estimate.holdoutExamples(),
   estimate.validation().medianAbsolutePctError(),estimate.validation().naiveMedianAbsolutePctError(),
   estimate.validation().centralBandCoverage(),"HISTORICAL_HOLDOUT_EMPIRICAL_LOG_RETURN_QUANTILES",
   PriceForecastMath.VERSION,beatsNaive?"EXPLORATORY":"BASELINE_NOT_BEATEN",List.copyOf(cautions));
 }
 private static BigDecimal price(BigDecimal origin,double logReturn){
  double multiplier=Math.exp(logReturn);
  if(!Double.isFinite(multiplier)||multiplier<=0)
   throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,"Extreme return prevents reliable scenario");
  BigDecimal result=origin.multiply(BigDecimal.valueOf(multiplier)).setScale(6,RoundingMode.HALF_UP);
  if(result.signum()<=0||result.precision()>24)
   throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,"Scenario exceeds supported price precision");
  return result;
 }
 /**
  * Explicit, auditable creation only. Preview GET never writes a prediction.
  * Module 3 settles the central-price prediction once actual prices arrive.
  */
 @Transactional
 public Issued issue(String symbol,int horizonDays){
  Forecast forecast=preview(symbol,horizonDays);
  PredictionTrackingService.Prediction saved=tracking.submit(
   new PredictionTrackingService.Submission(forecast.symbol(),horizonDays,forecast.central(),
    "EMPIRICAL_PRICE_QUANTILES",PriceForecastMath.VERSION));
  if(saved.referencePrice().compareTo(forecast.referencePrice())!=0 ||
     !saved.referenceTime().equals(forecast.referenceTime()))
   throw new ResponseStatusException(HttpStatus.CONFLICT,"Reference price changed while issuing forecast; retry");
  return new Issued(forecast,saved);
 }
}
