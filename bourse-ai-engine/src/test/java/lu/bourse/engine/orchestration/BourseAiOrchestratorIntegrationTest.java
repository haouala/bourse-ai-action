package lu.bourse.engine.orchestration;

import lu.bourse.engine.provider.DatabaseIntegrationSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.*;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest
class BourseAiOrchestratorIntegrationTest extends DatabaseIntegrationSupport {
    @Autowired JdbcTemplate jdbc;
    @Autowired BourseAiOrchestratorService orchestrator;

    /**
     * The pipeline never calls market providers. One existing daily close produces exactly
     * one durable assessment, and repeated calls produce no duplicate analyses.
     */
    @Test void enqueuesAndProcessesOnlyOnceForASingleCandle() {
        String symbol="ORCH"+UUID.randomUUID().toString().replace("-","").substring(0,10).toUpperCase();
        long asset=0;
        try {
            jdbc.update("INSERT INTO assets(symbol,name,asset_type,currency) VALUES (?,?,'STOCK','USD')",symbol,"Orchestration fixture");
            asset=jdbc.queryForObject("SELECT id FROM assets WHERE symbol=?",Long.class,symbol);
            jdbc.update("""
                INSERT INTO watchlist(asset_id,provider_symbol,region,market_zone,session_open,session_close,enabled)
                VALUES (?,?,'US','America/New_York','09:30','16:00',TRUE)
                """,asset,symbol);
            Instant candle=LocalDate.now(ZoneOffset.UTC).minusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();
            for(int index=0;index<40;index++){
                Instant at=candle.minus(Duration.ofDays(39L-index));
                jdbc.update("""
                    INSERT INTO market_candles(asset_id,timeframe,open_time,open_price,high_price,low_price,close_price,volume,provider)
                    VALUES (?,'1d',?,?,?,?,?,1000,'TEST')
                    """,asset,Timestamp.from(at),new BigDecimal("98"),new BigDecimal("105"),
                    new BigDecimal("95"),new BigDecimal("100"));
            }
            assertThat(orchestrator.enqueue()).isEqualTo(1);
            assertThat(orchestrator.enqueue()).isZero();
            var first=orchestrator.runNext();
            assertThat(first.symbol()).isEqualTo(symbol);
            assertThat(first.status()).isIn("SUCCEEDED","PARTIAL");
            assertThat(first.quality()).isNotEqualTo("BLOCKED");
            assertThat(orchestrator.runNext().status()).isEqualTo("IDLE");
            assertThat(orchestrator.enqueue()).isZero();
            assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM orchestration_assessments WHERE asset_id=?",
                Integer.class,asset)).isEqualTo(1);
        } finally { cleanup(asset); }
    }

    /** Incomplete market history never creates an optimistic score or repeated retry storms. */
    @Test void incompleteHistoryIsPartialAndStableAcrossRuns() {
        String symbol="ORCH"+UUID.randomUUID().toString().replace("-","").substring(0,10).toUpperCase();
        long asset=0;
        try {
            jdbc.update("INSERT INTO assets(symbol,name,asset_type,currency) VALUES (?,?,'STOCK','USD')",
                symbol,"Incomplete history fixture");
            asset=jdbc.queryForObject("SELECT id FROM assets WHERE symbol=?",Long.class,symbol);
            jdbc.update("""
                INSERT INTO watchlist(asset_id,provider_symbol,region,market_zone,session_open,session_close,enabled)
                VALUES (?,?,'US','America/New_York','09:30','16:00',TRUE)
                """,asset,symbol);
            Instant at=LocalDate.now(ZoneOffset.UTC).minusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();
            jdbc.update("""
                INSERT INTO market_candles(asset_id,timeframe,open_time,open_price,high_price,low_price,close_price,volume,provider)
                VALUES (?,'1d',?,?,?,?,?,1000,'TEST')
                """,asset,Timestamp.from(at),BigDecimal.valueOf(100),BigDecimal.valueOf(101),
                BigDecimal.valueOf(99),BigDecimal.valueOf(100));
            var summary=orchestrator.runBatch(1);
            assertThat(summary.queued()).isEqualTo(1);
            assertThat(summary.processed()).isEqualTo(1);
            assertThat(summary.partial()).isEqualTo(1);
            assertThat(orchestrator.runBatch(1).processed()).isZero();
        } finally { cleanup(asset); }
    }

    @Test void invalidBatchSizeIsRejectedBeforeWritingData() {
        assertThatThrownBy(()->orchestrator.runBatch(0)).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        assertThatThrownBy(()->orchestrator.runBatch(21)).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
    }

    private void cleanup(long asset) {
        if(asset==0)return;
        jdbc.update("DELETE FROM orchestration_assessments WHERE asset_id=?",asset);
        jdbc.update("DELETE FROM market_candles WHERE asset_id=?",asset);
        jdbc.update("DELETE FROM watchlist WHERE asset_id=?",asset);
        jdbc.update("DELETE FROM assets WHERE id=?",asset);
    }
}
