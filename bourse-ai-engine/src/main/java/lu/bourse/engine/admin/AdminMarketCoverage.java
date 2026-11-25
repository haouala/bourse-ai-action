package lu.bourse.engine.admin;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

@Component
public class AdminMarketCoverage {
    private static final int LOOKBACK_DAYS = 35;
    private static final int MAX_SESSIONS = 21;
    private final JdbcTemplate jdbc;

    public record Asset(String symbol, String assetType, String provider, String calendar, String state,
                        Integer expected, Integer missing, Integer invalid,
                        LocalDate latestExpected, LocalDate latestAvailable, LocalDate latestMissing) {}
    public record Coverage(int lookbackDays, int maxSessions, int assets, int unknownAssets,
                           long expected, long missing, long invalid, List<Asset> details) {}

    public AdminMarketCoverage(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Coverage read(Instant asOf) {
        var today = asOf.atZone(ZoneOffset.UTC).toLocalDate();
        var assets = jdbc.query("""
                WITH universe AS (
                  SELECT a.id,a.symbol,a.asset_type,a.currency,p.calendar_code,p.delivery_grace_seconds,
                         p.trading_status,q.provider,m.coverage_end
                  FROM watchlist w JOIN assets a ON a.id=w.asset_id
                  LEFT JOIN asset_market_policies p ON p.asset_id=a.id
                  LEFT JOIN market_calendars m ON m.code=p.calendar_code
                  LEFT JOIN daily_series_qualifications q ON q.asset_id=a.id
                      AND q.adjustment='SPLIT_ADJUSTED' AND q.qualified_at<=?
                  WHERE w.enabled=TRUE
                ), recent AS (
                  SELECT u.id,u.provider,u.currency,s.session_date,
                         CAST(CONCAT(CAST(s.session_date AS VARCHAR),'T00:00:00+00:00')
                           AS TIMESTAMP WITH TIME ZONE) AS daily_open,
                         ROW_NUMBER() OVER(PARTITION BY u.id ORDER BY s.session_date DESC) AS rank_no
                  FROM universe u JOIN market_sessions s ON s.calendar_code=u.calendar_code
                  WHERE u.asset_type='STOCK' AND u.provider IS NOT NULL AND u.trading_status='ACTIVE'
                    AND s.session_date BETWEEN ? AND ? AND s.opens_at IS NOT NULL
                    AND s.closes_at + u.delivery_grace_seconds * INTERVAL '1' SECOND<=?
                ), selected AS (
                  SELECT r.id,r.session_date,
                         COALESCE(c.open_price,o.open_price) AS opening,
                         COALESCE(c.high_price,o.high_price) AS high_price,
                         COALESCE(c.low_price,o.low_price) AS low_price,
                         COALESCE(c.close_price,o.close_price) AS closing,
                         COALESCE(c.volume,o.volume) AS volume
                  FROM recent r
                  LEFT JOIN market_candles c ON c.asset_id=r.id AND c.timeframe='1d'
                    AND c.provider=r.provider AND c.open_time=r.daily_open
                  LEFT JOIN provider_price_observations o ON c.id IS NULL AND r.provider='TWELVE_DATA'
                    AND o.asset_id=r.id AND o.provider=r.provider AND o.currency=r.currency
                    AND o.timeframe='1d' AND o.open_time=r.daily_open
                  WHERE r.rank_no<=?
                ), coverage AS (
                  SELECT id,COUNT(*) AS expected,
                    SUM(CASE WHEN closing IS NULL THEN 1 ELSE 0 END) AS missing,
                    SUM(CASE WHEN opening<=0 OR closing<=0 OR low_price<=0
                          OR high_price<opening OR high_price<closing OR low_price>opening
                          OR low_price>closing OR volume<0 THEN 1 ELSE 0 END) AS invalid,
                    MAX(session_date) AS latest_expected,
                    MAX(CASE WHEN closing IS NOT NULL THEN session_date END) AS latest_available,
                    MAX(CASE WHEN closing IS NULL THEN session_date END) AS latest_missing
                  FROM selected GROUP BY id
                )
                SELECT u.symbol,u.asset_type,u.provider,u.calendar_code,u.trading_status,u.coverage_end,c.*
                FROM universe u LEFT JOIN coverage c ON c.id=u.id ORDER BY u.symbol
                """, (row, index) -> {
            String type = row.getString("asset_type");
            String provider = row.getString("provider");
            LocalDate end = row.getObject("coverage_end", LocalDate.class);
            Integer expected = row.getObject("expected", Integer.class);
            Integer missing = row.getObject("missing", Integer.class);
            Integer invalid = row.getObject("invalid", Integer.class);
            String state;
            if (!"STOCK".equals(type)) state = "NOT_APPLICABLE";
            else if ("HALTED".equals(row.getString("trading_status"))) state = "HALTED";
            else if (end == null || end.isBefore(today)) state = "CALENDAR_UNAVAILABLE";
            else if (provider == null) state = "UNQUALIFIED";
            else if (expected == null) state = "NO_SESSIONS";
            else state = missing > 0 || invalid > 0 ? "INCOMPLETE" : "OK";
            return new Asset(row.getString("symbol"), type, provider, row.getString("calendar_code"), state,
                    expected, missing, invalid, row.getObject("latest_expected", LocalDate.class),
                    row.getObject("latest_available", LocalDate.class), row.getObject("latest_missing", LocalDate.class));
        }, Timestamp.from(asOf), java.sql.Date.valueOf(today.minusDays(LOOKBACK_DAYS - 1)),
                java.sql.Date.valueOf(today), Timestamp.from(asOf), MAX_SESSIONS);
        var assessed = assets.stream().filter(a -> "OK".equals(a.state()) || "INCOMPLETE".equals(a.state())).toList();
        int unknown = (int) assets.stream().filter(a -> List.of("CALENDAR_UNAVAILABLE", "UNQUALIFIED", "NO_SESSIONS")
                .contains(a.state())).count();
        return new Coverage(LOOKBACK_DAYS, MAX_SESSIONS, assets.size(), unknown,
                assessed.stream().mapToLong(Asset::expected).sum(),
                assessed.stream().mapToLong(Asset::missing).sum(),
                assessed.stream().mapToLong(Asset::invalid).sum(), List.copyOf(assets));
    }
}
