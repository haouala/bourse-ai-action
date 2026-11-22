package lu.bourse.engine.service;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class RegimeRulesTest {
 private static List<Double> prices(double dailyGrowth){
  List<Double> p=new ArrayList<>();double value=100;
  for(int i=0;i<100;i++){value*=1+dailyGrowth;p.add(value);}return p;
 }
 @Test void trendClassesAndVolatilityAreFinite(){
  var bull=RegimeRules.classify(prices(.004));
  var bear=RegimeRules.classify(prices(-.004));
  var flat=RegimeRules.classify(prices(0));
  assertThat(bull.regime()).isEqualTo("BULL");
  assertThat(bear.regime()).isEqualTo("BEAR");
  assertThat(flat.regime()).isEqualTo("SIDEWAYS");
  assertThat(bull.annualizedVolatilityPct()).isGreaterThanOrEqualTo(0);
  assertThat(bear.drawdownPct()).isLessThan(0);
  assertThat(flat.annualizedVolatilityPct()).isEqualTo(0);
 }
 @Test void abruptOscillationsRaiseVolatilityFlag(){
  List<Double> prices=new ArrayList<>();
  for(int i=0;i<100;i++)prices.add(i%2==0?100.0:112.0);
  assertThat(RegimeRules.classify(prices).volatilityLevel()).isEqualTo("HIGH");
 }
 @Test void rejectsInvalidAndShortSeries(){
  assertThatThrownBy(()->RegimeRules.classify(prices(0).subList(0,30))).isInstanceOf(IllegalArgumentException.class);
  var broken=prices(0);broken.set(60,Double.NaN);
  assertThatThrownBy(()->RegimeRules.classify(broken)).isInstanceOf(IllegalArgumentException.class);
 }
}
