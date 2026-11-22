package lu.bourse.engine.service;

import java.math.BigDecimal;
import java.util.*;

/** Fail-closed research policy, never an order authorization. */
public final class DecisionGuardRules {
 private DecisionGuardRules(){}
 public record Result(String decision,List<String> reasons,BigDecimal maximumTheoreticalSpend){}
 public static Result evaluate(String action,BigDecimal amount,int opportunity,Integer reliability,
 int risk,String regime,String volatilityLevel,boolean fresh,boolean sufficientData,
 BigDecimal availableCash,BigDecimal portfolioValue,BigDecimal currentAssetExposure){
  List<String> reasons=new ArrayList<>();
  if(!Set.of("BUY","SELL","HOLD").contains(action)||amount==null||amount.signum()<0||
      availableCash==null||availableCash.signum()<0||portfolioValue==null||portfolioValue.signum()<0||
      currentAssetExposure==null||currentAssetExposure.signum()<0)
   throw new IllegalArgumentException("Invalid policy inputs");
  if(!fresh)reasons.add("STALE_PRICE");
  if(!sufficientData)reasons.add("UNTRUSTED_OR_INCOMPLETE_DATA");
  if(reliability==null||reliability<60)reasons.add("RELIABILITY_NOT_DEMONSTRATED");
  if(risk>65)reasons.add("HIGH_RISK_SCORE");
  if(action.equals("BUY")){
   if(opportunity<65)reasons.add("LOW_OPPORTUNITY_SCORE");
   if(!Set.of("BULL","SIDEWAYS").contains(regime))reasons.add("UNFAVORABLE_OR_UNKNOWN_REGIME");
   if("HIGH".equals(volatilityLevel))reasons.add("HIGH_VOLATILITY");
   if(amount.signum()==0||amount.compareTo(availableCash)>0)reasons.add("INSUFFICIENT_CASH");
   if(portfolioValue.signum()==0||currentAssetExposure.add(amount).compareTo(portfolioValue.multiply(new BigDecimal("0.25")))>0)
    reasons.add("MAX_POSITION_25_PERCENT");
  }
  if(action.equals("SELL")||action.equals("HOLD"))reasons.add("POLICY_ONLY_NO_EXECUTION");
  // Even with no reasons, explicitly abstain: experimental score is not approved for live trading.
  return new Result(reasons.isEmpty()?"REVIEW_REQUIRED":"BLOCKED",List.copyOf(reasons),
   availableCash.min(portfolioValue.multiply(new BigDecimal("0.25")).subtract(currentAssetExposure).max(BigDecimal.ZERO)));
 }
}
