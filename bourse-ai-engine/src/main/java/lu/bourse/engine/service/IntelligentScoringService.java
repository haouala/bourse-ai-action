package lu.bourse.engine.service;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;

@Service
public class IntelligentScoringService {
 private final FuturePriceForecastService forecasts;
 private final DataQualityService quality;
 private final JdbcTemplate jdbc;
 private final TechnicalAnalysisService technical;private final ClosedMarketHistory history;private final java.time.Clock clock;
 public IntelligentScoringService(FuturePriceForecastService forecasts,DataQualityService quality,JdbcTemplate jdbc,TechnicalAnalysisService technical,ClosedMarketHistory history,java.time.Clock clock){
  this.forecasts=forecasts;this.quality=quality;this.jdbc=jdbc;this.technical=technical;this.history=history;this.clock=clock;
 }
 public record Report(String symbol,int horizonDays,Instant referenceTime,BigDecimal referencePrice,
  Integer opportunityScore,Integer riskScore,Integer reliabilityScore,String status,
  int evaluatedForecasts,Double directionAccuracyPct,List<String> reasons,String disclaimer,
  TechnicalAnalysisService.Report technicalAnalysis,Integer technicalOpportunityDelta,Integer technicalRiskDelta,String scoreVersion){}
 @Transactional(readOnly=true)
 public Report score(String rawSymbol,int horizonDays){
  if(rawSymbol==null||!rawSymbol.matches("[A-Za-z0-9._-]{1,40}")||
   !PriceForecastMath.HORIZONS.contains(horizonDays))
   throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid symbol or horizon");
  String symbol=rawSymbol.toUpperCase(Locale.ROOT);
  var qc=quality.check(symbol,"1d");
  if("BLOCKED".equals(qc.status()))return new Report(symbol,horizonDays,qc.latestCandle(),null,
   null,null,null,"DATA_BLOCKED",0,null,List.of("Quality gate blocked the inputs"),
   "No score is a guaranteed profit probability or trading recommendation",null,0,0,"TECHNICAL_SCORE_V2");
  var technicalReport=technical.analyze(symbol,"1d",clock.instant());
  FuturePriceForecastService.Forecast forecast;
  try{forecast=forecasts.preview(symbol,horizonDays);}
  catch(ResponseStatusException ex){
   if(ex.getStatusCode()!=HttpStatus.UNPROCESSABLE_ENTITY)throw ex;
   return new Report(symbol,horizonDays,qc.latestCandle(),null,null,null,null,
    "INSUFFICIENT_HISTORY",0,null,List.of("Historical scenario not available"),
    "No score is a guaranteed profit probability or trading recommendation",technicalReport,0,0,"TECHNICAL_SCORE_V2");
  }
  List<Double> closes=new ArrayList<>(history.load(symbol,"1d",61,clock.instant()).bars().stream().map(b->b.close().doubleValue()).toList());
  Collections.reverse(closes);
  double sum=0,sum2=0;int n=0;
  for(int i=0;i+1<closes.size();i++){
   double r=Math.log(closes.get(i)/closes.get(i+1));
   if(!Double.isFinite(r))throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,"Invalid daily returns");
   sum+=r;sum2+=r*r;n++;
  }
  if(n<30)throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,"Insufficient volatility sample");
  double vol=Math.sqrt(Math.max(0,sum2/n-Math.pow(sum/n,2)));
  double origin=forecast.referencePrice().doubleValue();
  double projected=(forecast.central().doubleValue()/origin-1)*100;
  double downside=(forecast.pessimistic().doubleValue()/origin-1)*100;
  // Only settled forecasts from same research method/version/horizon count toward evidence.
  List<Integer> counts=jdbc.query("SELECT COUNT(*),AVG(CASE WHEN direction_correct THEN 100.0 ELSE 0.0 END) FROM prediction_records p JOIN assets a ON a.id=p.asset_id WHERE a.symbol=? AND p.status='EVALUATED' AND p.model_name='EMPIRICAL_PRICE_QUANTILES' AND p.model_version=? AND p.horizon_days=?",
   (rs,i)->rs.getInt(1),symbol,PriceForecastMath.VERSION,horizonDays);
  int evaluated=counts.get(0);
  Double accuracy=jdbc.queryForObject("SELECT AVG(CASE WHEN p.direction_correct THEN 100.0 ELSE 0.0 END) FROM prediction_records p JOIN assets a ON a.id=p.asset_id WHERE a.symbol=? AND p.status='EVALUATED' AND p.model_name='EMPIRICAL_PRICE_QUANTILES' AND p.model_version=? AND p.horizon_days=?",
   (rs,i)->{BigDecimal v=rs.getBigDecimal(1);return v==null?null:v.doubleValue();},symbol,PriceForecastMath.VERSION,horizonDays);
  var calculated=ScoringRules.calculate(projected,downside,vol,qc.issues().size(),
   forecast.holdoutMedianAbsolutePctError(),forecast.holdoutNaiveMedianAbsolutePctError(),evaluated,accuracy);
  boolean comparable=technicalReport.usable()&&forecast.referenceTime().equals(technicalReport.referenceTime());
  double scale=horizonDays<=7?1:horizonDays<=30?.5:.25;
  int opportunityDelta=comparable?(int)Math.round(technicalReport.analysis().opportunityDelta()*scale):0;
  int riskDelta=comparable?(int)Math.round(technicalReport.analysis().riskDelta()*scale):0;
  var enriched=ScoringRules.withTechnical(calculated,opportunityDelta,riskDelta);
  return new Report(symbol,horizonDays,forecast.referenceTime(),forecast.referencePrice(),
   enriched.opportunity(),enriched.risk(),enriched.reliability(),enriched.status(),
   evaluated,accuracy,enriched.reasons(),
   "Heuristic scores, not calibrated probabilities; historical performance is not predictive certainty",
   technicalReport,enriched.opportunity()-calculated.opportunity(),enriched.risk()-calculated.risk(),"TECHNICAL_SCORE_V2");
 }
}
