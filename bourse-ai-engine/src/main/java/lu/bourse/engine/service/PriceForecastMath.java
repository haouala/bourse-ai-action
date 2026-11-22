package lu.bourse.engine.service;

import java.time.LocalDate;
import java.util.*;

/**
 * Non-parametric historical scenario baseline. No trained ML model is claimed:
 * Module 7 currently stores evaluation metrics, not deployable model weights.
 * Historical outcomes are selected by first observed close after target calendar date,
 * within 7 days. Training and holdout use strictly disjoint outcome timestamps.
 */
public final class PriceForecastMath {
 private PriceForecastMath(){}
 public static final String VERSION="EMPIRICAL_LOG_RETURN_V1";
 public static final Set<Integer> HORIZONS=Set.of(7,30,90,180);
 public record Close(LocalDate date,double price){}
 public record Validation(int examples,double medianAbsolutePctError,double centralBandCoverage,
                          double naiveMedianAbsolutePctError){}
 public record Estimate(int horizonDays,int trainingExamples,int holdoutExamples,
                        double lowReturn,double medianReturn,double highReturn,Validation validation){}
 private record Outcome(int startIndex,int endIndex,double logReturn){}

 public static Estimate estimate(List<Close> prices,int horizonDays){
  if(prices==null||prices.size()<250||prices.size()>10000||!HORIZONS.contains(horizonDays))
   throw new IllegalArgumentException("Need 250..10000 prices and horizon 7/30/90/180");
  LocalDate previous=null;
  for(Close close:prices){
   if(close==null||close.date()==null||close.price()<=0||!Double.isFinite(close.price())||
      (previous!=null&&!close.date().isAfter(previous)))
    throw new IllegalArgumentException("Invalid or unordered prices");
   previous=close.date();
  }
  // Split by observation date, not by the number of available outcome examples.
  int split=prices.size()*70/100;
  List<Double> training=new ArrayList<>();
  List<Outcome> validation=new ArrayList<>();
  for(int i=0;i<prices.size()-1;i++){
   int target=firstEligible(prices,i,horizonDays);
   if(target<0)continue;
   double ret=Math.log(prices.get(target).price()/prices.get(i).price());
   if(!Double.isFinite(ret))throw new IllegalArgumentException("Invalid historical return");
   // Outcome MUST be known before first holdout signal date.
   if(target<split)training.add(ret);
   else if(i>=split)validation.add(new Outcome(i,target,ret));
  }
  if(training.size()<80||validation.size()<20)
   throw new IllegalArgumentException("Insufficient purged training or untouched holdout outcomes");
  Collections.sort(training);
  double low=quantile(training,.10),median=quantile(training,.50),high=quantile(training,.90);
  double totalAbs=0,naiveAbs=0,covered=0;
  for(Outcome obs:validation){
   double origin=prices.get(obs.startIndex()).price();
   double actual=prices.get(obs.endIndex()).price();
   double predicted=origin*Math.exp(median);
   totalAbs+=Math.abs(predicted/actual-1)*100;
   naiveAbs+=Math.abs(origin/actual-1)*100;
   if(obs.logReturn()>=low&&obs.logReturn()<=high)covered++;
  }
  Validation metrics=new Validation(validation.size(),round(totalAbs/validation.size()),
    round(covered/validation.size()),round(naiveAbs/validation.size()));
  return new Estimate(horizonDays,training.size(),validation.size(),low,median,high,metrics);
 }

 static int firstEligible(List<Close> prices,int index,int horizonDays){
  LocalDate target=prices.get(index).date().plusDays(horizonDays);
  int lo=index+1,hi=prices.size();
  while(lo<hi){
   int mid=(lo+hi)>>>1;
   if(prices.get(mid).date().isBefore(target))lo=mid+1;else hi=mid;
  }
  if(lo>=prices.size()||prices.get(lo).date().isAfter(target.plusDays(7)))return -1;
  return lo;
 }
 static double quantile(List<Double> sorted,double probability){
  double pos=(sorted.size()-1)*probability;
  int lower=(int)Math.floor(pos),upper=(int)Math.ceil(pos);
  return sorted.get(lower)+(sorted.get(upper)-sorted.get(lower))*(pos-lower);
 }
 static double round(double value){return Math.round(value*10000.0)/10000.0;}
}
