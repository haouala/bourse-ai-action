package lu.bourse.engine.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import java.time.Instant;
import java.util.*;

@Service
public class ModelMonitoringService {
 private final MachineLearningService ml;
 private final PredictionTrackingService tracker;
 public ModelMonitoringService(MachineLearningService ml,PredictionTrackingService tracker){
  this.ml=ml;this.tracker=tracker;
 }
 public record Group(String version,int horizonSessions,ModelMonitoringRules.Assessment assessment,
  List<MachineLearningService.Run> runs){}
 public record Report(String symbol,List<Group> models,List<PredictionTrackingService.Metrics> outcomes,
  String disclaimer){}
 @Transactional(readOnly=true)
 public Report monitor(String raw){
  if(raw==null||!raw.matches("[A-Za-z0-9._-]{1,40}"))
   throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid symbol");
  String ticker=raw.toUpperCase(Locale.ROOT);
  List<MachineLearningService.Run> history=ml.history(ticker,100);
  Map<String,List<MachineLearningService.Run>> groups=new LinkedHashMap<>();
  for(var run:history)groups.computeIfAbsent(run.version()+"|"+run.horizonSessions(),k->new ArrayList<>()).add(run);
  List<Group> outputs=new ArrayList<>();
  for(var entry:groups.entrySet()){
   var runs=entry.getValue();
   List<ModelMonitoringRules.Snapshot> snapshots=new ArrayList<>();
   for(var r:runs)snapshots.add(new ModelMonitoringRules.Snapshot(
    r.createdTime(),r.testSamples(),r.directionAccuracy(),r.baselineAccuracy(),
    r.brierScore(),r.baselineBrier(),r.version(),r.horizonSessions()));
   outputs.add(new Group(runs.get(0).version(),runs.get(0).horizonSessions(),
    ModelMonitoringRules.assess(snapshots,Instant.now()),List.copyOf(runs)));
  }
  return new Report(ticker,List.copyOf(outputs),tracker.metrics(ticker),
   "Research-only drift checks. Different horizons and versions are not directly comparable. No retraining or trading is triggered.");
 }
}
