package lu.bourse.engine.market;

import lu.bourse.engine.service.HistoryService;
import lu.bourse.engine.service.HistoryService.PriceBar;
import lu.bourse.engine.persistence.SqlIds;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;

@Service
public class LocalCandleAggregationService {
    private final JdbcTemplate db;
    private final HistoryService history;
    private final Clock clock;
    public LocalCandleAggregationService(JdbcTemplate db, HistoryService history, Clock clock) {
        this.db=db; this.history=history; this.clock=clock;
    }
    public HistoryService.ImportResult sync(Map<String,Object> asset,String timeframe) {
        int minutes=switch(timeframe){case "30m" -> 30; case "1h" -> 60; default -> throw new IllegalArgumentException("Unsupported aggregation");};
        long id=((Number)asset.get("id")).longValue();
        Instant now=clock.instant();
        var source=db.query("SELECT open_time,open_price,high_price,low_price,close_price,volume FROM market_candles WHERE asset_id=? AND timeframe='15m' AND provider='TWELVE_DATA' ORDER BY open_time DESC LIMIT 5000",
            (r,i)->new PriceBar(r.getTimestamp(1).toInstant(),r.getBigDecimal(2),r.getBigDecimal(3),r.getBigDecimal(4),r.getBigDecimal(5),r.getBigDecimal(6)),id);
        String symbol=(String)asset.get("symbol");
        if(source.isEmpty()) return new HistoryService.ImportResult(symbol,0,0);
        Instant earliest=source.getLast().openTime();
        var sessions=new ArrayList<Instant[]>();
        if("CRYPTO".equals(asset.get("asset_type"))) {
            for(Instant start=earliest.truncatedTo(java.time.temporal.ChronoUnit.DAYS);!start.isAfter(now);start=start.plusSeconds(86400))
                sessions.add(new Instant[]{start,start.plusSeconds(86400)});
        } else {
            for(var row:db.queryForList("SELECT s.opens_at,s.closes_at FROM asset_market_policies p JOIN market_sessions s ON s.calendar_code=p.calendar_code WHERE p.asset_id=? AND s.closes_at>? AND s.opens_at<=? ORDER BY s.opens_at",id,Timestamp.from(earliest),Timestamp.from(now)))
                sessions.add(new Instant[]{SqlIds.instant(row.get("opens_at")),SqlIds.instant(row.get("closes_at"))});
        }
        var derived=new ArrayList<PriceBar>();
        for(var session:sessions) derived.addAll(CandleAggregation.aggregate(source,session[0],session[1],now,minutes));
        if(derived.isEmpty()) return new HistoryService.ImportResult(symbol,0,0);
        return history.importBars(new HistoryService.ImportRequest(symbol,(String)asset.get("name"),(String)asset.get("asset_type"),(String)asset.get("currency"),timeframe,"DERIVED_15M_TWELVE_DATA",derived));
    }
}
