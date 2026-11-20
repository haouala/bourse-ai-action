package lu.bourse.engine.api;

import lu.bourse.engine.service.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import java.time.*;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.util.*;

@RestController
@RequestMapping("/api/v1/charts")
public class ChartController {
    private final JdbcTemplate db;private final Clock clock;private final lu.bourse.engine.calendar.MarketCalendarService calendars;
    public ChartController(JdbcTemplate db,Clock clock,lu.bourse.engine.calendar.MarketCalendarService calendars){this.db=db;this.clock=clock;this.calendars=calendars;}
    public record Candle(Instant openTime,Instant closeTime,BigDecimal open,BigDecimal high,BigDecimal low,BigDecimal close,BigDecimal volume,String provider){}
    public record Chart(String symbol,String timeframe,String currency,List<Candle> candles,FinancialIndicators.Result latestIndicators,String warning){}
    @GetMapping("/{symbol}")
    public Chart chart(@PathVariable String symbol,@RequestParam(defaultValue="15m")String timeframe,@RequestParam(defaultValue="250")int limit){
        if(symbol==null || !symbol.matches("[A-Za-z0-9._-]{1,40}") || limit<1 || limit>2000)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid chart query");
        Timeframe tf=Timeframe.parse(timeframe);String normalized=symbol.toUpperCase(Locale.ROOT);
        var currencies=db.queryForList("SELECT currency FROM assets WHERE symbol=?",String.class,normalized);
        if(currencies.isEmpty())throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Unknown asset");
        long asset=calendars.assetId(normalized);
        var bars=db.query("SELECT c.* FROM market_candles c JOIN assets a ON a.id=c.asset_id WHERE a.symbol=? AND c.timeframe=? AND c.open_time<=? ORDER BY c.open_time DESC LIMIT ?",
                (r,i)->new Candle(r.getTimestamp("open_time").toInstant(),calendars.barClose(asset,r.getTimestamp("open_time").toInstant(),tf.duration()),r.getBigDecimal("open_price"),r.getBigDecimal("high_price"),r.getBigDecimal("low_price"),r.getBigDecimal("close_price"),r.getBigDecimal("volume"),r.getString("provider")),normalized,tf.code(),Timestamp.from(clock.instant()),limit+1);
        bars=bars.stream().filter(b->!b.closeTime().isAfter(clock.instant())).limit(limit).collect(java.util.stream.Collectors.toCollection(ArrayList::new));
        Collections.reverse(bars);
        var indicators=bars.size()>=35?FinancialIndicators.calculate(bars.stream().map(Candle::close).toList()):null;
        return new Chart(normalized,tf.code(),currencies.getFirst(),bars,indicators,"Raw imported data; latency, corporate actions and execution prices must be verified");
    }
}
