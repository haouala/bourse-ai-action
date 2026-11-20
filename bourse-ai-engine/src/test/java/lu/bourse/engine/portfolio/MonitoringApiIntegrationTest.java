package lu.bourse.engine.portfolio;

import lu.bourse.engine.market.WatchlistService;
import lu.bourse.engine.provider.DatabaseIntegrationSupport;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class MonitoringApiIntegrationTest extends DatabaseIntegrationSupport {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate db;
    @Autowired WatchlistService watchlist;
    @Autowired PortfolioService portfolios;
    @Autowired PortfolioMonitor monitor;
    @Autowired lu.bourse.engine.calendar.MarketCalendarService calendars;
    @MockitoBean Clock clock;
    static final Instant NOW=Instant.parse("2026-11-19T15:00:00Z");
    long asset;
    @BeforeEach void reset(){
        when(clock.instant()).thenReturn(NOW);when(clock.getZone()).thenReturn(ZoneOffset.UTC);
        for(String t:List.of("market_news","financial_facts","company_profiles","asset_provider_symbols","provider_price_observations","market_candles","assets","import_jobs","provider_api_calls"))db.update("DELETE FROM "+t);
        asset=watchlist.register(new WatchlistService.Entry("TEST","Test","STOCK","EUR","TEST","EU","Europe/Luxembourg","09:00","17:30",true));
        LocalDate day=NOW.atZone(ZoneId.of("Europe/Luxembourg")).toLocalDate();
        calendars.importCalendar(new lu.bourse.engine.calendar.MarketCalendarService.CalendarImport("TEST_EU","Europe/Luxembourg","https://example.org/test-calendar",List.of(new lu.bourse.engine.calendar.MarketCalendarService.Session(day,day.atTime(9,0).atZone(ZoneId.of("Europe/Luxembourg")).toInstant(),day.atTime(17,30).atZone(ZoneId.of("Europe/Luxembourg")).toInstant(),"SYNTHETIC_TEST_SESSION"))));
        calendars.assign("TEST",new lu.bourse.engine.calendar.MarketCalendarService.Policy("TEST_EU",180,"ACTIVE",null,3600));
    }
    void bar(String tf,Instant time,BigDecimal close){db.update("INSERT INTO market_candles(asset_id,timeframe,open_time,open_price,high_price,low_price,close_price,volume,provider) VALUES (?,?,?,?,?,?,?,10,'TEST')",asset,tf,Timestamp.from(time),close,close,close,close);}
    long fund(){return portfolios.create(new PortfolioService.CreatePortfolio("Test","EUR",new BigDecimal("1000"),new BigDecimal("100")));}
    long buy(long f,String key,String horizon,int qty){return portfolios.buy(f,new PortfolioService.Buy(key,"TEST",BigDecimal.valueOf(qty),new BigDecimal("100"),BigDecimal.ZERO,BigDecimal.ONE,NOW.minusSeconds(3600),horizon));}
    @Test void chartReturnsChronologicalClosedBarsAndIndependentIndicators() throws Exception {
        for(int i=40;i>=1;i--)bar("15m",NOW.minusSeconds(i*900L),BigDecimal.valueOf(100));
        bar("15m",NOW,BigDecimal.valueOf(999));
        for(int i=40;i>=1;i--)bar("30m",NOW.minusSeconds(i*1800L),BigDecimal.valueOf(200));
        mvc.perform(get("/api/v1/charts/TEST").param("timeframe","15m")).andExpect(status().isOk()).andExpect(jsonPath("$.candles.length()").value(40)).andExpect(jsonPath("$.latestIndicators.sma20").value(100)).andExpect(jsonPath("$.candles[39].closeTime").value(NOW.toString()));
        mvc.perform(get("/api/v1/charts/TEST").param("timeframe","15m").param("limit","40")).andExpect(status().isOk()).andExpect(jsonPath("$.candles.length()").value(40));
        mvc.perform(get("/api/v1/history/analysis/TEST").param("timeframe","30m")).andExpect(status().isOk()).andExpect(jsonPath("$.indicators.sma20").value(200));
        mvc.perform(get("/api/v1/charts/TEST").param("timeframe","2m")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/charts/TEST").param("limit","5000")).andExpect(status().isBadRequest());
    }
    @Test void longTermPositionIgnoresFifteenMinuteMacdWhileShortPositionGetsOneReviewAlert() {
        long f=fund(),shortId=buy(f,"b1","SHORT",1);buy(f,"b2","LONG",1);
        for(int i=0;i<40;i++)bar("15m",NOW.minusSeconds((40-i)*900L),BigDecimal.valueOf(i<35?100:i<39?110:70));
        monitor.evaluate(f);monitor.evaluate(f);
        var alerts=portfolios.alerts(f);
        assertThat(alerts).hasSize(1);assertThat(alerts.getFirst().get("alert_type")).isEqualTo("MACD_REVIEW");
        assertThat(((Number)alerts.getFirst().get("position_id")).longValue()).isEqualTo(shortId);
    }
    @Test void concurrentBuysCannotSpendTheSameCashTwice() throws Exception {
        long f=fund();
        try(var pool=Executors.newFixedThreadPool(2)){
            var results=pool.invokeAll(List.of(()->{try{buy(f,"b1","LONG",7);return true;}catch(Exception e){return false;}},()->{try{buy(f,"b2","LONG",7);return true;}catch(Exception e){return false;}}));
            long successes=0;for(var result:results)if(Boolean.TRUE.equals(result.get()))successes++;
            assertThat(successes).isEqualTo(1);
        }
        assertThat(db.queryForObject("SELECT COUNT(*) FROM position_trades",Integer.class)).isEqualTo(1);
    }
    @Test void missingPriceOnAnotherAssetDoesNotSuppressAFreshMacdReview(){
        long f=fund(),fresh=buy(f,"fresh","SHORT",1);
        watchlist.register(new WatchlistService.Entry("MISSING","Missing price","STOCK","EUR","MISSING","US","America/New_York","09:30","16:00",false));
        portfolios.buy(f,new PortfolioService.Buy("missing","MISSING",BigDecimal.ONE,new BigDecimal("100"),BigDecimal.ZERO,BigDecimal.ONE,NOW.minusSeconds(3600),"SHORT"));
        for(int i=0;i<40;i++)bar("15m",NOW.minusSeconds((40-i)*900L),BigDecimal.valueOf(i<35?100:i<39?110:70));
        var risk=monitor.evaluate(f);assertThat(risk.status()).isEqualTo("DATA_INCOMPLETE");assertThat(risk.loss()).isNull();
        assertThat(portfolios.alerts(f)).hasSize(1);assertThat(((Number)portfolios.alerts(f).getFirst().get("position_id")).longValue()).isEqualTo(fresh);
    }
    @Test void apiPersistsPortfolioAndRejectsInvalidBudget() throws Exception {
        mvc.perform(post("/api/v1/portfolios").contentType("application/json").content("{\"name\":\"Test\",\"baseCurrency\":\"EUR\",\"initialCapital\":1000,\"maxLossAmount\":100}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.id").isNumber());
        mvc.perform(post("/api/v1/portfolios").contentType("application/json").content("{\"name\":\"Test\",\"baseCurrency\":\"EUR\",\"initialCapital\":1000,\"maxLossAmount\":2000}"))
                .andExpect(status().isBadRequest());
    }
}
