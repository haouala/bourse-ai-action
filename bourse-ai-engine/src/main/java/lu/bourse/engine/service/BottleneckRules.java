package lu.bourse.engine.service;
import java.util.*;

public final class BottleneckRules {
 private BottleneckRules(){}
 public record Signal(String type,String direction,String confidence){}
 public record Result(Integer score,String status,int positive,int negative,List<String> reasons){}
 public static Result evaluate(List<Signal> signals){
  int pos=0,neg=0,raw=50;
  Set<String> distinct=new HashSet<>();
  for(Signal s:signals){
   if(s==null||!Set.of("POSITIVE","NEGATIVE").contains(s.direction())||
       !Set.of("LOW","MEDIUM","HIGH").contains(s.confidence()))
    throw new IllegalArgumentException("Invalid evidence");
   distinct.add(s.type());
   int weight=s.confidence().equals("HIGH")?12:s.confidence().equals("MEDIUM")?7:3;
   if(s.direction().equals("POSITIVE")){pos++;raw+=weight;}else{neg++;raw-=weight;}
  }
  List<String> reasons=new ArrayList<>();
  reasons.add("Analyst-curated sources, not independent real-time supply verification");
  if(signals.size()<3||distinct.size()<2){
   reasons.add("At least three observations across two signal categories required");
   return new Result(null,"INSUFFICIENT_EVIDENCE",pos,neg,List.copyOf(reasons));
  }
  int score=Math.max(0,Math.min(100,raw));
  if(neg>0)reasons.add("Explicit negative or valuation-risk evidence present");
  reasons.add("Uncalibrated evidence index, not projected returns or 85x upside");
  return new Result(score,"RESEARCH_ONLY",pos,neg,List.copyOf(reasons));
 }
}
