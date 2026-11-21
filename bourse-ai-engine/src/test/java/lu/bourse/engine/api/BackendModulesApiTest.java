package lu.bourse.engine.api;
import lu.bourse.engine.market.WatchlistService;
import lu.bourse.engine.provider.DatabaseIntegrationSupport;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import java.time.*;
import java.util.*;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
@SpringBootTest
@AutoConfigureMockMvc
class BackendModulesApiTest extends DatabaseIntegrationSupport {
    @Autowired MockMvc mvc;@Autowired JdbcTemplate db;@Autowired WatchlistService watchlist;@MockitoBean Clock clock;
    @BeforeEach void setup(){when(clock.instant()).thenReturn(Instant.parse("2026-11-19T15:00:00Z"));when(clock.getZone()).thenReturn(ZoneOffset.UTC);for(String t:List.of("market_news","financial_facts","company_profiles","asset_provider_symbols","provider_price_observations","market_candles","assets","import_jobs","provider_api_calls"))db.update("DELETE FROM "+t);watchlist.register(new WatchlistService.Entry("API-TEST","API test","STOCK","USD","API-TEST","US","America/New_York","09:30","16:00",false));}
    @Test void calendarAndSeasonalityApiExposeOnlySupportedScales()throws Exception{
        mvc.perform(get("/api/v1/calendars/assets/API-TEST/state")).andExpect(status().isOk()).andExpect(jsonPath("$.marketState").value("OPEN"));
        mvc.perform(get("/api/v1/seasonality/API-TEST")).andExpect(status().isOk()).andExpect(jsonPath("$.statistics.monthly.length()").value(12)).andExpect(jsonPath("$.statistics.quarterly.length()").value(4)).andExpect(jsonPath("$.statistics.annual.length()").value(1)).andExpect(jsonPath("$.usableForScoring").value(false));
    }
    @Test void sourceEnableIsExplicitAndDoesNotRunCollection()throws Exception{
        mvc.perform(get("/api/v1/information/sources")).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(14));
        mvc.perform(post("/api/v1/information/sources/FED_PRESS/sync")).andExpect(status().isConflict());
        mvc.perform(put("/api/v1/information/sources/FED_PRESS/enabled").contentType("application/json").content("{\"enabled\":true}")).andExpect(status().isOk());
        mvc.perform(get("/api/v1/information/runs")).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
    }
    @Test void explanationsPersistNullForMissingDataAndRejectUnknownHorizon()throws Exception{
        mvc.perform(post("/api/v1/explanations/API-TEST").param("horizon","DAY")).andExpect(status().isOk()).andExpect(jsonPath("$.coverageScore").value(0)).andExpect(jsonPath("$.score").isEmpty()).andExpect(jsonPath("$.quoteQuality.status").value("DATA_INCOMPLETE"));
        mvc.perform(get("/api/v1/explanations/API-TEST/history").param("horizon","DAY")).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1));
        mvc.perform(post("/api/v1/explanations/API-TEST").param("horizon","HOUR")).andExpect(status().isBadRequest());
    }
    @Test void invalidCalendarAndRulesAreRejectedWithoutMutations()throws Exception{
        mvc.perform(post("/api/v1/calendars").contentType("application/json").content("{\"code\":\"BAD\",\"zoneId\":\"UTC\",\"sourceUrl\":\"https://example.org/calendar\",\"sessions\":[]}")).andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/explanations/rulesets").contentType("application/json").content("{\"name\":\"invalid\",\"rules\":{\"technicalMax\":100,\"fundamentalMax\":50,\"informationMax\":20,\"marginChangeThreshold\":3,\"rsiOversold\":30,\"rsiOverbought\":70}}")).andExpect(status().isBadRequest());
    }
}
