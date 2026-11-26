package lu.bourse.engine.service;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class DirectionLearningTest {
 private static List<Double> prices(int size){
  List<Double> result=new ArrayList<>();
  double price=100;
  Random random=new Random(31415);
  for(int i=0;i<size;i++){
   double daily=.001+.012*Math.sin(i*1.71)+random.nextGaussian()*.003;
   price*=Math.exp(daily);
   result.add(price);
  }
  return result;
 }
 @Test void expandingFoldsPurgeFutureLabelsAndHaveValidMetrics(){
  var result=DirectionLearning.evaluate(prices(500),10);
  assertThat(result.folds()).hasSize(3);
  assertThat(result.testSamples()).isGreaterThan(80);
  assertThat(result.accuracy()).isBetween(0.0,1.0);
  assertThat(result.brier()).isBetween(0.0,1.0);
  for(var fold:result.folds()){
   assertThat(fold.trained()).isGreaterThan(80);
   assertThat(fold.tested()).isGreaterThanOrEqualTo(20);
   assertThat(fold.testStartIndex()).isEqualTo(fold.trainingEndIndex());
  }
 }
 @Test void futureOutcomesCannotChangePredictionsOnEarlierFolds(){
  List<Double> base=prices(500),changed=new ArrayList<>(base);
  var original=DirectionLearning.evaluate(base,10);
  // The final tail is out of reach for the first two chronological folds.
  for(int i=450;i<500;i++)changed.set(i,changed.get(i)*(1+Math.sin(i)*.04));
  var changedEval=DirectionLearning.evaluate(changed,10);
  assertThat(changedEval.folds().subList(0,2)).isEqualTo(original.folds().subList(0,2));
 }
 @Test void rejectsMissingDataAndUnsupportedHorizons(){
  assertThatThrownBy(()->DirectionLearning.evaluate(prices(100),10))
   .isInstanceOf(IllegalArgumentException.class);
  assertThatThrownBy(()->DirectionLearning.evaluate(prices(500),25))
   .isInstanceOf(IllegalArgumentException.class);
  var broken=prices(500);broken.set(45,0.0);
  assertThatThrownBy(()->DirectionLearning.evaluate(broken,10))
   .isInstanceOf(IllegalArgumentException.class);
 }
}
