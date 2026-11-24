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
    /** Register imported US daily equities; other markets require explicit exchange metadata. */
    @Transactional public int registerImportedUs(){
        db.queryForObject("SELECT id FROM watchlist_guard WHERE id=1 FOR UPDATE",Integer.class);
        int count=0;
        for (var row : db.queryForList("""
                SELECT a.*, p.provider_symbol, p.exchange_mic FROM assets a
                JOIN asset_provider_symbols p ON p.asset_id=a.id AND p.provider='TWELVE_DATA'
                WHERE a.asset_type='STOCK' AND a.currency='USD'
                  AND EXISTS (SELECT 1 FROM market_candles c WHERE c.asset_id=a.id
                    AND c.timeframe='1d' AND c.provider='TWELVE_DATA')
                  AND NOT EXISTS (SELECT 1 FROM watchlist w WHERE w.asset_id=a.id)
                ORDER BY a.symbol
                """)) {
            String symbol = (String) row.get("symbol");
            if (!isUsRegistrationCandidate(symbol, (String) row.get("exchange_mic"))) continue;
            int active=db.queryForObject("SELECT COUNT(*) FROM watchlist w JOIN assets a ON a.id=w.asset_id WHERE w.enabled=TRUE AND a.asset_type='STOCK'",Integer.class);
            if(active>=100)break;
            register(new Entry(symbol,(String)row.get("name"),"STOCK","USD",(String)row.get("provider_symbol"),"US","America/New_York","09:30","16:00",true));
            count++;
        }
        return count;
    }
    private static final Set<String> US_EQUITY_MICS = Set.of(
            "XNYS", "XNAS", "XNGS", "XNMS", "XNCM", "ARCX", "XASE", "BATS");

    static boolean isUsRegistrationCandidate(String symbol, String mic) {
        return symbol != null && symbol.matches("[A-Z]{1,5}(?:[.-][A-Z])?")
                && mic != null && US_EQUITY_MICS.contains(mic);
    }
    public List<Map<String,Object>> list(){return db.queryForList("SELECT w.*,a.symbol,a.name,a.asset_type,a.currency FROM watchlist w JOIN assets a ON a.id=w.asset_id ORDER BY a.asset_type,a.symbol");}
    /** Verified sessions gate pulls; closed sessions still need a final closing-bar catch-up. */
    @Transactional public int enqueue(){
        db.queryForObject("SELECT id FROM watchlist_guard WHERE id=1 FOR UPDATE",Integer.class);
        Instant now=clock.instant(); int count=0;
        for (var row : list()) {
            if (!Boolean.TRUE.equals(row.get("enabled"))) continue;
            long asset = ((Number) row.get("asset_id")).longValue();
            for (String code : List.of("15m", "30m", "1h", "1d")) {
                var state = calendars.state(asset, Timeframe.parse(code).duration(), now);
                Instant slot = completedSlot(state);
                if (slot == null) continue;
                count += db.update("""
                        INSERT INTO market_sync_tasks(asset_id,timeframe,slot_time,next_attempt_at)
                        VALUES (?,?,?,?) ON CONFLICT DO NOTHING
                        """, asset, code, Timestamp.from(slot), Timestamp.from(now));
            }
        }
        return count;
    }
    static Instant completedSlot(lu.bourse.engine.calendar.MarketCalendarService.State state) {
        return switch (state.marketState()) {
            case "OPEN", "CLOSED" -> state.expectedClose();
            default -> null;
        };
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
