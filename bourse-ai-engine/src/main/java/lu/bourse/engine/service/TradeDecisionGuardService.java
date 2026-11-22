package lu.bourse.engine.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;

@Service
public class TradeDecisionGuardService {
 private final IntelligentScoringService scoring;
 private final MarketRegimeService regimes;
 public TradeDecisionGuardService(IntelligentScoringService scoring,MarketRegimeService regimes){
  this.scoring=scoring;this.regimes=regimes;
 }
 public record Request(String symbol,String action,int horizonDays,BigDecimal hypotheticalAmount,
  BigDecimal availableCash,BigDecimal totalPortfolioValue,BigDecimal currentExposure){}
 public record Review(String symbol,String requestedAction,String status,
  DecisionGuardRules.Result policy,IntelligentScoringService.Report scores,
  MarketRegimeService.Report regime,String disclaimer){}
 @Transactional(readOnly=true)
 public Review review(Request input){
  if(input==null||input.symbol()==null||!input.symbol().matches("[A-Za-z0-9._-]{1,40}")||
    !Set.of("BUY","SELL","HOLD").contains(input.action())||
    !PriceForecastMath.HORIZONS.contains(input.horizonDays()))
   throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid guard request");
  var scores=scoring.score(input.symbol(),input.horizonDays());
  var regime=regimes.assess(input.symbol());
  boolean clean=scores.opportunityScore()!=null&&scores.riskScore()!=null&&
   "RESEARCH_ONLY".equals(regime.status())&&regime.signal()!=null;
  boolean fresh=scores.referenceTime()!=null &&
   !scores.referenceTime().isBefore(Instant.now().minus(Duration.ofDays(7)));
  DecisionGuardRules.Result result;
  try{
   result=DecisionGuardRules.evaluate(input.action(),input.hypotheticalAmount(),
    scores.opportunityScore()==null?0:scores.opportunityScore(),scores.reliabilityScore(),
    scores.riskScore()==null?100:scores.riskScore(),
    regime.signal()==null?"UNKNOWN":regime.signal().regime(),
    regime.signal()==null?"UNKNOWN":regime.signal().volatilityLevel(),fresh,clean,
    input.availableCash(),input.totalPortfolioValue(),input.currentExposure());
  }catch(IllegalArgumentException e){throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid monetary amounts");}
  return new Review(scores.symbol(),input.action(),result.decision(),result,scores,regime,
   "Research-only policy review, never automated trade authorization or financial advice");
 }
}
