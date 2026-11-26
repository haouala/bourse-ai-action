package lu.bourse.engine.service;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class ScoringRulesTest {
 @Test void distinctSignalsAndMissingReliability(){
  var insufficient=ScoringRules.calculate(9,-28,.015,2,8.0,9.0,3,51.0);
  assertThat(insufficient.opportunity()).isBetween(0,100);
  assertThat(insufficient.risk()).isBetween(0,100);
  assertThat(insufficient.reliability()).isNull();
  assertThat(insufficient.status()).isEqualTo("INSUFFICIENT_RELIABILITY_EVIDENCE");
  var supported=ScoringRules.calculate(9,-28,.015,2,8.0,9.0,50,56.0);
  assertThat(supported.reliability()).isBetween(0,100);
  assertThat(supported.status()).isEqualTo("RESEARCH_ONLY");
 }
 @Test void riskIncreasesWithDownsideVolatilityAndWarnings(){
  var low=ScoringRules.calculate(5,-3,.004,0,null,null,null,null);
  var high=ScoringRules.calculate(5,-30,.05,6,null,null,null,null);
  assertThat(high.risk()).isGreaterThan(low.risk());
  assertThat(high.opportunity()).isEqualTo(low.opportunity());
 }
 @Test void scoresAreBoundedAndRejectNonFinite(){
  var extreme=ScoringRules.calculate(900,-900,.8,300,5.,6.,100,100.);
  assertThat(extreme.opportunity()).isBetween(0,100);
  assertThat(extreme.risk()).isEqualTo(100);
  assertThat(extreme.reliability()).isBetween(0,100);
  assertThatThrownBy(()->ScoringRules.calculate(Double.NaN,-4,.01,0,null,null,0,null))
   .isInstanceOf(IllegalArgumentException.class);
 }
 @Test void technicalPointsStayBoundedAndNeverInventReliability(){
  var base=ScoringRules.calculate(30,-90,.05,0,null,null,0,null);
  var enriched=ScoringRules.withTechnical(base,8,8);
  assertThat(enriched.opportunity()).isBetween(0,100);assertThat(enriched.risk()).isEqualTo(100);
  assertThat(enriched.reliability()).isNull();assertThat(enriched.reasons()).anyMatch(s->s.contains("TECHNICAL_SCORE_V2"));
  assertThatThrownBy(()->ScoringRules.withTechnical(base,9,0)).isInstanceOf(IllegalArgumentException.class);
  assertThatThrownBy(()->ScoringRules.withTechnical(base,0,-1)).isInstanceOf(IllegalArgumentException.class);
 }
}
