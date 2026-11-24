package lu.bourse.engine.market;

import lu.bourse.engine.service.HistoryService.PriceBar;
import java.time.*;
import java.math.BigDecimal;
import java.util.*;

/** Aggregate only contiguous, complete, session-aligned quarter-hour bars. */
public final class CandleAggregation {
    private CandleAggregation() {}
    public static List<PriceBar> aggregate(List<PriceBar> input, Instant sessionOpen, Instant sessionClose, Instant now, int minutes) {
        if (minutes != 30 && minutes != 60) throw new IllegalArgumentException("Unsupported aggregation");
        if (!sessionClose.isAfter(sessionOpen)) throw new IllegalArgumentException("Invalid session");
        var byTime = new HashMap<Instant,PriceBar>();
        for (var bar : input) {
            if (byTime.putIfAbsent(bar.openTime(), bar) != null) throw new IllegalArgumentException("Duplicate source candle");
        }
        var result = new ArrayList<PriceBar>();
        for (Instant start=sessionOpen; start.isBefore(sessionClose); start=start.plusSeconds(minutes*60L)) {
            Instant end=start.plusSeconds(minutes*60L);
            if (end.isAfter(sessionClose)) end=sessionClose;
            if (end.isAfter(now) || Duration.between(start,end).toSeconds()%900!=0) continue;
            var bars=new ArrayList<PriceBar>();
            for (Instant at=start; at.isBefore(end); at=at.plusSeconds(900)) {
                var bar=byTime.get(at);
                if (bar==null) break;
                bars.add(bar);
            }
            if (bars.size()!=Duration.between(start,end).toSeconds()/900) continue;
            BigDecimal high=bars.stream().map(PriceBar::high).max(BigDecimal::compareTo).orElseThrow();
            BigDecimal low=bars.stream().map(PriceBar::low).min(BigDecimal::compareTo).orElseThrow();
            BigDecimal volume=bars.stream().map(PriceBar::volume).reduce(BigDecimal.ZERO,BigDecimal::add);
            result.add(new PriceBar(start,bars.getFirst().open(),high,low,bars.getLast().close(),volume));
        }
        return List.copyOf(result);
    }
}
