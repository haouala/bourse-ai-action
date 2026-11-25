package lu.bourse.engine.admin;

import lu.bourse.engine.provider.DatabaseIntegrationSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {
        "admin.monitor-token=fixture-monitor-token-0123456789abcdef",
        "orchestrator.daily-repair.enabled=true"
})
@AutoConfigureMockMvc
@Transactional
class AdminStatusIntegrationTest extends DatabaseIntegrationSupport {
    private static final String TOKEN = "Bearer fixture-monitor-token-0123456789abcdef";
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean Clock clock;

    @Test void requiresAdminToken() throws Exception {
        mvc.perform(get("/api/v1/admin/status"))
                .andExpect(status().isUnauthorized()).andExpect(header().string("Cache-Control", "no-store"));
    }

    @Test void countsTheQualifiedSeriesGapAndShowsALateScheduledRun() throws Exception {
        var now = Instant.parse("2026-11-24T23:00:00Z");
        when(clock.instant()).thenReturn(now);
        when(clock.getZone()).thenReturn(ZoneOffset.UTC);
        jdbc.update("INSERT INTO assets(symbol,name,asset_type,currency) VALUES ('ADMINTEST','Monitor fixture','STOCK','USD')");
        long asset = jdbc.queryForObject("SELECT id FROM assets WHERE symbol='ADMINTEST'", Long.class);
        jdbc.update("""
                INSERT INTO watchlist(asset_id,provider_symbol,region,market_zone,session_open,session_close)
                VALUES (?,'ADMINTEST','US','America/New_York','09:30','16:00')
                """, asset);
        jdbc.update("INSERT INTO asset_market_policies(asset_id,calendar_code) VALUES (?,'US_EQUITIES')", asset);
        jdbc.update("""
                INSERT INTO daily_series_qualifications(asset_id,provider,adjustment,evidence_url,qualified_at)
                VALUES (?,'TWELVE_DATA','SPLIT_ADJUSTED','https://example.org/adjusted',?)
                """, asset, Timestamp.from(Instant.parse("2026-11-24T00:00:00Z")));
        var date = Timestamp.from(Instant.parse("2026-11-24T00:00:00Z"));
        jdbc.update("""
                INSERT INTO market_candles(asset_id,timeframe,open_time,open_price,high_price,low_price,close_price,volume,provider)
                VALUES (?,'1d',?,100,100,100,100,1,'EODHD')
                """, asset, date);

        mvc.perform(get("/api/v1/admin/status").header("Authorization", TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.database").value("UP"))
                .andExpect(jsonPath("$.repair.state").value("LATE"))
                .andExpect(jsonPath("$.market.details[0].latestMissing").value("2026-11-24"))
                .andExpect(jsonPath("$.market.details[0].missingDates[0]").value("2026-11-24"))
                .andExpect(jsonPath("$.market.details[0].missing").value(21));

        jdbc.update("""
                INSERT INTO provider_price_observations
                (asset_id,provider,provider_symbol,timeframe,open_time,open_price,high_price,low_price,close_price,volume,currency)
                VALUES (?,'TWELVE_DATA','ADMINTEST','1d',?,101,101,101,101,1,'USD')
                """, asset, date);
        mvc.perform(get("/api/v1/admin/status").header("Authorization", TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.market.details[0].latestAvailable").value("2026-11-24"))
                .andExpect(jsonPath("$.market.details[0].missing").value(20));
    }
}
