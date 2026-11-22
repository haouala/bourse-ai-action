package lu.bourse.engine.service;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import static org.assertj.core.api.Assertions.*;

class DecisionGuardRulesTest {
 @Test void refusesUnprovenReliabilityAndLargePositions(){
  var result=DecisionGuardRules.evaluate("BUY",new BigDecimal("300"),80,null,35,
    "BULL","NORMAL",true,true,new BigDecimal("1000"),new BigDecimal("1000"),BigDecimal.ZERO);
  assertThat(result.decision()).isEqualTo("BLOCKED");
  assertThat(result.reasons()).contains("RELIABILITY_NOT_DEMONSTRATED","MAX_POSITION_25_PERCENT");
 }
 @Test void allPositiveSignalsStillRequireHumanReview(){
  var result=DecisionGuardRules.evaluate("BUY",new BigDecimal("100"),80,75,30,
    "BULL","NORMAL",true,true,new BigDecimal("1000"),new BigDecimal("1000"),BigDecimal.ZERO);
  assertThat(result.decision()).isEqualTo("REVIEW_REQUIRED");
 }
 @Test void blocksStaleAndBearAndInsufficientCash(){
  var result=DecisionGuardRules.evaluate("BUY",new BigDecimal("150"),95,75,20,
    "BEAR","NORMAL",false,true,new BigDecimal("100"),new BigDecimal("1000"),BigDecimal.ZERO);
  assertThat(result.reasons()).contains("STALE_PRICE","UNFAVORABLE_OR_UNKNOWN_REGIME","INSUFFICIENT_CASH");
  assertThatThrownBy(()->DecisionGuardRules.evaluate("BUY",BigDecimal.valueOf(-1),90,75,20,"BULL",
   "NORMAL",true,true,BigDecimal.ONE,BigDecimal.TEN,BigDecimal.ZERO)).isInstanceOf(IllegalArgumentException.class);
 }
}
