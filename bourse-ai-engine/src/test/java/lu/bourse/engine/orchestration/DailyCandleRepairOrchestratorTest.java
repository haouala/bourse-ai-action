package lu.bourse.engine.orchestration;

import lu.bourse.engine.provider.DatabaseIntegrationSupport;
import lu.bourse.engine.provider.TwelveDataImportService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@SpringBootTest
@Transactional
class DailyCandleRepairOrchestratorTest extends DatabaseIntegrationSupport {
    @Autowired DailyCandleRepairOrchestrator repairs;
    @Autowired JdbcTemplate db;
    @MockitoBean TwelveDataImportService provider;
    @MockitoBean Clock clock;

    @Test void attemptsOnlyTheMissingQualifiedProviderAndHonoursRepairLimit() {
        Instant now = Instant.parse("2026-11-24T22:00:00Z");
        when(clock.instant()).thenReturn(now);
        when(clock.getZone()).thenReturn(ZoneOffset.UTC);
        db.update("INSERT INTO assets(symbol,name,asset_type,currency) VALUES ('REPAIRGAP','Repair gap','STOCK','USD')");
        long asset = db.queryForObject("SELECT id FROM assets WHERE symbol='REPAIRGAP'", Long.class);
        db.update("INSERT INTO asset_market_policies(asset_id,calendar_code) VALUES (?,'US_EQUITIES')", asset);
        db.update("""
                INSERT INTO daily_series_qualifications(asset_id,provider,adjustment,evidence_url,qualified_at)
                VALUES (?,'TWELVE_DATA','SPLIT_ADJUSTED','https://example.org/adjusted',?)
                """, asset, java.sql.Timestamp.from(Instant.parse("2026-01-01T00:00:00Z")));
        db.update("""
                INSERT INTO market_candles(asset_id,timeframe,open_time,open_price,high_price,low_price,close_price,volume,provider)
                VALUES (?,'1d',?,100,100,100,100,1,'EODHD')
                """, asset, java.sql.Timestamp.from(Instant.parse("2026-11-23T00:00:00Z")));
        db.update("""
                INSERT INTO provider_price_observations
                  (asset_id,provider,provider_symbol,timeframe,open_time,open_price,high_price,low_price,close_price,volume,currency)
                VALUES (?,'TWELVE_DATA','REPAIRGAP','1d',?,100,100,100,100,1,'USD')
                """, asset, java.sql.Timestamp.from(Instant.parse("2026-11-24T00:00:00Z")));
        LocalDate missing = LocalDate.parse("2026-11-23");
        when(provider.repairDaily("REPAIRGAP", missing)).thenReturn(
                new TwelveDataImportService.RepairResult("REPAIRGAP", missing, "1d", "TWELVE_DATA",
                        TwelveDataImportService.RepairStatus.INSERTED));

        var summary = repairs.repair(List.of("repairgap"), null, now, 1);

        assertThat(summary.missing()).isEqualTo(20);
        assertThat(summary.repairs()).containsExactly(
                new DailyCandleRepairOrchestrator.Result("REPAIRGAP", missing, "INSERTED"));
        verify(provider).repairDaily("REPAIRGAP", missing);
        verifyNoMoreInteractions(provider);
    }
}
