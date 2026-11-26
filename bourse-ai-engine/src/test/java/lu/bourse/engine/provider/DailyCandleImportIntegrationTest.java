package lu.bourse.engine.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import lu.bourse.engine.service.HistoryService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@SpringBootTest(properties="market.twelve-data.api-key=test-only")
@Transactional
class DailyCandleImportIntegrationTest extends DatabaseIntegrationSupport {
    @Autowired HistoryService history;
    @Autowired JdbcTemplate db;
    @Autowired TwelveDataImportService twelveData;
    @Autowired ObjectMapper json;
    @MockitoBean ProviderHttp http;
    @MockitoBean Clock clock;

    @org.junit.jupiter.api.BeforeEach
    void setTime() {
        when(clock.instant()).thenReturn(Instant.parse("2026-11-25T15:00:00Z"));
        when(clock.getZone()).thenReturn(java.time.ZoneOffset.UTC);
    }

    @Test void retainsTwelveDataWhenAnotherProviderAlreadyOwnsTheCanonicalCandle() {
        assertThat(importCandle("EODHD", "165.05").inserted()).isOne();
        assertThat(importCandle("TWELVE_DATA", "166").inserted()).isOne();
        assertThat(importCandle("TWELVE_DATA", "167").skipped()).isOne();

        assertThat(db.queryForObject("""
                SELECT c.provider FROM market_candles c JOIN assets a ON a.id=c.asset_id
                WHERE a.symbol='REPAIRTEST'
                """, String.class)).isEqualTo("EODHD");
        assertThat(db.queryForObject("""
                SELECT o.close_price FROM provider_price_observations o JOIN assets a ON a.id=o.asset_id
                WHERE a.symbol='REPAIRTEST' AND o.provider='TWELVE_DATA'
                """, BigDecimal.class)).isEqualByComparingTo("166");
    }

    @Test void repairsOnlyTheRequestedSessionAndReusesAnExistingCandle() throws Exception {
        var date = LocalDate.parse("2026-01-05");
        var barTime = Instant.parse("2026-01-05T00:00:00Z");
        BigDecimal price = new BigDecimal("165.05");
        history.importBars(new HistoryService.ImportRequest("REPAIRAPI", "Repair API",
                "STOCK", "USD", "1d", "EODHD",
                List.of(new HistoryService.PriceBar(barTime, price, price, price, price, BigDecimal.ONE))));
        db.update("""
                INSERT INTO asset_market_policies(asset_id,calendar_code)
                SELECT id,'US_EQUITIES' FROM assets WHERE symbol='REPAIRAPI'
                """);
        var operation = ProviderOperation.TWELVE_DAILY;
        when(http.get(eq(operation), contains("start_date=2026-01-05"), isNull()))
                .thenReturn(json.readTree("""
                    {"meta":{"currency":"USD"},"values":[{"datetime":"2026-01-06"}]}
                    """))
                .thenReturn(json.readTree("""
                    {"meta":{"currency":"USD"},"values":[{"datetime":"2026-01-06"},
                    {"datetime":"2026-01-05","open":"166","high":"166","low":"166","close":"166","volume":"1"}]}
                    """));

        assertThat(twelveData.repairDaily("repairapi", date).status())
                .isEqualTo(TwelveDataImportService.RepairStatus.UNAVAILABLE);
        assertThat(twelveData.repairDaily("repairapi", date).status())
                .isEqualTo(TwelveDataImportService.RepairStatus.INSERTED);
        assertThat(twelveData.repairDaily("repairapi", date).status())
                .isEqualTo(TwelveDataImportService.RepairStatus.ALREADY_PRESENT);
        verify(http, times(2)).get(eq(operation), contains("start_date=2026-01-05"), isNull());
    }

    private HistoryService.ImportResult importCandle(String provider, String close) {
        BigDecimal price = new BigDecimal(close);
        var bar = new HistoryService.PriceBar(Instant.parse("2024-01-02T00:00:00Z"),
                price, price, price, price, BigDecimal.ONE);
        return history.importBars(new HistoryService.ImportRequest(
                "REPAIRTEST", "Repair test", "STOCK", "USD", "1d", provider, List.of(bar)));
    }
}
