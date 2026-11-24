package lu.bourse.engine.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lu.bourse.engine.calendar.MarketCalendarService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;

import static org.springframework.http.HttpStatus.BAD_REQUEST;

@Service
public class MarketModelResearchService {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final MarketCalendarService calendars;
    private final Clock clock;

    public MarketModelResearchService(JdbcTemplate jdbc, ObjectMapper json, MarketCalendarService calendars, Clock clock) {
        this.jdbc = jdbc;
        this.json = json;
        this.calendars = calendars;
        this.clock = clock;
    }

    public record Result(String symbol, String runId, String horizon, String model, String provider, String currency,
                         Instant asOf, String status, Double estimate, int testCount, double brier,
                         double constantBrier, String validationStatus) {}

    public List<Result> latest(String rawSymbol) {
        String symbol = rawSymbol.toUpperCase(Locale.ROOT);
        if (!symbol.matches("[A-Z0-9._-]{1,40}")) throw new ResponseStatusException(BAD_REQUEST, "Invalid symbol");

        Instant now = clock.instant();
        var ids = jdbc.queryForList("SELECT id FROM assets WHERE symbol=?", Long.class, symbol);
        if (ids.isEmpty()) return List.of();
        var market = calendars.state(ids.getFirst(), Duration.ofDays(1), now);
        return jdbc.query("""
                SELECT * FROM (
                  SELECT r.run_id,r.horizon,r.summary,p.as_of,p.provider,p.currency,p.estimate,
                    ROW_NUMBER() OVER (PARTITION BY r.horizon ORDER BY p.as_of DESC,
                      r.data_cutoff DESC,r.created_at DESC,r.run_id DESC) AS position
                  FROM ml_market_predictions p JOIN ml_market_runs r ON r.run_id=p.run_id
                  WHERE p.symbol=? AND r.data_cutoff<=?
                ) ranked WHERE position=1 ORDER BY horizon
                """, (row, index) -> {
            JsonNode summary = parse(row.getString("summary"));
            String model = summary.required("selectedModel").asText();
            Instant asOf = row.getTimestamp("as_of").toInstant();
            boolean usable = !asOf.isAfter(now) && market.expectedClose() != null
                    && !asOf.isBefore(market.expectedClose()) && !market.marketState().equals("HALTED");
            return new Result(symbol, row.getString("run_id"), row.getString("horizon"), model,
                    row.getString("provider"), row.getString("currency"), asOf,
                    usable ? "RESEARCH_ONLY" : "STALE_OR_UNVERIFIED", usable ? row.getDouble("estimate") : null,
                    summary.required("testCount").asInt(), summary.required("testBrier").required(model).asDouble(),
                    summary.required("constantBrier").asDouble(), summary.required("validationStatus").asText());
        }, symbol, java.sql.Timestamp.from(now));
    }

    private JsonNode parse(String summary) {
        try {
            return json.readTree(summary);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Invalid stored market model report", e);
        }
    }
}
