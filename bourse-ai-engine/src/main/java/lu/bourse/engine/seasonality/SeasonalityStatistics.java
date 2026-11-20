package lu.bourse.engine.seasonality;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;

/** Calendar-month/quarter/year descriptive returns only. No intraday seasonality or forecasts. */
public final class SeasonalityStatistics {
    private SeasonalityStatistics(){}
    public record Close(LocalDate date,BigDecimal price){}
    public record Observation(String scale,int bucket,int year,LocalDate from,LocalDate to,double returnPercent,int dailyBars){}
    public record Bucket(String scale,int bucket,int observations,Double meanPercent,Double medianPercent,
                         Double winFrequencyPercent,Double worstPercent,Double bestPercent,Double standardDeviation,
                         String reliability,List<Observation> history){}
    public record Result(List<Bucket> monthly,List<Bucket> quarterly,List<Bucket> annual,int rejectedPeriods){}
    public static Result calculate(List<Close> input,LocalDate asOf,boolean crypto){
        var bars=input.stream().filter(b->!b.date().isAfter(asOf)).sorted(Comparator.comparing(Close::date)).toList();
        var observations=new ArrayList<Observation>();int rejected=0;
        for(String scale:List.of("MONTH","QUARTER","YEAR")){
            var groups=new TreeMap<LocalDate,List<Close>>();
            for(Close b:bars){LocalDate first=switch(scale){case "MONTH"->b.date().withDayOfMonth(1);case "QUARTER"->LocalDate.of(b.date().getYear(),((b.date().getMonthValue()-1)/3)*3+1,1);default->LocalDate.of(b.date().getYear(),1,1);};groups.computeIfAbsent(first,x->new ArrayList<>()).add(b);}
            for(var entry:groups.entrySet()){
                LocalDate first=entry.getKey(),end=first.plusMonths(scale.equals("MONTH")?1:scale.equals("QUARTER")?3:12).minusDays(1);
                if(!end.isBefore(asOf))continue; // Never include an unfinished calendar period.
                var period=entry.getValue();Close prior=null;
                for(Close b:bars){if(b.date().isBefore(first))prior=b;else break;}
                int months=scale.equals("MONTH")?1:scale.equals("QUARTER")?3:12;
                int minimum=(crypto?25:15)*months;
                boolean gaps=prior==null||period.size()<minimum||period.getFirst().date().isAfter(first.plusDays(7))||period.getLast().date().isBefore(end.minusDays(7));
                LocalDate last=prior==null?first:prior.date();
                for(Close b:period){if(b.date().isAfter(last.plusDays(crypto?2:7)))gaps=true;last=b.date();}
                if(gaps){rejected++;continue;}
                double result=period.getLast().price().divide(prior.price(),16,java.math.RoundingMode.HALF_UP).subtract(BigDecimal.ONE).doubleValue()*100;
                int bucket=scale.equals("MONTH")?first.getMonthValue():scale.equals("QUARTER")?(first.getMonthValue()-1)/3+1:1;
                observations.add(new Observation(scale,bucket,first.getYear(),prior.date(),period.getLast().date(),result,period.size()));
            }
        }
        return new Result(buckets(observations,"MONTH",12),buckets(observations,"QUARTER",4),buckets(observations,"YEAR",1),rejected);
    }
    private static List<Bucket> buckets(List<Observation> observations,String scale,int count){
        var result=new ArrayList<Bucket>();
        for(int bucket=1;bucket<=count;bucket++){
            int target=bucket;var history=observations.stream().filter(x->x.scale().equals(scale)&&x.bucket()==target).toList();
            double[] returns=history.stream().mapToDouble(Observation::returnPercent).sorted().toArray();int n=returns.length;
            double mean=Arrays.stream(returns).average().orElse(0),median=n==0?0:n%2==1?returns[n/2]:(returns[n/2-1]+returns[n/2])/2;
            double variance=n<2?0:Arrays.stream(returns).map(x->(x-mean)*(x-mean)).sum()/(n-1);
            result.add(new Bucket(scale,bucket,n,n==0?null:mean,n==0?null:median,n==0?null:Arrays.stream(returns).filter(x->x>0).count()*100.0/n,n==0?null:returns[0],n==0?null:returns[n-1],n<2?null:Math.sqrt(variance),n<5?"INSUFFICIENT_SAMPLE":n<10?"LIMITED_SAMPLE":"HISTORICAL_ONLY",history));
        }
        return List.copyOf(result);
    }
}
