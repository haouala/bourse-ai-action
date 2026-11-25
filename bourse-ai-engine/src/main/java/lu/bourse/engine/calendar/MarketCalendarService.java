package lu.bourse.engine.calendar;

import jakarta.annotation.PostConstruct;
import lu.bourse.engine.persistence.SqlIds;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import static org.springframework.http.HttpStatus.*;

/** Explicit coverage, regular equity sessions, and separately timestamped price/FX freshness. */
@Service
public class MarketCalendarService {
    private final JdbcTemplate db;private final Clock clock;private final org.springframework.transaction.support.TransactionTemplate bootstrap;
    public MarketCalendarService(JdbcTemplate db,Clock clock,org.springframework.transaction.PlatformTransactionManager manager){this.db=db;this.clock=clock;this.bootstrap=new org.springframework.transaction.support.TransactionTemplate(manager);}
    public record Session(LocalDate date,Instant opensAt,Instant closesAt,String reason){}
    public record CalendarImport(String code,String zoneId,String sourceUrl,List<Session> sessions){}
    public record Policy(String calendarCode,int deliveryGraceSeconds,String tradingStatus,String statusReason,int fxMaxAgeSeconds){}
    public record State(String calendarCode,String marketState,Instant expectedClose,Instant nextOpen,String reason){}
    public record Quality(long assetId,String status,boolean usable,boolean estimated,State market,Instant observedAt,Instant fxObservedAt,Instant fxFreshnessThreshold){
        public Quality(long assetId,String status,boolean usable,boolean estimated,State market,Instant observedAt,Instant fxObservedAt){this(assetId,status,usable,estimated,market,observedAt,fxObservedAt,null);}
    }
    private static final String NYSE="https://www.nyse.com/markets/hours-calendars";

