package lu.bourse.engine.market;

import org.junit.jupiter.api.Test;
import lu.bourse.engine.service.HistoryService.PriceBar;
import java.time.Instant;
import java.math.BigDecimal;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

class CandleAggregationTest {
    private final Instant start=Instant.parse("2026-11-19T14:30:00Z");
    private PriceBar bar(int offset,int open,int high,int low,int close,int volume){
        return new PriceBar(start.plusSeconds(offset*60L),BigDecimal.valueOf(open),BigDecimal.valueOf(high),BigDecimal.valueOf(low),BigDecimal.valueOf(close),BigDecimal.valueOf(volume));
    }
    @Test void combinesOhlcvAndAlignsToSession(){
        var bars=List.of(bar(0,10,12,9,11,4),bar(15,11,14,10,13,6));
        var result=CandleAggregation.aggregate(bars,start,start.plusSeconds(1800),start.plusSeconds(1800),30);
        assertThat(result).containsExactly(new PriceBar(start,BigDecimal.TEN,BigDecimal.valueOf(14),BigDecimal.valueOf(9),BigDecimal.valueOf(13),BigDecimal.TEN));
    }
    @Test void refusesMissingOrStillOpenIntervals(){
        var bars=List.of(bar(0,10,12,9,11,4),bar(15,11,14,10,13,6));
        assertThat(CandleAggregation.aggregate(bars.subList(0,1),start,start.plusSeconds(1800),start.plusSeconds(1800),30)).isEmpty();
        assertThat(CandleAggregation.aggregate(bars,start,start.plusSeconds(1800),start.plusSeconds(1799),30)).isEmpty();
    }
    @Test void allowsVerifiedShortClosingHour(){
        var bars=List.of(bar(0,10,12,9,11,4),bar(15,11,14,10,13,6));
        assertThat(CandleAggregation.aggregate(bars,start,start.plusSeconds(1800),start.plusSeconds(1800),60)).hasSize(1);
    }
}
