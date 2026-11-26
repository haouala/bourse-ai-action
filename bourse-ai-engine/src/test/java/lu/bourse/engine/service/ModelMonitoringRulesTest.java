package lu.bourse.engine.service;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class ModelMonitoringRulesTest {
 private static final Instant NOW=Instant.parse("2026-11-22T12:00:00Z");
 private static ModelMonitoringRules.Snapshot s(int ageDays,int n,double accuracy,double brier){
  return new ModelMonitoringRules.Snapshot(NOW.minus(Duration.ofDays(ageDays)),n,accuracy,.5,
   brier,.24,"v1",10);
 }
 @Test void flagsRealMeasuredDegradationOnlyAgainstComparableRuns(){
  var output=ModelMonitoringRules.assess(List.of(s(1,70,.52,.27),s(25,70,.7,.18)),NOW);
  assertThat(output.status()).isEqualTo("REVIEW_DRIFT");
  assertThat(output.accuracyDeltaPoints()).isLessThan(-10);
  assertThat(output.brierDelta()).isGreaterThan(.05);
 }
 @Test void flagsStalenessInsufficientDataAndUnknownRunHistory(){
  assertThat(ModelMonitoringRules.assess(List.of(),NOW).status()).isEqualTo("NO_TRAINING_RUNS");
  assertThat(ModelMonitoringRules.assess(List.of(s(2,20,.7,.18)),NOW).status()).isEqualTo("INSUFFICIENT_SAMPLE");
  assertThat(ModelMonitoringRules.assess(List.of(s(80,80,.7,.18)),NOW).status()).isEqualTo("STALE_MODEL");
  assertThat(ModelMonitoringRules.assess(List.of(s(2,80,.7,.18)),NOW).status()).isEqualTo("RESEARCH_ONLY");
 }
}
