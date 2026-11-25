package lu.bourse.engine.orchestration;

import lu.bourse.engine.provider.TwelveDataImportService;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

@Service
public class DailyCandleRepairOrchestrator {
    private static final int ML_CLOSE_WINDOW = 21;
    private final JdbcTemplate jdbc;
    private final TwelveDataImportService provider;
    private final Clock clock;

    public record Result(String symbol, LocalDate date, String status) {}
    public record Summary(Instant asOf, int missing, List<Result> repairs) {}
    private record Gap(String symbol, LocalDate date) {}

    public DailyCandleRepairOrchestrator(JdbcTemplate jdbc, TwelveDataImportService provider, Clock clock) {
        this.jdbc = jdbc;
        this.provider = provider;
        this.clock = clock;
    }

    public Summary repair(List<String> rawSymbols, LocalDate start, Instant requestedCutoff, int maxRepairs) {
        if (maxRepairs < 1 || maxRepairs > 20 || rawSymbols == null || rawSymbols.size() > 100
                || rawSymbols.stream().anyMatch(s -> s == null || !s.matches("[A-Za-z0-9._-]{1,40}"))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid symbol list or repair limit");
        }
        Instant now = clock.instant();
        Instant cutoff = requestedCutoff == null ? now : requestedCutoff;
        if (cutoff.isAfter(now)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Repair cutoff cannot be in the future");
        }
        var symbols = rawSymbols.stream().map(s -> s.toUpperCase(Locale.ROOT)).distinct().toList();
        LocalDate end = cutoff.atZone(ZoneOffset.UTC).toLocalDate();
        if (start != null && start.isAfter(end)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Repair start must precede the cutoff");
        }
        var gaps = findGaps(symbols, start, end, cutoff);
        List<Result> results = new ArrayList<>();
        for (Gap gap : gaps.stream().limit(maxRepairs).toList()) {
            try {
                var result = provider.repairDaily(gap.symbol(), gap.date());
                results.add(new Result(gap.symbol(), gap.date(), result.status().name()));
            } catch (ResponseStatusException e) {
                int status = e.getStatusCode().value();
                results.add(new Result(gap.symbol(), gap.date(), "HTTP_" + status));
                if (status == 429 || status == 503) break;
            }
        }
        return new Summary(cutoff, gaps.size(), List.copyOf(results));
    }

    private List<Gap> findGaps(List<String> symbols, LocalDate start, LocalDate end, Instant cutoff) {
        // The query uses the same qualified provider, delivery grace, and daily date labels as ML.
        String selection = symbols.isEmpty()
                ? "EXISTS (SELECT 1 FROM watchlist w WHERE w.asset_id=a.id AND w.enabled=TRUE)"
                : "a.symbol IN (" + String.join(",", Collections.nCopies(symbols.size(), "?")) + ")";
        Timestamp asOf = Timestamp.from(cutoff);
        List<Object> parameters = new ArrayList<>();
        parameters.add(asOf);
        parameters.add(asOf);
        parameters.add(java.sql.Date.valueOf(end));
        if (start != null) parameters.add(java.sql.Date.valueOf(start));
        parameters.addAll(symbols);
        parameters.add(ML_CLOSE_WINDOW);
        return jdbc.query("""
                WITH recent AS (
                  SELECT a.id,a.symbol,a.currency,s.session_date,
                    CAST(CONCAT(CAST(s.session_date AS VARCHAR),'T00:00:00+00:00')
                         AS TIMESTAMP WITH TIME ZONE) AS daily_open,
                    ROW_NUMBER() OVER (PARTITION BY a.id ORDER BY s.session_date DESC) AS session_rank
                  FROM assets a
                  JOIN daily_series_qualifications q ON q.asset_id=a.id
                  JOIN asset_market_policies p ON p.asset_id=a.id
                  JOIN market_sessions s ON s.calendar_code=p.calendar_code
                  WHERE a.asset_type='STOCK' AND q.provider='TWELVE_DATA'
                    AND q.adjustment='SPLIT_ADJUSTED' AND q.qualified_at<=?
                    AND s.opens_at IS NOT NULL
                    AND s.closes_at + p.delivery_grace_seconds * INTERVAL '1' SECOND <= ?
                    AND s.session_date<=? %s
                    AND %s
                )
                SELECT r.symbol,r.session_date FROM recent r
                WHERE r.session_rank<=?
                  AND NOT EXISTS (
                    SELECT 1 FROM market_candles c
                    WHERE c.asset_id=r.id AND c.timeframe='1d' AND c.provider='TWELVE_DATA'
                      AND c.open_time=r.daily_open
                  )
                  AND NOT EXISTS (
                    SELECT 1 FROM provider_price_observations o
                    WHERE o.asset_id=r.id AND o.timeframe='1d' AND o.provider='TWELVE_DATA' AND o.currency=r.currency
                      AND o.open_time=r.daily_open
                  )
                ORDER BY r.session_date DESC,r.symbol
                """.formatted(start == null ? "" : "AND s.session_date>=?", selection), (row, index) ->
                new Gap(row.getString("symbol"), row.getObject("session_date", LocalDate.class)),
                parameters.toArray());
    }
}
