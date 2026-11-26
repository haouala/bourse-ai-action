package lu.bourse.engine.service;

import org.springframework.stereotype.Service;
import java.time.*;

@Service
public class TechnicalAnalysisService {
    private final ClosedMarketHistory history;private final Clock clock;
    public TechnicalAnalysisService(ClosedMarketHistory history,Clock clock){this.history=history;this.clock=clock;}
    public record Report(String symbol,String timeframe,String currency,Instant asOf,Instant referenceTime,
                         String status,boolean usable,TechnicalAnalysis.Result analysis) {}
    public Report analyze(String symbol,String timeframe){return analyze(symbol,timeframe,clock.instant());}
    public Report analyze(String symbol,String timeframe,Instant asOf) {
        var h=history.load(symbol,timeframe,250,asOf);var result=TechnicalAnalysis.analyze(h.bars());
        boolean usable="AT_LAST_CLOSE".equals(h.freshness())&&result.continuousCandles()>=5;
        String status=usable?"EXPERIMENTAL":h.freshness().equals("AT_LAST_CLOSE")?"INSUFFICIENT_CONTINUOUS_HISTORY":h.freshness();
        return new Report(h.symbol(),h.timeframe(),h.currency(),asOf,h.bars().isEmpty()?null:h.bars().getLast().openTime(),status,usable,result);
    }
}
