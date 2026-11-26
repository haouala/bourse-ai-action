package lu.bourse.engine.service;

import java.util.*;

/** Transparent retrospective market-condition heuristic, not a trained regime-switching model. */
public final class RegimeRules {
 private RegimeRules(){}
 public record Signal(String regime,String volatilityLevel,double trailingReturnPct,
                      double annualizedVolatilityPct,double drawdownPct,int observations,
                      String method,List<String> caveats){}
 public static Signal classify(List<Double> orderedCloses){
  if(orderedCloses==null||orderedCloses.size()<65||orderedCloses.size()>1000)
   throw new IllegalArgumentException("Require 65..1000 ordered closes");
  for(Double p:orderedCloses)if(p==null||!Double.isFinite(p)||p<=0)
   throw new IllegalArgumentException("Invalid prices");
  int n=orderedCloses.size();
  double start=orderedCloses.get(n-61),last=orderedCloses.get(n-1);
  double pct=(last/start-1)*100;
  double sum=0,sumSquares=0,peak=0,worstDrawdown=0;
  for(int i=n-61;i<n;i++){
   double p=orderedCloses.get(i);
   peak=Math.max(peak,p);
   worstDrawdown=Math.min(worstDrawdown,(p/peak-1)*100);
   if(i>n-61){double r=Math.log(p/orderedCloses.get(i-1));sum+=r;sumSquares+=r*r;}
  }
  double mean=sum/60;
  double vol=Math.sqrt(Math.max(0,sumSquares/60-mean*mean))*Math.sqrt(252)*100;
  String volLevel=vol>=40?"HIGH":vol>=20?"ELEVATED":"NORMAL";
  String regime=pct>=8&&worstDrawdown>-15?"BULL":pct<=-8||worstDrawdown<=-20?"BEAR":"SIDEWAYS";
  return new Signal(regime,volLevel,round(pct),round(vol),round(worstDrawdown),60,
   "60-SESSION_TOTAL_RETURN_VOLATILITY_DRAWDOWN_V1",
   List.of("Heuristic classification of a past window, not a future regime probability",
    "Annualization assumes 252 trading sessions; corporate action adjustment and exchange calendar must be verified"));
 }
 private static double round(double n){return Math.round(n*10000.0)/10000.0;}
}