    @PostConstruct void seed(){bootstrap.executeWithoutResult(status->{db.queryForObject("SELECT id FROM watchlist_guard WHERE id=1 FOR UPDATE",Integer.class);seedUnlocked();});}
    private void seedUnlocked(){
        if(db.queryForObject("SELECT COUNT(*) FROM market_calendars WHERE code='US_EQUITIES'",Integer.class)>0)return;
        Map<LocalDate,String> closed=new HashMap<>();
        String[] holidays={"2026-01-01","2026-01-19","2026-02-16","2026-04-03","2026-05-25","2026-06-19","2026-07-03","2026-09-07","2026-11-26","2026-12-25",
            "2027-01-01","2027-01-18","2027-02-15","2027-03-26","2027-05-31","2027-06-18","2027-07-05","2027-09-06","2027-11-25","2027-12-24",
            "2028-01-17","2028-02-21","2028-04-14","2028-05-29","2028-06-19","2028-07-04","2028-09-04","2028-11-23","2028-12-25"};
        for(String d:holidays)closed.put(LocalDate.parse(d),"OFFICIAL_HOLIDAY");
        Set<LocalDate> early=new HashSet<>();for(String d:List.of("2026-11-27","2026-12-24","2027-11-26","2028-07-03","2028-11-24"))early.add(LocalDate.parse(d));
        var sessions=new ArrayList<Session>();ZoneId zone=ZoneId.of("America/New_York");
        for(LocalDate d=LocalDate.of(2026,1,1);!d.isAfter(LocalDate.of(2028,12,31));d=d.plusDays(1)){
            boolean weekend=d.getDayOfWeek()==DayOfWeek.SATURDAY||d.getDayOfWeek()==DayOfWeek.SUNDAY;
            String reason=weekend?"WEEKEND":closed.get(d);
            sessions.add(reason!=null?new Session(d,null,null,reason):new Session(d,d.atTime(9,30).atZone(zone).toInstant(),d.atTime(early.contains(d)?13:16,0).atZone(zone).toInstant(),early.contains(d)?"EARLY_CLOSE":"REGULAR"));
        }
        importCalendar(new CalendarImport("US_EQUITIES",zone.getId(),NYSE,sessions));
    }
    @Transactional public void importCalendar(CalendarImport r){
        if(r==null||r.code()==null||!r.code().matches("[A-Z0-9_]{2,40}")||Set.of("CRYPTO_24_7").contains(r.code())||r.sessions()==null||r.sessions().isEmpty()||r.sessions().size()>4000)bad("Invalid calendar import");
        ZoneId zone;try{zone=ZoneId.of(r.zoneId());}catch(Exception e){throw new ResponseStatusException(BAD_REQUEST,"Invalid calendar zone");}url(r.sourceUrl());
        var days=new TreeMap<LocalDate,Session>();
        for(Session s:r.sessions()){
            if(s==null||s.date()==null||s.reason()==null||s.reason().isBlank()||s.reason().length()>120||days.put(s.date(),s)!=null)bad("Invalid or duplicate session date");
            if((s.opensAt()==null)!=(s.closesAt()==null))bad("Both session times are required, or both absent");
            if(s.opensAt()!=null&&(!s.closesAt().isAfter(s.opensAt())||!s.opensAt().atZone(zone).toLocalDate().equals(s.date())||!s.closesAt().atZone(zone).toLocalDate().equals(s.date())))bad("Equity sessions must fit their local date");
        }
        if(Duration.between(days.firstKey().atStartOfDay(),days.lastKey().plusDays(1).atStartOfDay()).toDays()!=days.size())bad("Calendar coverage must contain every date, including closed dates");
        var old=db.queryForList("SELECT * FROM market_calendars WHERE code=? FOR UPDATE",r.code());
        if(old.isEmpty())db.update("INSERT INTO market_calendars(code,zone_id,coverage_start,coverage_end,source_url) VALUES (?,?,?,?,?)",r.code(),zone.getId(),Date.valueOf(days.firstKey()),Date.valueOf(days.lastKey()),r.sourceUrl());
        else{
            // Replacements are complete snapshots. Reject accidental loss of previously covered dates.
            LocalDate start=((Date)old.getFirst().get("coverage_start")).toLocalDate(),end=((Date)old.getFirst().get("coverage_end")).toLocalDate();
            if(days.firstKey().isAfter(start)||days.lastKey().isBefore(end))bad("Replacement must retain existing calendar coverage");
            db.update("DELETE FROM market_sessions WHERE calendar_code=?",r.code());
            db.update("UPDATE market_calendars SET zone_id=?,coverage_start=?,coverage_end=?,source_url=?,updated_time=CURRENT_TIMESTAMP WHERE code=?",zone.getId(),Date.valueOf(days.firstKey()),Date.valueOf(days.lastKey()),r.sourceUrl(),r.code());
        }
        for(Session s:days.values())db.update("INSERT INTO market_sessions(calendar_code,session_date,opens_at,closes_at,reason,source_url) VALUES (?,?,?,?,?,?)",r.code(),Date.valueOf(s.date()),ts(s.opensAt()),ts(s.closesAt()),s.reason(),r.sourceUrl());
    }
    @Transactional public void assign(String symbol,Policy p){
        long asset=assetId(symbol);validate(p);
        db.queryForObject("SELECT id FROM assets WHERE id=? FOR UPDATE",Long.class,asset);
        if(db.queryForObject("SELECT COUNT(*) FROM asset_market_policies WHERE asset_id=?",Integer.class,asset)==0)db.update("INSERT INTO asset_market_policies(asset_id,calendar_code,delivery_grace_seconds,trading_status,status_reason,fx_max_age_seconds) VALUES (?,?,?,?,?,?)",asset,p.calendarCode(),p.deliveryGraceSeconds(),p.tradingStatus(),p.statusReason(),p.fxMaxAgeSeconds());
        else db.update("UPDATE asset_market_policies SET calendar_code=?,delivery_grace_seconds=?,trading_status=?,status_reason=?,fx_max_age_seconds=?,updated_time=CURRENT_TIMESTAMP WHERE asset_id=?",p.calendarCode(),p.deliveryGraceSeconds(),p.tradingStatus(),p.statusReason(),p.fxMaxAgeSeconds(),asset);
    }
    /** Compatibility defaults only for the explicitly configured regular US session or crypto. */
    public void assignDefault(long assetId,String type,String region,String zone,String open,String close){
        if(db.queryForObject("SELECT COUNT(*) FROM asset_market_policies WHERE asset_id=?",Integer.class,assetId)>0)return;
        String code=type.equals("CRYPTO")?"CRYPTO_24_7":region.equals("US")&&zone.equals("America/New_York")&&open.equals("09:30")&&close.equals("16:00")?"US_EQUITIES":"UNASSIGNED";
        db.update("INSERT INTO asset_market_policies(asset_id,calendar_code) VALUES (?,?)",assetId,code);
    }
    public State state(long assetId,Duration timeframe,Instant now){
        var ps=db.queryForList("SELECT * FROM asset_market_policies WHERE asset_id=?",assetId);
        if(ps.isEmpty())return unknown("UNASSIGNED","No market calendar assigned");
        var p=ps.getFirst();String code=(String)p.get("calendar_code");
        if(p.get("trading_status").equals("HALTED"))return new State(code,"HALTED",null,null,(String)p.get("status_reason"));
        int grace=((Number)p.get("delivery_grace_seconds")).intValue();Instant eligible=now.minusSeconds(grace);
        if(code.equals("CRYPTO_24_7"))return new State(code,"OPEN",Instant.ofEpochSecond(Math.floorDiv(eligible.getEpochSecond(),timeframe.toSeconds())*timeframe.toSeconds()),null,"CONTINUOUS_CONFIGURED_MARKET");
        var cs=db.queryForList("SELECT * FROM market_calendars WHERE code=?",code);if(cs.isEmpty())return unknown(code,"Calendar unavailable");
        var c=cs.getFirst();LocalDate today=now.atZone(ZoneId.of((String)c.get("zone_id"))).toLocalDate();
        if(today.isBefore(((Date)c.get("coverage_start")).toLocalDate())||today.isAfter(((Date)c.get("coverage_end")).toLocalDate()))return unknown(code,"Outside verified calendar coverage");
        var sessions=db.queryForList("SELECT * FROM market_sessions WHERE calendar_code=? AND session_date BETWEEN ? AND ? ORDER BY session_date",code,Date.valueOf(today.minusDays(15)),Date.valueOf(today.plusDays(15)));
        boolean found=false,open=false;Instant expected=null,nextOpen=null,currentOpen=null;String reason="";
        for(var s:sessions){
            LocalDate date=((Date)s.get("session_date")).toLocalDate();if(date.equals(today)){found=true;reason=(String)s.get("reason");}
            if(s.get("opens_at")==null)continue;
            Instant start=SqlIds.instant(s.get("opens_at")),end=SqlIds.instant(s.get("closes_at"));
            if(!now.isBefore(start)&&now.isBefore(end)){open=true;currentOpen=start;}
            if(start.isAfter(now)&&(nextOpen==null||start.isBefore(nextOpen)))nextOpen=start;
            Instant candidate=null;
            if(!eligible.isBefore(end))candidate=end;
            else if(!eligible.isBefore(start)){
                long count=Duration.between(start,eligible).toSeconds()/timeframe.toSeconds();
                if(count>0)candidate=start.plusSeconds(count*timeframe.toSeconds());
            }
            if(candidate!=null&&(expected==null||candidate.isAfter(expected)))expected=candidate;
        }
        if(expected==null){Timestamp earlier=db.queryForObject("SELECT MAX(closes_at) FROM market_sessions WHERE calendar_code=? AND closes_at<=?",Timestamp.class,code,ts(eligible));if(earlier!=null)expected=earlier.toInstant();}
        if(nextOpen==null){Timestamp later=db.queryForObject("SELECT MIN(opens_at) FROM market_sessions WHERE calendar_code=? AND opens_at>?",Timestamp.class,code,ts(now));if(later!=null)nextOpen=later.toInstant();}
        if(!found||expected==null)return unknown(code,"Missing current date or previous completed session");
        if(open&&currentOpen!=null&&expected.isBefore(currentOpen))reason="AWAITING_FIRST_BAR";
        return new State(code,open?"OPEN":"CLOSED",expected,nextOpen,reason);
    }
    public Quality quality(long assetId,Instant observed,Instant fxAt,boolean needsFx,Instant now){
        State s=state(assetId,Duration.ofMinutes(15),now);
        if(observed==null)return new Quality(assetId,"DATA_INCOMPLETE",false,false,s,null,fxAt);
        if(s.marketState().equals("UNKNOWN"))return new Quality(assetId,"CALENDAR_UNKNOWN",false,false,s,observed,fxAt);
        if(s.marketState().equals("HALTED"))return new Quality(assetId,"MARKET_HALTED",false,true,s,observed,fxAt);
        if(observed.isAfter(now)||observed.isBefore(s.expectedClose()))return new Quality(assetId,"STALE_DATA",false,false,s,observed,fxAt);
        Instant fxCutoff=null;
        if(needsFx){
            int ttl=db.queryForObject("SELECT fx_max_age_seconds FROM asset_market_policies WHERE asset_id=?",Integer.class,assetId);
            // Separate conventional spot-FX weekend calendar; its source-specific TTL remains explicit.
            fxCutoff=fxAnchor(now).minusSeconds(ttl);
            if(fxAt==null||fxAt.isAfter(now)||fxAt.isBefore(fxCutoff))return new Quality(assetId,"FX_STALE",false,false,s,observed,fxAt,fxCutoff);
        }
        boolean closed=s.marketState().equals("CLOSED"),opening=s.reason().equals("AWAITING_FIRST_BAR");
        return new Quality(assetId,closed?"AT_LAST_CLOSE":opening?"OPENING_REFERENCE":"FRESH",true,closed||opening,s,observed,fxAt,fxCutoff);
    }
    public Instant barClose(long assetId,Instant opened,Duration duration){
        return barCloses(assetId,List.of(opened),duration).getFirst();
    }

