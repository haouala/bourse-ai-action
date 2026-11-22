package lu.bourse.engine.service;

import java.time.LocalDate;
import java.util.*;

/**
 * Pure statistics, no side effects. Source leads target by lag common observations:
 * at lag 1, compare source[t-1] with target[t]. This is association, NOT causation.
 */
public final class ContagionStatistics {
 private ContagionStatistics(){}
 public record ReturnPoint(LocalDate date,double dailyReturn){}
 public record Correlation(int lagSessions,int observations,Double coefficient,String status){}

 public static List<ReturnPoint> dailyReturns(NavigableMap<LocalDate,Double> prices){
  List<ReturnPoint> points=new ArrayList<>();
  Double previous=null;
  for(var entry:prices.entrySet()){
   double close=entry.getValue();
   if(!Double.isFinite(close)||close<=0)throw new IllegalArgumentException("Closing prices must be positive and finite");
   if(previous!=null)points.add(new ReturnPoint(entry.getKey(),Math.log(close/previous)));
   previous=close;
  }
  return List.copyOf(points);
 }

 public static Correlation correlate(List<ReturnPoint> source,List<ReturnPoint> target,int lag,int minPairs){
  if(lag<0||lag>5||minPairs<10)throw new IllegalArgumentException("Invalid lag or sample threshold");
  Map<LocalDate,Double> s=new HashMap<>(),t=new HashMap<>();
  for(ReturnPoint p:source)s.put(p.date(),p.dailyReturn());
  for(ReturnPoint p:target)t.put(p.date(),p.dailyReturn());
  List<LocalDate> common=new ArrayList<>(s.keySet());common.retainAll(t.keySet());
  common.sort(Comparator.naturalOrder());
  int n=common.size()-lag;
  if(n<minPairs)return new Correlation(lag,Math.max(0,n),null,"INSUFFICIENT_OVERLAP");
  double sx=0,sy=0;
  for(int i=lag;i<common.size();i++){
   sx+=s.get(common.get(i-lag));sy+=t.get(common.get(i));
  }
  double mx=sx/n,my=sy/n,xx=0,yy=0,xy=0;
  for(int i=lag;i<common.size();i++){
   double x=s.get(common.get(i-lag))-mx,y=t.get(common.get(i))-my;
   xx+=x*x;yy+=y*y;xy+=x*y;
  }
  if(xx<1e-20||yy<1e-20)return new Correlation(lag,n,null,"NO_VARIANCE");
  double correlation=Math.max(-1,Math.min(1,xy/Math.sqrt(xx*yy)));
  if(!Double.isFinite(correlation))return new Correlation(lag,n,null,"NUMERIC_ERROR");
  return new Correlation(lag,n,Math.round(correlation*10000.0)/10000.0,"OK");
 }
}
