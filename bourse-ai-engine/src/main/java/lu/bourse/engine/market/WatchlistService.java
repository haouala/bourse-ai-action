package lu.bourse.engine.market;

import lu.bourse.engine.persistence.*;
import lu.bourse.engine.service.Timeframe;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;

@Service
public class WatchlistService {
    private final JdbcTemplate db; private final AssetRepository assets; private final Clock clock;
    private final lu.bourse.engine.calendar.MarketCalendarService calendars;
    public WatchlistService(JdbcTemplate db,AssetRepository assets,Clock clock,lu.bourse.engine.calendar.MarketCalendarService calendars){this.db=db;this.assets=assets;this.clock=clock;this.calendars=calendars;}
    public record Entry(String symbol,String name,String assetType,String currency,String providerSymbol,
                        String region,String marketZone,String sessionOpen,String sessionClose,boolean enabled){}
    @Transactional public long register(Entry r){
        if(r==null || r.symbol()==null || !r.symbol().matches("[A-Za-z0-9._-]{1,40}") || r.name()==null || r.name().isBlank() || r.name().length()>255
                || r.assetType()==null || !Set.of("STOCK","CRYPTO").contains(r.assetType()) || r.currency()==null || !r.currency().matches("[A-Z]{3}")
                || r.providerSymbol()==null || !r.providerSymbol().matches("[A-Za-z0-9._:/-]{1,80}") || r.region()==null || !Set.of("US","EU","GLOBAL").contains(r.region())) bad();
        try{ZoneId.of(r.marketZone());LocalTime.parse(r.sessionOpen());LocalTime.parse(r.sessionClose());}catch(Exception e){bad();}
        if(r.assetType().equals("STOCK") && !LocalTime.parse(r.sessionOpen()).isBefore(LocalTime.parse(r.sessionClose())))bad();
        db.queryForObject("SELECT id FROM watchlist_guard WHERE id=1 FOR UPDATE",Integer.class);
        String symbol=r.symbol().toUpperCase(Locale.ROOT);
        AssetEntity asset=assets.findBySymbol(symbol).orElse(null);
        if(asset!=null && (!asset.getAssetType().equals(r.assetType()) || !asset.getCurrency().equals(r.currency())))throw new ResponseStatusException(HttpStatus.CONFLICT,"Existing asset currency/type differs");
        boolean exists=asset!=null && db.queryForObject("SELECT COUNT(*) FROM watchlist WHERE asset_id=?",Integer.class,asset.getId())>0;
        boolean wasEnabled=exists && Boolean.TRUE.equals(db.queryForObject("SELECT enabled FROM watchlist WHERE asset_id=?",Boolean.class,asset.getId()));
        if(r.enabled() && !wasEnabled){
            int cap=r.assetType().equals("STOCK")?100:30;
            int current=db.queryForObject("SELECT COUNT(*) FROM watchlist w JOIN assets a ON a.id=w.asset_id WHERE w.enabled=TRUE AND a.asset_type=?",Integer.class,r.assetType());
            if(current>=cap)throw new ResponseStatusException(HttpStatus.CONFLICT,"Watchlist capacity reached for "+r.assetType());
        }
        if(asset==null)asset=assets.saveAndFlush(new AssetEntity(symbol,r.name(),r.assetType(),r.currency()));
        if(exists)db.update("UPDATE watchlist SET provider_symbol=?,region=?,market_zone=?,session_open=?,session_close=?,enabled=?,updated_time=CURRENT_TIMESTAMP WHERE asset_id=?",r.providerSymbol(),r.region(),r.marketZone(),r.sessionOpen(),r.sessionClose(),r.enabled(),asset.getId());
        else db.update("INSERT INTO watchlist(asset_id,provider_symbol,region,market_zone,session_open,session_close,enabled) VALUES (?,?,?,?,?,?,?)",asset.getId(),r.providerSymbol(),r.region(),r.marketZone(),r.sessionOpen(),r.sessionClose(),r.enabled());
        calendars.assignDefault(asset.getId(),r.assetType(),r.region(),r.marketZone(),r.sessionOpen(),r.sessionClose());
        return asset.getId();
    }
    public List<Map<String,Object>> list(){return db.queryForList("SELECT w.*,a.symbol,a.name,a.asset_type,a.currency FROM watchlist w JOIN assets a ON a.id=w.asset_id ORDER BY a.asset_type,a.symbol");}
    /** Verified sessions gate pulls; closed sessions still need a final closing-bar catch-up. */
    @Transactional public int enqueue(){
        db.queryForObject("SELECT id FROM watchlist_guard WHERE id=1 FOR UPDATE",Integer.class);
        Instant now=clock.instant(); int count=0;
        for(var row:list())if(Boolean.TRUE.equals(row.get("enabled")))for(String code:List.of("15m","30m","1h","1d")){
            var state=calendars.state(((Number)row.get("asset_id")).longValue(),Timeframe.parse(code).duration(),now);
            if(!state.marketState().equals("OPEN")){
                if(!state.marketState().equals("CLOSED")||state.expectedClose()==null)continue;
                // One deterministic catch-up slot after each session, not a request every weekend quarter-hour.
                long id=((Number)row.get("asset_id")).longValue();
                Instant slot=state.expectedClose();
                if(db.queryForObject("SELECT COUNT(*) FROM market_sync_tasks WHERE asset_id=? AND timeframe=? AND slot_time=?",Integer.class,id,code,Timestamp.from(slot))==0){db.update("INSERT INTO market_sync_tasks(asset_id,timeframe,slot_time,next_attempt_at) VALUES (?,?,?,?)",id,code,Timestamp.from(slot),Timestamp.from(now));count++;}
                continue;
            }
            long seconds=Timeframe.parse(code).duration().toSeconds();
            Instant slot=Instant.ofEpochSecond(Math.floorDiv(now.getEpochSecond(),seconds)*seconds);
            long asset=((Number)row.get("asset_id")).longValue();
            if(db.queryForObject("SELECT COUNT(*) FROM market_sync_tasks WHERE asset_id=? AND timeframe=? AND slot_time=?",Integer.class,asset,code,Timestamp.from(slot))>0)continue;
            db.update("INSERT INTO market_sync_tasks(asset_id,timeframe,slot_time,next_attempt_at) VALUES (?,?,?,?)",asset,code,Timestamp.from(slot),Timestamp.from(now));count++;
        }
        return count;
    }
    public static boolean sessionOpen(Map<String,Object> row,Instant now){
        if(row.get("asset_type").equals("CRYPTO"))return true;
        var local=now.atZone(ZoneId.of((String)row.get("market_zone")));
        if(local.getDayOfWeek()==DayOfWeek.SATURDAY || local.getDayOfWeek()==DayOfWeek.SUNDAY)return false;
        LocalTime time=local.toLocalTime();
        return !time.isBefore(LocalTime.parse((String)row.get("session_open"))) && time.isBefore(LocalTime.parse((String)row.get("session_close")));
    }
    private static void bad(){throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid watchlist entry");}
}
