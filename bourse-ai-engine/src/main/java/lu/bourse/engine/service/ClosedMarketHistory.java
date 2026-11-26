package lu.bourse.engine.service;

import lu.bourse.engine.calendar.MarketCalendarService;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;

/** One sequential read shared by charting, scoring and dated analysis. No query inside a row mapper. */
@Service
public class ClosedMarketHistory {
    private final JdbcTemplate db;
    private final MarketCalendarService calendars;
    public ClosedMarketHistory(JdbcTemplate db, MarketCalendarService calendars) { this.db=db; this.calendars=calendars; }
    public record History(String symbol,String timeframe,String currency,List<CandlestickPatterns.Bar> bars,
                          String freshness,Instant expectedClose) {}
    private record Raw(Instant openTime,Instant storedClose,BigDecimal open,BigDecimal high,BigDecimal low,
                       BigDecimal close,BigDecimal volume,String provider) {}

    public History load(String rawSymbol,String timeframe,int limit,Instant asOf) {
        if(rawSymbol==null || !rawSymbol.matches("[A-Za-z0-9._-]{1,40}") || limit<1 || limit>10000 || asOf==null)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid historical query");
        String symbol=rawSymbol.toUpperCase(Locale.ROOT); Timeframe tf=Timeframe.parse(timeframe);
        var units=db.queryForList("SELECT currency FROM assets WHERE symbol=?",String.class,symbol);
        if(units.isEmpty())throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Unknown asset");
        long asset=calendars.assetId(symbol);
        var rows=db.query("""
            SELECT c.* FROM market_candles c JOIN assets a ON a.id=c.asset_id
            WHERE a.symbol=? AND c.timeframe=? AND c.open_time<=? ORDER BY c.open_time DESC LIMIT ?
            """,(r,i)->new Raw(r.getTimestamp("open_time").toInstant(),
                Optional.ofNullable(r.getTimestamp("close_time")).map(Timestamp::toInstant).orElse(null),
                r.getBigDecimal("open_price"),r.getBigDecimal("high_price"),r.getBigDecimal("low_price"),
                r.getBigDecimal("close_price"),r.getBigDecimal("volume"),r.getString("provider")),
            symbol,tf.code(),Timestamp.from(asOf),limit+1);
        var boundaries=calendars.barCloses(asset,rows.stream().map(Raw::openTime).toList(),tf.duration());
        var closed=new ArrayList<CandlestickPatterns.Bar>();
        for(int i=0;i<rows.size()&&closed.size()<limit;i++) {
            var r=rows.get(i); Instant end=r.storedClose()!=null?r.storedClose():boundaries.get(i);
            if(end.isAfter(r.openTime())&&!end.isAfter(asOf))
                closed.add(new CandlestickPatterns.Bar(r.openTime(),end,r.open(),r.high(),r.low(),r.close(),r.volume(),r.provider(),false));
        }
        Collections.reverse(closed);
        var daily=tf==Timeframe.D1?calendars.consecutiveDailyBars(asset,closed.stream().map(CandlestickPatterns.Bar::openTime).toList()):List.<Boolean>of();
        var bars=new ArrayList<CandlestickPatterns.Bar>();
        for(int i=0;i<closed.size();i++) {
            var b=closed.get(i);
            boolean adjacent=i>0&&(tf==Timeframe.D1?daily.get(i):b.openTime().equals(closed.get(i-1).closeTime()));
            bars.add(new CandlestickPatterns.Bar(b.openTime(),b.closeTime(),b.open(),b.high(),b.low(),b.close(),b.volume(),b.provider(),adjacent));
        }
        var state=calendars.state(asset,tf.duration(),asOf);
        String freshness=bars.isEmpty()?"NO_DATA":state.marketState().equals("HALTED")?"HALTED":
                state.expectedClose()==null?"CALENDAR_UNVERIFIED":bars.getLast().closeTime().isBefore(state.expectedClose())?"STALE":"AT_LAST_CLOSE";
        return new History(symbol,tf.code(),units.getFirst(),List.copyOf(bars),freshness,state.expectedClose());
    }
}