    /** Resolve a chart's session boundaries in one query, in input order. */
    public List<Instant> barCloses(long assetId,List<Instant> opens,Duration duration){
        if(opens.isEmpty())return List.of();
        Instant first=Collections.min(opens),last=Collections.max(opens);
        Duration day=Duration.ofDays(1);
        if(duration.equals(day)){
            // Daily provider timestamps are UTC trading-date labels.
            Map<LocalDate,Instant> closes=new HashMap<>();
            for(var row:db.queryForList("""
                SELECT s.session_date,s.closes_at
                FROM asset_market_policies p JOIN market_sessions s ON s.calendar_code=p.calendar_code
                WHERE p.asset_id=? AND s.session_date BETWEEN ? AND ? AND s.closes_at IS NOT NULL
                """,assetId,Date.valueOf(first.atZone(ZoneOffset.UTC).toLocalDate()),
                    Date.valueOf(last.atZone(ZoneOffset.UTC).toLocalDate()))){
                closes.put(((Date)row.get("session_date")).toLocalDate(),SqlIds.instant(row.get("closes_at")));
            }
            return opens.stream().map(open -> closes.getOrDefault(open.atZone(ZoneOffset.UTC).toLocalDate(),open.plus(duration))).toList();
        }
        if(duration.compareTo(day)>0)return opens.stream().map(open -> open.plus(duration)).toList();
        var sessions=new TreeMap<Instant,Instant>();
        for(var row:db.queryForList("""
            SELECT s.opens_at,s.closes_at
            FROM asset_market_policies p JOIN market_sessions s ON s.calendar_code=p.calendar_code
            WHERE p.asset_id=? AND s.opens_at<=? AND s.closes_at>?
            """,assetId,ts(last),ts(first))){
            sessions.put(SqlIds.instant(row.get("opens_at")),SqlIds.instant(row.get("closes_at")));
        }
        return opens.stream().map(open -> {
            Instant normal=open.plus(duration);
            var session=sessions.floorEntry(open);
            return session!=null && session.getValue().isAfter(open) && session.getValue().isBefore(normal)
                    ?session.getValue():normal;
        }).toList();
    }
    public State state(String symbol,String timeframe){return state(assetId(symbol),lu.bourse.engine.service.Timeframe.parse(timeframe).duration(),clock.instant());}
    public List<Map<String,Object>> calendars(){return db.queryForList("SELECT * FROM market_calendars ORDER BY code");}
    public List<Map<String,Object>> sessions(String code,LocalDate from,LocalDate to){if(from==null||to==null||to.isBefore(from)||to.isAfter(from.plusYears(1)))bad("Session window must be at most one year");return db.queryForList("SELECT * FROM market_sessions WHERE calendar_code=? AND session_date BETWEEN ? AND ? ORDER BY session_date",code,Date.valueOf(from),Date.valueOf(to));}
    public long assetId(String symbol){var ids=db.queryForList("SELECT id FROM assets WHERE symbol=?",Long.class,symbol==null?"":symbol.toUpperCase(Locale.ROOT));if(ids.isEmpty())throw new ResponseStatusException(NOT_FOUND,"Unknown asset");return ids.getFirst();}
    private void validate(Policy p){if(p==null||p.calendarCode()==null||!p.calendarCode().matches("[A-Z0-9_]{2,40}")||p.deliveryGraceSeconds()<0||p.deliveryGraceSeconds()>86400||(p.tradingStatus()==null||!Set.of("ACTIVE","HALTED").contains(p.tradingStatus()))||p.fxMaxAgeSeconds()<60||p.fxMaxAgeSeconds()>604800||p.statusReason()!=null&&p.statusReason().length()>300)bad("Invalid market policy");if(!p.calendarCode().equals("CRYPTO_24_7")&&db.queryForObject("SELECT COUNT(*) FROM market_calendars WHERE code=?",Integer.class,p.calendarCode())==0)bad("Import the calendar before assigning it");}
    private static State unknown(String code,String reason){return new State(code,"UNKNOWN",null,null,reason);}
    private static Instant fxAnchor(Instant now){
        var local=now.atZone(ZoneId.of("America/New_York"));var day=local.getDayOfWeek();LocalDate friday=null;
        if(day==DayOfWeek.SATURDAY)friday=local.toLocalDate().minusDays(1);
        else if(day==DayOfWeek.SUNDAY&&local.toLocalTime().isBefore(LocalTime.of(17,0)))friday=local.toLocalDate().minusDays(2);
        else if(day==DayOfWeek.FRIDAY&&!local.toLocalTime().isBefore(LocalTime.of(17,0)))friday=local.toLocalDate();
        return friday==null?now:friday.atTime(17,0).atZone(local.getZone()).toInstant();
    }
    private static Timestamp ts(Instant at){return at==null?null:Timestamp.from(at);}
    private static void url(String value){try{var u=java.net.URI.create(value);if(!"https".equals(u.getScheme())||u.getHost()==null||u.getUserInfo()!=null||value.length()>1500)bad("Calendar evidence requires HTTPS");}catch(Exception e){bad("Invalid calendar source URL");}}
    private static void bad(String message){throw new ResponseStatusException(BAD_REQUEST,message);}
}
