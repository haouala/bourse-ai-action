package lu.bourse.engine.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.*;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@SpringBootTest
@Transactional
class AlpacaIntradayIntegrationTest extends DatabaseIntegrationSupport {
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transaction;

    @Test void fillsOnlyMissingClosedRegularBarsAndPreservesConflictingPrices() throws Exception {
        var clock = Clock.fixed(Instant.parse("2026-11-27T15:16:00Z"),ZoneOffset.UTC);
        var client = mock(AlpacaClient.class);
        var service = new AlpacaIntradayService(jdbc,transaction,client,clock,true);
        var mapper = new ObjectMapper();
        var from = LocalDate.parse("2026-11-21");
        var date = LocalDate.parse("2026-11-27");
        var first = Instant.parse("2026-11-27T14:30:00Z");
        var second = first.plusSeconds(900);
        when(client.configured()).thenReturn(true);
        when(client.calendar(from,date)).thenReturn(mapper.readTree("""
            [{"date":"2026-11-27","open":"09:30","close":"13:00"}]
            """));
        when(client.bars(List.of("ALPACAFIX"),first,second,null)).thenReturn(mapper.readTree("""
            {"bars":{"ALPACAFIX":[
              {"t":"2026-11-27T14:30:00Z","o":100,"h":105,"l":98,"c":101,"v":10},
              {"t":"2026-11-27T14:45:00Z","o":101,"h":105,"l":100,"c":103,"v":12},
              {"t":"2026-11-27T15:00:00Z","o":101,"h":105,"l":100,"c":103,"v":12}
            ]},"next_page_token":null}
            """));
        jdbc.update("INSERT INTO assets(symbol,name,asset_type,currency) VALUES ('ALPACAFIX','Fixture','STOCK','USD')");
        long asset = jdbc.queryForObject("SELECT id FROM assets WHERE symbol='ALPACAFIX'",Long.class);
        jdbc.update("""
            INSERT INTO watchlist(asset_id,provider_symbol,region,market_zone,session_open,session_close)
            VALUES (?,'ALPACAFIX','US','America/New_York','09:30','16:00')
            """,asset);
        jdbc.update("""
            INSERT INTO market_candles(asset_id,timeframe,open_time,open_price,high_price,low_price,close_price,volume,provider)
            VALUES (?,'15m',?,100,105,98,102,20,'TWELVE_DATA')
            """,asset,Timestamp.from(first));
        service.recent();
        assertThat(service.status().recent()).containsEntry("status","SUCCESS").containsEntry("inserted",1)
                .containsEntry("differences",1).containsEntry("missing",0);
        assertThat(jdbc.queryForObject("SELECT close_price FROM market_candles WHERE asset_id=? AND open_time=?",
                java.math.BigDecimal.class,asset,Timestamp.from(first))).isEqualByComparingTo("102");
        assertThat(jdbc.queryForObject("SELECT close_time FROM market_candles WHERE asset_id=? AND open_time=?",
                Timestamp.class,asset,Timestamp.from(second)).toInstant()).isEqualTo(second.plusSeconds(900));
        service.recent();
        assertThat(service.status().recent()).containsEntry("inserted",0);
    }

    @Test void calendarUsesExchangeTimezoneEarlyClosesAndDelayedCutoff() throws Exception {
        var mapper = new ObjectMapper();
        var sessions = mapper.readTree("""
            [{"date":"2026-07-02","open":"09:30","close":"13:00"},
             {"date":"2026-11-27","open":"09:30","close":"13:00"}]
            """);
        var window = new AlpacaIntradayService.Window(LocalDate.parse("2026-07-02"),LocalDate.parse("2026-11-27"));
        var slots = AlpacaIntradayService.expected(sessions,window,Instant.parse("2026-11-27T17:45:00Z"));
        assertThat(slots.firstKey()).isEqualTo(Instant.parse("2026-07-02T13:30:00Z"));
        assertThat(slots.get(Instant.parse("2026-07-02T16:45:00Z"))).isEqualTo(Instant.parse("2026-07-02T17:00:00Z"));
        assertThat(slots.lastEntry()).isEqualTo(java.util.Map.entry(Instant.parse("2026-11-27T17:30:00Z"),
                Instant.parse("2026-11-27T17:45:00Z")));
    }
}
