package lu.bourse.engine.market;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lu.bourse.engine.service.HistoryService;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.sql.Timestamp;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
public class MarketAnalysisService {
    private final HistoryService history;
    private final JdbcTemplate db;
    private final ObjectMapper json;

    public MarketAnalysisService(HistoryService history, JdbcTemplate db, ObjectMapper json) {
        this.history = history;
        this.db = db;
        this.json = json;
    }

    public String refresh(long asset, String symbol, String timeframe) {
        try {
            var analysis = history.analyze(symbol, timeframe);
            String payload = json.writeValueAsString(analysis);
            Timestamp candle = Timestamp.from(analysis.lastCandleTime());
            int written = db.update("""
                    INSERT INTO market_technical_analyses(asset_id,timeframe,last_candle_at,candle_count,payload)
                    VALUES (?,?,?,?,?) ON CONFLICT DO NOTHING
                    """, asset, timeframe, candle, analysis.candleCount(), payload);
            if (written == 0) {
                written = db.update("""
                        UPDATE market_technical_analyses
                        SET last_candle_at=?,candle_count=?,payload=?,updated_at=CURRENT_TIMESTAMP
                        WHERE asset_id=? AND timeframe=? AND last_candle_at<=? AND payload<>?
                        """, candle, analysis.candleCount(), payload, asset, timeframe, candle, payload);
            }
            return written == 0 ? "CURRENT" : "UPDATED";
        } catch (ResponseStatusException exception) {
            if (exception.getStatusCode() != HttpStatus.UNPROCESSABLE_ENTITY) throw exception;
            return "INSUFFICIENT_HISTORY";
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Could not encode technical analysis", exception);
        }
    }

    public List<Map<String, Object>> latest(String symbol) {
        return db.query("""
                SELECT m.payload FROM market_technical_analyses m
                JOIN assets a ON a.id=m.asset_id
                WHERE a.symbol=? ORDER BY m.timeframe
                """, (row, index) -> {
            try {
                return Map.of("analysis", json.readTree(row.getString(1)));
            } catch (JsonProcessingException exception) {
                throw new IllegalStateException("Could not read technical analysis", exception);
            }
        }, symbol.toUpperCase(Locale.ROOT));
    }
}
