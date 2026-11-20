package lu.bourse.engine.seasonality;

import com.fasterxml.jackson.databind.ObjectMapper;
import lu.bourse.engine.calendar.MarketCalendarService;
import lu.bourse.engine.persistence.SqlIds;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import static org.springframework.http.HttpStatus.*;

@Service
public class SeasonalityService {
    public static final String METHOD="CALENDAR_RETURNS_V1_EXPLORATORY";
    private final JdbcTemplate db;private final Clock clock;private final MarketCalendarService calendars;private final ObjectMapper json;
    public SeasonalityService(JdbcTemplate db,Clock clock,MarketCalendarService calendars,ObjectMapper json){this.db=db;this.clock=clock;this.calendars=calendars;this.json=json;}
    public record Qualification(String provider,String adjustment,String evidenceUrl){}
    public record Analysis(String symbol,Instant asOf,String method,String seriesStatus,String inputHash,boolean usableForScoring,
                           SeasonalityStatistics.Result statistics,List<String> limitations){}
    @Transactional public void qualify(String symbol,Qualification q){
        long asset=calendars.assetId(symbol);
        if(q==null||q.provider()==null||!q.provider().matches("[A-Za-z0-9._-]{1,64}")||(q.adjustment()==null||!Set.of("SPLIT_ADJUSTED","TOTAL_RETURN").contains(q.adjustment())))bad("Provider and explicit adjustment basis required");
        try{var url=java.net.URI.create(q.evidenceUrl());if(!"https".equals(url.getScheme())||url.getHost()==null||q.evidenceUrl().length()>1500)bad("HTTPS evidence required");}catch(Exception e){bad("Invalid evidence URL");}
        db.queryForObject("SELECT id FROM assets WHERE id=? FOR UPDATE",Long.class,asset);
        if(db.queryForObject("SELECT COUNT(*) FROM daily_series_qualifications WHERE asset_id=?",Integer.class,asset)==0)db.update("INSERT INTO daily_series_qualifications(asset_id,provider,adjustment,evidence_url,qualified_at) VALUES (?,?,?,?,?)",asset,q.provider(),q.adjustment(),q.evidenceUrl(),Timestamp.from(clock.instant()));
        else db.update("UPDATE daily_series_qualifications SET provider=?,adjustment=?,evidence_url=?,qualified_at=? WHERE asset_id=?",q.provider(),q.adjustment(),q.evidenceUrl(),Timestamp.from(clock.instant()),asset);
    }
    @Transactional(readOnly=true) public Analysis analyze(String symbol,Instant requested){
        Instant asOf=requested==null?clock.instant():requested;
        if(asOf.isAfter(clock.instant())||asOf.isBefore(Instant.parse("1970-01-01T00:00:00Z")))bad("Invalid asOf");
        long asset=calendars.assetId(symbol);
        var rows=db.queryForList("SELECT open_time,close_price,provider FROM market_candles WHERE asset_id=? AND timeframe='1d' AND open_time<=? ORDER BY open_time",asset,Timestamp.from(asOf.minus(Duration.ofDays(1))));
        if(rows.size()>30000)throw new ResponseStatusException(UNPROCESSABLE_ENTITY,"Daily series exceeds 30,000 bars; select an archive window");
        var daily=new ArrayList<SeasonalityStatistics.Close>();var fingerprints=new StringBuilder();var providers=new HashSet<String>();var dates=new HashSet<LocalDate>();
        for(var r:rows){Instant time=SqlIds.instant(r.get("open_time"));LocalDate date=time.atZone(ZoneOffset.UTC).toLocalDate();
            if(!dates.add(date))throw new ResponseStatusException(UNPROCESSABLE_ENTITY,"Multiple daily candles share a trading-date label");
            java.math.BigDecimal price=(java.math.BigDecimal)r.get("close_price");daily.add(new SeasonalityStatistics.Close(date,price));providers.add((String)r.get("provider"));fingerprints.append(time).append('|').append(price.toPlainString()).append('|').append(r.get("provider")).append('\n');
        }
        String hash=hash(fingerprints.toString());var qualified=db.queryForList("SELECT * FROM daily_series_qualifications WHERE asset_id=? AND qualified_at<=?",asset,Timestamp.from(asOf));
        String status=qualified.isEmpty()?"ADJUSTMENT_UNVERIFIED":providers.size()==1&&providers.contains((String)qualified.getFirst().get("provider"))?(String)qualified.getFirst().get("adjustment"):"MIXED_OR_UNQUALIFIED_PROVIDERS";
        boolean crypto="CRYPTO".equals(db.queryForObject("SELECT asset_type FROM assets WHERE id=?",String.class,asset));
        var stats=SeasonalityStatistics.calculate(daily,asOf.atZone(ZoneOffset.UTC).toLocalDate(),crypto);
        return new Analysis(symbol.toUpperCase(Locale.ROOT),asOf,METHOD,status,hash,false,stats,List.of(
            "Descriptive history, not a calibrated probability or a buy/sell signal; default seasonal score contribution is zero",
            "Only completed months, quarters and years; no hour or weekday seasonality",
            "Periods without a prior close, with too few daily bars or large gaps are rejected; exact historical session reconciliation is not certified",
            "Daily open_time UTC date is the provider's trading-date label; adjusted-series qualification is a manual attestation, not a data transformation",
            "Small annual samples, corporate actions, regime changes, fees and independent out-of-sample tests require review"));
    }
    @Transactional public Map<String,Object> save(String symbol){var a=analyze(symbol,null);String data;try{data=json.writeValueAsString(a);}catch(Exception e){throw new IllegalStateException(e);}long id=SqlIds.insert(db,"INSERT INTO seasonality_runs(asset_id,as_of,computed_at,method,input_hash,result_json) VALUES (?,?,?,?,?,?)",calendars.assetId(symbol),Timestamp.from(a.asOf()),Timestamp.from(clock.instant()),METHOD,a.inputHash(),data);return Map.of("id",id,"analysis",a);}
    public List<Map<String,Object>> history(String symbol){return db.queryForList("SELECT * FROM seasonality_runs WHERE asset_id=? ORDER BY id DESC LIMIT 100",calendars.assetId(symbol));}
    public static String hash(String value){try{return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException(e);}}
    private static void bad(String m){throw new ResponseStatusException(BAD_REQUEST,m);}
}
