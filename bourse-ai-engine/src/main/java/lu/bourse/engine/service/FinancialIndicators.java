package lu.bourse.engine.service;
import java.math.*;
import java.util.*;
/** Deterministic computations on chronologically ordered closing prices; no market-data guessing. */
public final class FinancialIndicators {
 private FinancialIndicators() {}
 public record Result(BigDecimal sma20,BigDecimal rsi14,BigDecimal macd,BigDecimal macdSignal,BigDecimal macdHistogram) {}
 public static Result calculate(List<BigDecimal> closes) {
  if(closes.size()<35) throw new IllegalArgumentException("At least 35 completed candles are required");
  for(BigDecimal value:closes) if(value==null || value.signum()<=0) throw new IllegalArgumentException("Invalid close price");
  BigDecimal sma=closes.subList(closes.size()-20,closes.size()).stream().reduce(BigDecimal.ZERO,BigDecimal::add).divide(BigDecimal.valueOf(20),10,RoundingMode.HALF_UP);
  BigDecimal rsi=rsiWilder(closes,14);
  List<BigDecimal> ema12=ema(closes,12), ema26=ema(closes,26);
  List<BigDecimal> macds=new ArrayList<>();
  // EMA(26) starts at close index 25; EMA(12) is already available there.
  for(int i=25;i<closes.size();i++) macds.add(ema12.get(i-11).subtract(ema26.get(i-25)));
  List<BigDecimal> signals=ema(macds,9);
  BigDecimal m=macds.get(macds.size()-1),signal=signals.get(signals.size()-1);
  return new Result(sma,rsi,m,signal,m.subtract(signal));
 }
 public static BigDecimal rsiWilder(List<BigDecimal> closes,int period) {
  if(closes.size()<period+1) throw new IllegalArgumentException("Insufficient RSI history");
  BigDecimal gains=BigDecimal.ZERO,losses=BigDecimal.ZERO;
  for(int i=1;i<=period;i++) {BigDecimal d=closes.get(i).subtract(closes.get(i-1));if(d.signum()>0) gains=gains.add(d);else losses=losses.subtract(d);}
  BigDecimal n=BigDecimal.valueOf(period);
  gains=gains.divide(n,12,RoundingMode.HALF_UP);losses=losses.divide(n,12,RoundingMode.HALF_UP);
  for(int i=period+1;i<closes.size();i++){
   BigDecimal d=closes.get(i).subtract(closes.get(i-1));
   gains=gains.multiply(n.subtract(BigDecimal.ONE)).add(d.max(BigDecimal.ZERO)).divide(n,12,RoundingMode.HALF_UP);
   losses=losses.multiply(n.subtract(BigDecimal.ONE)).add(d.negate().max(BigDecimal.ZERO)).divide(n,12,RoundingMode.HALF_UP);
  }
  if(losses.signum()==0) return gains.signum()==0?BigDecimal.valueOf(50):BigDecimal.valueOf(100);
  if(gains.signum()==0) return BigDecimal.ZERO;
  BigDecimal rs=gains.divide(losses,12,RoundingMode.HALF_UP);
  return BigDecimal.valueOf(100).subtract(BigDecimal.valueOf(100).divide(BigDecimal.ONE.add(rs),8,RoundingMode.HALF_UP));
 }
 private static List<BigDecimal> ema(List<BigDecimal> closes,int period){
  if(closes.size()<period) throw new IllegalArgumentException("Insufficient EMA history");
  BigDecimal seed=closes.subList(0,period).stream().reduce(BigDecimal.ZERO,BigDecimal::add).divide(BigDecimal.valueOf(period),12,RoundingMode.HALF_UP);
  BigDecimal alpha=BigDecimal.valueOf(2).divide(BigDecimal.valueOf(period+1),15,RoundingMode.HALF_UP);
  List<BigDecimal> result=new ArrayList<>();result.add(seed);
  for(int i=period;i<closes.size();i++) result.add(closes.get(i).subtract(result.get(result.size()-1)).multiply(alpha).add(result.get(result.size()-1)));
  return result;
 }
}
