package lu.bourse.engine.service;

import java.util.*;

/** Transparent, deterministic diagnostics, not calibrated trading probabilities. */
public final class ScoringRules {
 private ScoringRules(){}
 public record Scores(int opportunity,int risk,Integer reliability,String status,List<String> reasons){}
 /**
  * Produit trois axes indépendants sur [0,100].
  * <p>Opportunité : scénario médian historique par rapport au dernier prix (heuristique bornée).
  * Risque : baisse du scénario défavorable + volatilité journalière réalisée + avertissements de qualité.
  * Fiabilité : absente tant que 30 prévisions réellement échues et évaluées ne sont pas disponibles.
  * <p>Ne convertissez jamais ces scores en probabilités de gain :
  * les coefficients sont pédagogiques et non calibrés hors échantillon.
  */
 public static Scores calculate(double projectedReturnPct,double downsidePct,double realizedDailyVolatility,
                                 int qualityWarnings,Double holdoutErrorPct,Double naiveErrorPct,
                                 Integer evaluatedPredictions,Double directionAccuracyPct){
  if(!Double.isFinite(projectedReturnPct)||!Double.isFinite(downsidePct)||
     !Double.isFinite(realizedDailyVolatility)||realizedDailyVolatility<0||qualityWarnings<0)
   throw new IllegalArgumentException("Invalid scoring inputs");
  List<String> notes=new ArrayList<>();
  int opportunity=clamp(50+(int)Math.round(Math.max(-30,Math.min(30,projectedReturnPct))*1.5));
  int risk=clamp((int)Math.round(15+Math.min(55,Math.max(0,-downsidePct)*1.25)
    +Math.min(30,realizedDailyVolatility*1000)+Math.min(20,qualityWarnings*4)));
  if(qualityWarnings>0)notes.add("Data quality warnings: "+qualityWarnings);
  notes.add("Opportunity derives only from historical central scenario, not a measured win probability");
  notes.add("Risk score rises with lower scenario downside, volatility and quality warnings");
  Integer reliability=null;
  boolean enough=evaluatedPredictions!=null&&evaluatedPredictions>=30&&directionAccuracyPct!=null&&
   holdoutErrorPct!=null&&naiveErrorPct!=null&&Double.isFinite(directionAccuracyPct)&&
   Double.isFinite(holdoutErrorPct)&&Double.isFinite(naiveErrorPct);
  if(enough){
   double advantage=naiveErrorPct-holdoutErrorPct;
   reliability=clamp((int)Math.round(25+Math.min(30,evaluatedPredictions/3.0)
      +Math.max(-20,Math.min(20,advantage*2))
      +Math.max(-15,Math.min(15,(directionAccuracyPct-50)*.3))
      -Math.min(20,qualityWarnings*4)));
   notes.add("Reliability is an uncalibrated evidence index based on >=30 settled predictions; not probability");
  }else notes.add("Reliability unavailable: need >=30 settled predictions and holdout metrics");
  return new Scores(opportunity,risk,reliability,enough?"RESEARCH_ONLY":"INSUFFICIENT_RELIABILITY_EVIDENCE",List.copyOf(notes));
 }
 static int clamp(int x){return Math.max(0,Math.min(100,x));}
 /** Version 2 adds bounded experimental technical points; evidence reliability is deliberately unchanged. */
 public static Scores withTechnical(Scores base,int opportunityDelta,int riskDelta){
  if(opportunityDelta< -8||opportunityDelta>8||riskDelta<0||riskDelta>8)throw new IllegalArgumentException("Technical contribution exceeds cap");
  var reasons=new ArrayList<>(base.reasons());
  reasons.add("TECHNICAL_SCORE_V2 : contribution technique expérimentale, opportunité "+opportunityDelta+", risque +"+riskDelta+" ; plafonds appliqués. Aucun gain de fiabilité supposé.");
  return new Scores(clamp(base.opportunity()+opportunityDelta),clamp(base.risk()+riskDelta),base.reliability(),base.status(),List.copyOf(reasons));
 }
}
