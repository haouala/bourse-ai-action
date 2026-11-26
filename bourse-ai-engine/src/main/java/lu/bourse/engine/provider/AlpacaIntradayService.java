package lu.bourse.engine.provider;

import com.fasterxml.jackson.databind.JsonNode;
import lu.bourse.engine.persistence.SqlIds;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class AlpacaIntradayService {
    private static final ZoneId NEW_YORK = ZoneId.of("America/New_York");
    private static final Duration BAR = Duration.ofMinutes(15);
    private static final Duration DELIVERY = Duration.ofMinutes(15);
    private static final String SYMBOL = "[A-Z0-9][A-Z0-9.-]{0,39}";
    private final JdbcTemplate db;
    private final TransactionTemplate transaction;
    private final AlpacaClient client;
    private final Clock clock;
    private final boolean enabled;

    record Asset(long id, String symbol, LocalDate cursor, LocalDate firstTradingDate) {
        boolean expects(Instant open) {
            return firstTradingDate == null || !LocalDate.ofInstant(open, NEW_YORK).isBefore(firstTradingDate);
        }
    }
    record Bar(long asset, String symbol, Instant open, Instant close,
               BigDecimal o, BigDecimal h, BigDecimal l, BigDecimal c, BigDecimal v) {}
    record Window(LocalDate from, LocalDate to) {}
    record Counts(int observed, int inserted, int differences, int missing) {}
    public record Status(String state, int historyAssets, int pendingAssets,
                         Map<String, Object> recent, Map<String, Object> historical) {}

    AlpacaIntradayService(JdbcTemplate db, PlatformTransactionManager manager, AlpacaClient client, Clock clock,
                          @Value("${ALPACA_ENABLED:false}") boolean enabled) {
        this.db = db;
        this.transaction = new TransactionTemplate(manager);
        this.client = client;
        this.clock = clock;
        this.enabled = enabled;
    }

    @Scheduled(cron = "${ALPACA_RECENT_CRON:20 0/15 * * * *}", zone = "America/New_York")
    public synchronized void recent() {
        if (!enabled || !client.configured()) return;
        LocalDate today = LocalDate.ofInstant(clock.instant(), NEW_YORK);
        run("RECENT", assets(), new Window(today.minusDays(6), today), false);
    }

    @Scheduled(cron = "${ALPACA_HISTORY_CRON:40 * 0-7,22-23 * * *}",
            zone = "${ALPACA_HISTORY_ZONE:Europe/Luxembourg}")
    public synchronized void history() {
        if (!enabled || !client.configured()) return;
        LocalDate today = LocalDate.ofInstant(clock.instant(), NEW_YORK);
        LocalDate oldest = today.minusYears(7);
        LocalDate last = today.minusDays(7);
        List<Asset> assets = assets();
        LocalDate from = assets.stream().map(a -> cursor(a, oldest)).min(LocalDate::compareTo).orElse(last.plusDays(1));
        if (from.isAfter(last)) return;
        LocalDate end = from.plusDays(6);
        if (end.isAfter(last)) end = last;
        run("HISTORY", assets.stream().filter(a -> cursor(a, oldest).equals(from)).toList(), new Window(from, end), true);
    }

    private LocalDate cursor(Asset asset, LocalDate oldest) {
        LocalDate start = asset.firstTradingDate() != null && asset.firstTradingDate().isAfter(oldest)
                ? asset.firstTradingDate() : oldest;
        return asset.cursor() == null || asset.cursor().isBefore(start) ? start : asset.cursor();
    }

    private List<Asset> assets() {
        return db.query("""
                SELECT a.id, COALESCE(s.provider_symbol,a.symbol) AS symbol,p.next_date,a.first_trading_date
                FROM assets a JOIN watchlist w ON w.asset_id=a.id
                LEFT JOIN asset_provider_symbols s ON s.asset_id=a.id AND s.provider='ALPACA'
                LEFT JOIN alpaca_history_progress p ON p.asset_id=a.id
                WHERE w.enabled=TRUE AND w.region='US' AND a.currency='USD' AND a.asset_type IN ('STOCK','ETF')
                ORDER BY a.id
                """, (row,i) -> new Asset(row.getLong("id"),row.getString("symbol"),row.getObject("next_date", LocalDate.class),
                        row.getObject("first_trading_date", LocalDate.class)));
    }

    private void run(String mode, List<Asset> assets, Window window, boolean advance) {
        if (assets.isEmpty()) return;
        var now = clock.instant();
        var cutoff = now.minus(DELIVERY).minusSeconds(2);
        if (advance && recentFailure(now)) return;
        long id = SqlIds.insert(db, """
            INSERT INTO alpaca_import_runs(mode,started_at,status,from_date,to_date,symbols)
            VALUES (?,?,'RUNNING',?,?,?)
            """, mode, Timestamp.from(now), window.from(),window.to(),assets.size());
        try {
            var slots = expected(client.calendar(window.from(), window.to()), window, cutoff);
            var bars = fetch(assets, slots);
            transaction.executeWithoutResult(status -> {
                var counts = save(assets, slots, bars);
                if (advance) {
                    LocalDate next = window.to().plusDays(1);
                    for (Asset asset : assets) {
                        if (db.update("UPDATE alpaca_history_progress SET next_date=? WHERE asset_id=?",next,asset.id()) == 0) {
                            db.update("INSERT INTO alpaca_history_progress(asset_id,next_date) VALUES (?,?)",asset.id(),next);
                        }
                    }
                }
                db.update("""
                    UPDATE alpaca_import_runs SET finished_at=?, status='SUCCESS', observed=?, inserted=?,
                        differences=?, missing=? WHERE id=?
                    """, Timestamp.from(clock.instant()), counts.observed(), counts.inserted(),
                        counts.differences(),counts.missing(),id);
            });
        } catch (RuntimeException failure) {
            String message = failure.getMessage();
            String code = message != null && message.matches("ALPACA_[A-Z0-9_]{1,40}") ? message : "ALPACA_IMPORT_FAILED";
            db.update("UPDATE alpaca_import_runs SET finished_at=?,status='FAILED',error_code=? WHERE id=?",
                    Timestamp.from(clock.instant()),code,id);
        }
    }

    private boolean recentFailure(Instant now) {
        return Boolean.TRUE.equals(db.queryForObject("""
            SELECT COUNT(*) > 0 FROM alpaca_import_runs
            WHERE status='FAILED' AND started_at>=?
            """,Boolean.class,Timestamp.from(now.minus(Duration.ofMinutes(5)))));
    }

    static NavigableMap<Instant, Instant> expected(JsonNode calendar, Window window, Instant cutoff) {
        if (!calendar.isArray()) throw new IllegalStateException("ALPACA_INVALID_CALENDAR");
        var slots = new TreeMap<Instant, Instant>();
        for (JsonNode session : calendar) {
            LocalDate date = LocalDate.parse(session.path("date").asText());
            if (date.isBefore(window.from()) || date.isAfter(window.to()))
                throw new IllegalStateException("ALPACA_INVALID_CALENDAR");
            Instant open = date.atTime(LocalTime.parse(session.path("open").asText())).atZone(NEW_YORK).toInstant();
            Instant close = date.atTime(LocalTime.parse(session.path("close").asText())).atZone(NEW_YORK).toInstant();
            if (!close.isAfter(open)) throw new IllegalStateException("ALPACA_INVALID_CALENDAR");
            for (Instant t = open; t.isBefore(close); t = t.plus(BAR)) {
                Instant end = t.plus(BAR).isBefore(close) ? t.plus(BAR) : close;
                if (!end.isAfter(cutoff)) slots.put(t,end);
            }
        }
        return slots;
    }

    private List<Bar> fetch(List<Asset> assets, NavigableMap<Instant, Instant> slots) {
        if (slots.isEmpty()) return List.of();
        var result = new ArrayList<Bar>();
        for (int offset=0; offset<assets.size(); offset+=100) {
            Map<String, Asset> group = assets.subList(offset,Math.min(offset+100,assets.size())).stream()
                    .collect(Collectors.toMap(Asset::symbol,a -> a));
            if (group.keySet().stream().anyMatch(symbol -> !symbol.matches(SYMBOL)))
                throw new IllegalStateException("ALPACA_INVALID_SYMBOL");
            var tokens = new HashSet<String>();
            String cursor = null;
            do {
                JsonNode response = client.bars(List.copyOf(group.keySet()), slots.firstKey(), slots.lastKey(), cursor);
                JsonNode series = response.path("bars");
                if (!series.isObject()) throw new IllegalStateException("ALPACA_INVALID_BARS");
                for (var entry : series.properties()) {
                    Asset asset = group.get(entry.getKey());
                    if (asset == null || !entry.getValue().isArray())
                        throw new IllegalStateException("ALPACA_INVALID_BARS");
                    for (JsonNode bar : entry.getValue()) {
                        Instant open = Instant.parse(bar.path("t").asText());
                        Instant close = slots.get(open);
                        if (close != null && asset.expects(open)) result.add(decode(asset,bar,open,close));
                    }
                }
                cursor = response.path("next_page_token").asText(null);
                if (cursor != null && (!tokens.add(cursor) || tokens.size() > 50))
                    throw new IllegalStateException("ALPACA_INVALID_PAGINATION");
            } while (cursor != null);
        }
        return result;
    }

    private static Bar decode(Asset asset, JsonNode bar, Instant open, Instant close) {
        for (String key : List.of("o","h","l","c","v")) {
            if (!bar.path(key).isNumber()) throw new IllegalStateException("ALPACA_INVALID_OHLCV");
        }
        var o=bar.get("o").decimalValue(); var h=bar.get("h").decimalValue(); var l=bar.get("l").decimalValue();
        var c=bar.get("c").decimalValue(); var v=bar.get("v").decimalValue();
        if (l.signum() <= 0 || v.signum() < 0 || l.compareTo(o)>0 || l.compareTo(c)>0
                || h.compareTo(o)<0 || h.compareTo(c)<0)
            throw new IllegalStateException("ALPACA_INVALID_OHLCV");
        return new Bar(asset.id(),asset.symbol(),open,close,o,h,l,c,v);
    }

    private Counts save(List<Asset> assets, NavigableMap<Instant, Instant> slots, List<Bar> bars) {
        if (slots.isEmpty()) return new Counts(0,0,0,0);
        List<Object[]> observations = bars.stream().map(b -> new Object[]{b.asset(),b.symbol(),
                Timestamp.from(b.open()),Timestamp.from(b.close()),b.o(),b.h(),b.l(),b.c(),b.v()}).toList();
        db.batchUpdate("""
            INSERT INTO provider_price_observations(asset_id,provider,provider_symbol,timeframe,open_time,close_time,
                open_price,high_price,low_price,close_price,volume,currency,price_adjustment)
            VALUES (?,'ALPACA',?,'15m',?,?,?,?,?,?,?,'USD','RAW') ON CONFLICT DO NOTHING
            """, observations);
        List<Object[]> canonical = bars.stream().map(b -> new Object[]{b.asset(),Timestamp.from(b.open()),
                Timestamp.from(b.close()),b.o(),b.h(),b.l(),b.c(),b.v()}).toList();
        int[] inserted = db.batchUpdate("""
            INSERT INTO market_candles(asset_id,timeframe,open_time,close_time,open_price,high_price,low_price,close_price,volume,provider)
            VALUES (?,'15m',?,?,?,?,?,?,?,'ALPACA') ON CONFLICT DO NOTHING
            """,canonical);
        int differences=0;
        int missing=0;
        for (Asset asset : assets) {
            var checked = db.query("""
                SELECT c.open_time, CASE WHEN p.id IS NOT NULL AND
                    (c.open_price<>p.open_price OR c.high_price<>p.high_price OR c.low_price<>p.low_price OR c.close_price<>p.close_price)
                    THEN 1 ELSE 0 END AS differs
                FROM market_candles c LEFT JOIN provider_price_observations p ON p.asset_id=c.asset_id
                    AND p.timeframe=c.timeframe AND p.open_time=c.open_time AND p.provider='ALPACA'
                WHERE c.asset_id=? AND c.timeframe='15m' AND c.open_time BETWEEN ? AND ?
                """,(r,i) -> Map.entry(r.getTimestamp(1).toInstant(),r.getInt(2)),asset.id(),
                    Timestamp.from(slots.firstKey()),Timestamp.from(slots.lastKey()));
            int present=0;
            for (var candle : checked) if (slots.containsKey(candle.getKey()) && asset.expects(candle.getKey())) {
                present++;
                differences+=candle.getValue();
            }
            missing += (int) slots.keySet().stream().filter(asset::expects).count() - present;
        }
        return new Counts(bars.size(),Arrays.stream(inserted).map(i -> Math.max(0,i)).sum(),differences,missing);
    }

    public Status status() {
        LocalDate stop = LocalDate.ofInstant(clock.instant(), NEW_YORK).minusDays(7);
        List<Asset> watched = assets();
        int pending = (int) watched.stream().filter(a -> !cursor(a, stop.minusYears(7)).isAfter(stop)).count();
        return new Status(!enabled ? "DISABLED" : !client.configured() ? "NOT_CONFIGURED" : "ENABLED",
                watched.size(), pending, latest("RECENT"), latest("HISTORY"));
    }

    private Map<String,Object> latest(String mode) {
        return db.queryForList("SELECT * FROM alpaca_import_runs WHERE mode=? ORDER BY id DESC LIMIT 1",mode)
                .stream().findFirst().orElse(null);
    }
}
