package lu.bourse.engine.service;

import lu.bourse.engine.provider.DatabaseIntegrationSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.sql.Timestamp;
import java.time.Instant;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class MarketModelResearchIntegrationTest extends DatabaseIntegrationSupport {
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;

    @Test void keepsStalePredictionsAsEvidenceWithoutExposingTheirEstimate() throws Exception {
        String symbol = "MLMARKETFIXTURE";
        String run = "market-test-run";
        var old = Timestamp.from(Instant.parse("2020-01-01T20:00:00Z"));
        jdbc.update("INSERT INTO assets(symbol,name,asset_type,currency) VALUES (?,?,'STOCK','USD')", symbol, symbol);
        try {
            jdbc.update("INSERT INTO ml_market_runs(run_id,horizon,data_cutoff,summary) VALUES (?,'DAY',?,?)", run, old,
                    """
                    {"selectedModel":"LOGISTIC","testCount":100,"testBrier":{"LOGISTIC":0.24},
                     "constantBrier":0.25,"validationStatus":"ABOVE_CONSTANT_ON_HOLDOUT"}
                    """);
            jdbc.update("""
                    INSERT INTO ml_market_predictions(run_id,symbol,as_of,currency,provider,estimate)
                    VALUES (?,?,?,'USD','FIXTURE',0.63)
                    """, run, symbol, old);
            mvc.perform(get("/api/v1/ml/market/{symbol}", symbol.toLowerCase(java.util.Locale.ROOT)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$[0].status").value("STALE_OR_UNVERIFIED"))
                    .andExpect(jsonPath("$[0].estimate").doesNotExist())
                    .andExpect(jsonPath("$[0].testCount").value(100));
            mvc.perform(get("/api/v1/ml/market/ABSENT"))
                    .andExpect(status().isOk()).andExpect(content().json("[]"));
        } finally {
            jdbc.update("DELETE FROM ml_market_predictions WHERE run_id=?", run);
            jdbc.update("DELETE FROM ml_market_runs WHERE run_id=?", run);
            jdbc.update("DELETE FROM assets WHERE symbol=?", symbol);
        }
    }
}
