package lu.bourse.engine.service;

import org.junit.jupiter.api.Test;
import java.time.LocalDate;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class ContagionStatisticsTest {
 @Test void leadingLagFindsKnownShiftWithoutUsingFutureSourceReturn(){
  LocalDate start=LocalDate.of(2025,1,1);
  List<ContagionStatistics.ReturnPoint> source=new ArrayList<>(),target=new ArrayList<>();
  Random generator=new Random(7);
  double[] values=new double[100];
  for(int i=0;i<100;i++)values[i]=(generator.nextDouble()-.5)*.06;
  for(int i=0;i<100;i++){
   source.add(new ContagionStatistics.ReturnPoint(start.plusDays(i),values[i]));
   target.add(new ContagionStatistics.ReturnPoint(start.plusDays(i),i==0?0:values[i-1]));
  }
  var lag1=ContagionStatistics.correlate(source,target,1,30);
  assertThat(lag1.status()).isEqualTo("OK");
  assertThat(lag1.observations()).isEqualTo(99);
  assertThat(lag1.coefficient()).isEqualTo(1.0);
  var lag0=ContagionStatistics.correlate(source,target,0,30);
  assertThat(Math.abs(lag0.coefficient())).isLessThan(.5);
  // Replacing target's final observation cannot change a correlation calculated on a historical prefix.
  var past=ContagionStatistics.correlate(source.subList(0,70),target.subList(0,70),1,30);
  target.set(99,new ContagionStatistics.ReturnPoint(start.plusDays(99),-99));
  assertThat(ContagionStatistics.correlate(source.subList(0,70),target.subList(0,70),1,30)).isEqualTo(past);
 }
 @Test void insufficientOverlapOrZeroVarianceNeverProduceFabricatedCoefficient(){
  LocalDate day=LocalDate.of(2025,1,1);
  List<ContagionStatistics.ReturnPoint> x=new ArrayList<>(),flat=new ArrayList<>();
  for(int i=0;i<45;i++){
   x.add(new ContagionStatistics.ReturnPoint(day.plusDays(i),Math.sin(i)*.01));
   flat.add(new ContagionStatistics.ReturnPoint(day.plusDays(i),0));
  }
  assertThat(ContagionStatistics.correlate(x,flat,0,30).status()).isEqualTo("NO_VARIANCE");
  var insufficient=ContagionStatistics.correlate(x.subList(0,20),flat,0,30);
  assertThat(insufficient.status()).isEqualTo("INSUFFICIENT_OVERLAP");
  assertThat(insufficient.coefficient()).isNull();
 }
 @Test void priceValidationAndLogReturnCalculation(){
  var prices=new TreeMap<LocalDate,Double>();
  prices.put(LocalDate.of(2026,1,1),100.0);
  prices.put(LocalDate.of(2026,1,2),110.0);
  var points=ContagionStatistics.dailyReturns(prices);
  assertThat(points).hasSize(1);
  assertThat(points.get(0).dailyReturn()).isCloseTo(Math.log(1.1),within(1e-12));
  prices.put(LocalDate.of(2026,1,3),0.0);
  assertThatThrownBy(()->ContagionStatistics.dailyReturns(prices))
   .isInstanceOf(IllegalArgumentException.class);
 }
}
