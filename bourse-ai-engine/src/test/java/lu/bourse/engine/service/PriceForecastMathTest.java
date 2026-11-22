package lu.bourse.engine.service;

import org.junit.jupiter.api.Test;
import java.time.LocalDate;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class PriceForecastMathTest {
 private static List<PriceForecastMath.Close> series(int count) {
  List<PriceForecastMath.Close> rows=new ArrayList<>();
  double value=100;
  LocalDate start=LocalDate.of(2022,1,1);
  for(int i=0;i<count;i++){
   value*=Math.exp(.00025+.012*Math.sin(i*1.37)+.006*Math.cos(i*.49));
   rows.add(new PriceForecastMath.Close(start.plusDays(i),value));
  }
  return rows;
 }
 @Test void scenariosAreOrderedAndEvaluatedOnlyOnHeldOutFutureObservations() {
  var prices=series(920);
  for(int horizon:List.of(7,30,90,180)){
   var result=PriceForecastMath.estimate(prices,horizon);
   assertThat(result.lowReturn()).isLessThanOrEqualTo(result.medianReturn());
   assertThat(result.medianReturn()).isLessThanOrEqualTo(result.highReturn());
   assertThat(result.trainingExamples()).isGreaterThan(80);
   assertThat(result.holdoutExamples()).isGreaterThanOrEqualTo(20);
   assertThat(result.validation().centralBandCoverage()).isBetween(0.0,1.0);
   assertThat(result.validation().medianAbsolutePctError()).isGreaterThanOrEqualTo(0);
  }
 }
 @Test void holdoutChangesCannotModifyTrainingScenarios() {
  var original=series(920);
  var changed=new ArrayList<>(original);
  for(int i=720;i<changed.size();i++){
   var obs=changed.get(i);
   changed.set(i,new PriceForecastMath.Close(obs.date(),obs.price()*(1+.03*Math.sin(i*2.1))));
  }
  var a=PriceForecastMath.estimate(original,30);
  var b=PriceForecastMath.estimate(changed,30);
  assertThat(b.lowReturn()).isEqualTo(a.lowReturn());
  assertThat(b.medianReturn()).isEqualTo(a.medianReturn());
  assertThat(b.highReturn()).isEqualTo(a.highReturn());
  assertThat(b.trainingExamples()).isEqualTo(a.trainingExamples());
 }
 @Test void horizonUsesNextAvailableDateWithinSevenDays() {
  var dates=List.of(
   new PriceForecastMath.Close(LocalDate.of(2026,1,2),100),
   new PriceForecastMath.Close(LocalDate.of(2026,1,9),101),
   new PriceForecastMath.Close(LocalDate.of(2026,1,12),102),
   new PriceForecastMath.Close(LocalDate.of(2026,1,20),103));
  assertThat(PriceForecastMath.firstEligible(dates,0,7)).isEqualTo(1);
  assertThat(PriceForecastMath.firstEligible(dates,2,7)).isEqualTo(3);
  assertThat(PriceForecastMath.firstEligible(dates,3,7)).isEqualTo(-1);
 }
 @Test void exactIndexQuantileIsNotZeroAndBadInputIsRejected() {
  assertThat(PriceForecastMath.quantile(List.of(1.0,2.0,3.0),.5)).isEqualTo(2.0);
  var values=series(920);
  values.set(55,new PriceForecastMath.Close(values.get(54).date(),100));
  assertThatThrownBy(()->PriceForecastMath.estimate(values,30)).isInstanceOf(IllegalArgumentException.class);
  assertThatThrownBy(()->PriceForecastMath.estimate(series(100),180)).isInstanceOf(IllegalArgumentException.class);
 }
}
