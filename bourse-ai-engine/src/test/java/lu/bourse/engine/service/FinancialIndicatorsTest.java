package lu.bourse.engine.service;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.util.List;
import java.util.stream.IntStream;
import static org.junit.jupiter.api.Assertions.*;
class FinancialIndicatorsTest {
 @Test void flatPricesYieldSmaAndNeutralRsiAndZeroMacd(){
  List<BigDecimal> closes=IntStream.range(0,50).mapToObj(i->new BigDecimal("100")).toList();
  var result=FinancialIndicators.calculate(closes);
  assertEquals(0,result.sma20().compareTo(new BigDecimal("100")));
  assertEquals(0,result.rsi14().compareTo(new BigDecimal("50")));
  assertEquals(0,result.macd().compareTo(BigDecimal.ZERO));
 }
 @Test void strictlyRisingPricesYieldRsi100(){
  List<BigDecimal> closes=IntStream.range(0,60).mapToObj(i->BigDecimal.valueOf(i+1)).toList();
  assertEquals(0,FinancialIndicators.calculate(closes).rsi14().compareTo(new BigDecimal("100")));
 }
 @Test void failsWithInsufficientHistory(){
  assertThrows(IllegalArgumentException.class,()->FinancialIndicators.calculate(List.of(BigDecimal.ONE)));
 }
}
