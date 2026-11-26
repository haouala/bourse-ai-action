package lu.bourse.engine.seasonality;
import lu.bourse.engine.market.WatchlistService;
import lu.bourse.engine.provider.DatabaseIntegrationSupport;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.server.ResponseStatusException;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.when;
@SpringBootTest
class SeasonalityIntegrationTest extends DatabaseIntegrationSupport {
    @Autowired SeasonalityService service;@Autowired WatchlistService watchlist;@Autowired JdbcTemplate db;@MockitoBean Clock clock;long asset;
    static final Instant NOW=Instant.parse("2026-11-19T15:00:00Z");
    @BeforeEach void setup(){when(clock.instant()).thenReturn(NOW);when(clock.getZone()).thenReturn(ZoneOffset.UTC);for(String t:List.of("market_news","financial_facts","company_profiles","asset_provider_symbols","provider_price_observations","market_candles","assets","import_jobs","provider_api_calls"))db.update("DELETE FROM "+t);asset=watchlist.register(new WatchlistService.Entry("TEST","Test","STOCK","USD","TEST","US","America/New_York","09:30","16:00",true));}
    @Test void rawSeriesIsNotSilentlyTreatedAsAdjustedOrScorable(){var r=service.analyze("TEST",null);assertThat(r.seriesStatus()).isEqualTo("ADJUSTMENT_UNVERIFIED");assertThat(r.usableForScoring()).isFalse();assertThat(r.statistics().monthly()).hasSize(12);assertThat(r.statistics().monthly().getFirst().meanPercent()).isNull();}
    @Test void qualificationIsExplicitAndProviderMustMatch(){db.update("INSERT INTO market_candles(asset_id,timeframe,open_time,open_price,high_price,low_price,close_price,volume,provider) VALUES (?,'1d',?,100,100,100,100,1,'TEST')",asset,java.sql.Timestamp.from(NOW.minusSeconds(172800)));service.qualify("TEST",new SeasonalityService.Qualification("TEST","SPLIT_ADJUSTED","https://example.org/adjustment"));assertThat(service.analyze("TEST",null).seriesStatus()).isEqualTo("SPLIT_ADJUSTED");service.qualify("TEST",new SeasonalityService.Qualification("OTHER","TOTAL_RETURN","https://example.org/adjustment"));assertThat(service.analyze("TEST",null).seriesStatus()).isEqualTo("MIXED_OR_UNQUALIFIED_PROVIDERS");}
    @Test void snapshotIsStoredAndFutureAsOfRejected(){var saved=service.save("TEST");assertThat(saved.get("id")).isNotNull();assertThat(service.history("TEST")).hasSize(1);assertThatThrownBy(()->service.analyze("TEST",NOW.plusSeconds(1))).isInstanceOf(ResponseStatusException.class);}
}
