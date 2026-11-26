package lu.bourse.engine.service;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class BottleneckRulesTest {
 @Test void noUnsupportedHighConvictionWithSparseSources(){
  var one=BottleneckRules.evaluate(List.of(new BottleneckRules.Signal("SUPPLY_SHORTAGE","POSITIVE","HIGH")));
  assertThat(one.score()).isNull();
  assertThat(one.status()).isEqualTo("INSUFFICIENT_EVIDENCE");
 }
 @Test void riskEvidenceLowersIndex(){
  var positive=List.of(new BottleneckRules.Signal("SUPPLY_SHORTAGE","POSITIVE","HIGH"),
   new BottleneckRules.Signal("ORDER_BACKLOG","POSITIVE","MEDIUM"),
   new BottleneckRules.Signal("CAPACITY_EXPANSION","POSITIVE","LOW"));
  var baseline=BottleneckRules.evaluate(positive);
  var risky=new ArrayList<>(positive);
  risky.add(new BottleneckRules.Signal("VALUATION_RISK","NEGATIVE","HIGH"));
  assertThat(baseline.score()).isNotNull();
  assertThat(riskyScore(risky)).isLessThan(baseline.score());
 }
 private int riskyScore(List<BottleneckRules.Signal> evidence){return BottleneckRules.evaluate(evidence).score();}
}
