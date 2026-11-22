package lu.bourse.engine.service;

import java.time.*;
import java.util.*;

/** Read-only model evaluation drift signals; not a trading or retraining decision. */
public final class ModelMonitoringRules {
 private ModelMonitoringRules(){}
 public record Snapshot(Instant runTime,int samples,double accuracy,double baselineAccuracy,
                         double brier,double baselineBrier,String version,int horizonSessions){}
 public record Assessment(String status,Double accuracyDeltaPoints,Double brierDelta,
                          int evaluatedRuns,List<String> reasons){}
 public static Assessment assess(List<Snapshot> newestFirst,Instant now){
  if(newestFirst==null||now==null)throw new IllegalArgumentException("Missing monitoring input");
  if(newestFirst.isEmpty())return new Assessment("NO_TRAINING_RUNS",null,null,0,
    List.of("No completed training runs available"));
  Snapshot last=newestFirst.get(0);
  List<String> notes=new ArrayList<>();
  if(last.samples()<30)notes.add("Latest holdout contains fewer than 30 samples");
  if(last.runTime()==null||last.runTime().isAfter(now)||last.runTime().isBefore(now.minus(Duration.ofDays(45))))
   notes.add("Latest experiment is stale or has invalid timestamp");
  if(last.brier()>=last.baselineBrier())notes.add("Latest Brier loss does not beat constant-probability baseline");
  Snapshot prior=null;
  for(int i=1;i<newestFirst.size();i++){
   Snapshot s=newestFirst.get(i);
   if(s.version().equals(last.version())&&s.horizonSessions()==last.horizonSessions()){
    prior=s;break;
   }
  }
  Double accuracyDelta=null,brierDelta=null;
  if(prior!=null){
   accuracyDelta=(last.accuracy()-prior.accuracy())*100;
   brierDelta=last.brier()-prior.brier();
   if(accuracyDelta<=-10)notes.add("Accuracy deteriorated by >=10 percentage points");
   if(brierDelta>=.05)notes.add("Brier loss deteriorated by >=0.05");
  }else notes.add("No comparable earlier run for model version and horizon");
  String status=last.samples()<30?"INSUFFICIENT_SAMPLE":
    last.runTime()==null||last.runTime().isAfter(now)||last.runTime().isBefore(now.minus(Duration.ofDays(45)))?
    "STALE_MODEL":notes.stream().anyMatch(x->x.contains("deteriorated")||x.contains("does not beat"))?
    "REVIEW_DRIFT":"RESEARCH_ONLY";
  return new Assessment(status,accuracyDelta,brierDelta,newestFirst.size(),List.copyOf(notes));
 }
}
