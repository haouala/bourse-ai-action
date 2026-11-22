package lu.bourse.engine.service;
import org.junit.jupiter.api.Test;
import java.time.LocalDate;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class PortfolioRiskMathTest {
 private static List<PortfolioRiskMath.Point> prices(int n,double daily){
  List<PortfolioRiskMath.Point> result=new ArrayList<>();
  double p=100;LocalDate start=LocalDate.of(2026,1,1);
  for(int i=0;i<n;i++){p*=1+daily+Math.sin(i*.7)*.002;
   result.add(new PortfolioRiskMath.Point(start.plusDays(i),p));}
  return result;
 }
 @Test void concentrationAndRiskReflectPositions(){
  var a=prices(100,.001);var b=prices(100,-.001);
  var concentrated=PortfolioRiskMath.measure(List.of(a),new double[]{1});
  var balanced=PortfolioRiskMath.measure(List.of(a,b),new double[]{1,1});
  assertThat(concentrated.largestWeightPct()).isEqualTo(100);
  assertThat(concentrated.concentrationHhi()).isEqualTo(1);
  assertThat(balanced.concentrationHhi()).isLessThan(1);
  assertThat(balanced.commonDates()).isEqualTo(61);
  assertThat(balanced.annualVolatilityPct()).isGreaterThanOrEqualTo(0);
  assertThat(balanced.historical95OneDayLossPct()).isGreaterThanOrEqualTo(0);
 }
 @Test void rejectsInvalidQuantityAndInsufficientOverlap(){
  var prices=prices(100,.001);
  assertThatThrownBy(()->PortfolioRiskMath.measure(List.of(prices),new double[]{0}))
   .isInstanceOf(IllegalArgumentException.class);
  assertThatThrownBy(()->PortfolioRiskMath.measure(List.of(prices.subList(0,30)),new double[]{1}))
   .isInstanceOf(IllegalArgumentException.class);
  var shifted=new ArrayList<PortfolioRiskMath.Point>();
  for(var p:prices)shifted.add(new PortfolioRiskMath.Point(p.date().plusYears(2),p.value()));
  assertThatThrownBy(()->PortfolioRiskMath.measure(List.of(prices,shifted),new double[]{1,1}))
   .isInstanceOf(IllegalArgumentException.class);
 }
}
