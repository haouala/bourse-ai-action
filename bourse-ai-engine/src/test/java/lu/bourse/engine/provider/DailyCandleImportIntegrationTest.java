package lu.bourse.engine.provider;

import lu.bourse.engine.service.HistoryService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Transactional
class DailyCandleImportIntegrationTest extends DatabaseIntegrationSupport {
    @Autowired HistoryService history;
    @Autowired JdbcTemplate db;

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

    private HistoryService.ImportResult importCandle(String provider, String close) {
        BigDecimal price = new BigDecimal(close);
        var bar = new HistoryService.PriceBar(Instant.parse("2024-01-02T00:00:00Z"),
                price, price, price, price, BigDecimal.ONE);
        return history.importBars(new HistoryService.ImportRequest(
                "REPAIRTEST", "Repair test", "STOCK", "USD", "1d", provider, List.of(bar)));
    }
}
