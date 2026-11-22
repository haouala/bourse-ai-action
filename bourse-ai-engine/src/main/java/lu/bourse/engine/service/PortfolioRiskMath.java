package lu.bourse.engine.service;

import java.time.LocalDate;
import java.util.*;

/** Pure portfolio calculations; equal market dates and constant share quantities. */
public final class PortfolioRiskMath {
 private PortfolioRiskMath(){}
 public record Point(LocalDate date,double value){}
 public record Measures(double annualVolatilityPct,double maxDrawdownPct,double historical95OneDayLossPct,
  int commonDates,double largestWeightPct,double concentrationHhi){}
 public static Measures measure(List<List<Point>> series,double[] quantities){
  if(series==null||quantities==null||series.size()==0||series.size()!=quantities.length||
     series.size()>10)throw new IllegalArgumentException("Invalid holdings");
  List<NavigableMap<LocalDate,Double>> mapped=new ArrayList<>();
  Set<LocalDate> common=null;
  for(int s=0;s<series.size();s++){
   if(!Double.isFinite(quantities[s])||quantities[s]<=0)throw new IllegalArgumentException("Invalid share quantity");
   NavigableMap<LocalDate,Double> prices=new TreeMap<>();
   for(Point p:series.get(s)){
    if(p==null||p.date()==null||!Double.isFinite(p.value())||p.value()<=0||
       prices.putIfAbsent(p.date(),p.value())!=null)
     throw new IllegalArgumentException("Invalid or duplicated daily quote");
   }
   if(common==null)common=new HashSet<>(prices.keySet());else common.retainAll(prices.keySet());
   mapped.add(prices);
  }
  List<LocalDate> dates=new ArrayList<>(common);dates.sort(Comparator.naturalOrder());
  if(dates.size()<61)throw new IllegalArgumentException("Need at least 61 common price dates");
  // Use last 61 common dates: 60 daily log returns.
  dates=dates.subList(dates.size()-61,dates.size());
  List<Double> values=new ArrayList<>();
  for(LocalDate d:dates){
   double total=0;
   for(int i=0;i<mapped.size();i++)total+=mapped.get(i).get(d)*quantities[i];
   if(!Double.isFinite(total)||total<=0)throw new IllegalArgumentException("Portfolio value overflow");
   values.add(total);
  }
  double latest=values.get(values.size()-1),largest=0,hhi=0;
  for(int i=0;i<mapped.size();i++){
   double w=mapped.get(i).get(dates.get(dates.size()-1))*quantities[i]/latest;
   largest=Math.max(largest,w);hhi+=w*w;
  }
  List<Double> returns=new ArrayList<>();
  double maxDrawdown=0,peak=0,sum=0,sumSquares=0;
  for(int i=0;i<values.size();i++){
   double v=values.get(i);peak=Math.max(peak,v);
   maxDrawdown=Math.max(maxDrawdown,(peak-v)/peak);
   if(i>0){
    double ret=Math.log(v/values.get(i-1));
    returns.add(ret);sum+=ret;sumSquares+=ret*ret;
   }
  }
  double variance=Math.max(0,sumSquares/60-Math.pow(sum/60,2));
  List<Double> losses=new ArrayList<>();
  for(double r:returns)losses.add(Math.max(0,1-Math.exp(r)));
  losses.sort(Comparator.naturalOrder());
  double loss95=losses.get((int)Math.ceil(.95*losses.size())-1);
  return new Measures(round(Math.sqrt(variance)*Math.sqrt(252)*100),round(maxDrawdown*100),
   round(loss95*100),dates.size(),round(largest*100),round(hhi));
 }
 private static double round(double value){return Math.round(value*10000.0)/10000.0;}
}
