package lu.bourse.engine.calendar;

import lu.bourse.engine.market.WatchlistService;
import lu.bourse.engine.portfolio.*;
import lu.bourse.engine.provider.DatabaseIntegrationSupport;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.server.ResponseStatusException;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.when;

@SpringBootTest
class CalendarIntegrationTest extends DatabaseIntegrationSupport {
    @Autowired MarketCalendarService calendars;@Autowired WatchlistService watchlist;@Autowired PortfolioService portfolios;@Autowired PortfolioMonitor monitor;@Autowired JdbcTemplate db;
    @MockitoBean Clock clock;long asset;
    static final Instant NOW=Instant.parse("2026-11-19T15:00:00Z");
    @BeforeEach void setup(){
        at(NOW);for(String t:List.of("market_news","financial_facts","company_profiles","asset_provider_symbols","provider_price_observations","market_candles","assets","import_jobs","provider_api_calls"))db.update("DELETE FROM "+t);
        asset=watchlist.register(new WatchlistService.Entry("TEST","Test","STOCK","EUR","TEST","US","America/New_York","09:30","16:00",true));
    }
    void at(Instant now){when(clock.instant()).thenReturn(now);when(clock.getZone()).thenReturn(ZoneOffset.UTC);}
    MarketCalendarService.State state(String instant){return calendars.state(asset,Duration.ofMinutes(15),Instant.parse(instant));}
    @Test void officialHolidayAndEarlyCloseUseTheirTrueLastSession(){
        var thanksgiving=state("2026-11-26T18:00:00Z");assertThat(thanksgiving.marketState()).isEqualTo("CLOSED");assertThat(thanksgiving.expectedClose()).isEqualTo(Instant.parse("2026-11-25T21:00:00Z"));
        var early=state("2026-11-27T19:00:00Z");assertThat(early.marketState()).isEqualTo("CLOSED");assertThat(early.expectedClose()).isEqualTo(Instant.parse("2026-11-27T18:00:00Z"));
    }
    @Test void newYorkTimezoneHandlesDstWithoutFixedLuxembourgHours(){
        assertThat(state("2026-03-20T13:30:00Z").marketState()).isEqualTo("OPEN");
        assertThat(state("2026-11-19T13:30:00Z").marketState()).isEqualTo("CLOSED");
        assertThat(state("2026-11-19T14:30:00Z").marketState()).isEqualTo("OPEN");
    }
    @Test void sundayClosingMarkIsUsableButMissingFridayCloseIsStale(){
        Instant sunday=Instant.parse("2026-11-22T12:00:00Z"),friday=Instant.parse("2026-11-20T21:00:00Z");
        var good=calendars.quality(asset,friday,friday,false,sunday);assertThat(good.usable()).isTrue();assertThat(good.estimated()).isTrue();
        assertThat(calendars.quality(asset,friday.minusSeconds(900),friday,false,sunday).status()).isEqualTo("STALE_DATA");
    }
    @Test void gracePeriodWaitsForTheFirstClosedBarAfterOpening(){
        var before=state("2026-11-19T14:46:00Z");assertThat(before.expectedClose()).isEqualTo(Instant.parse("2026-11-18T21:00:00Z"));
        var after=state("2026-11-19T14:48:00Z");assertThat(after.expectedClose()).isEqualTo(Instant.parse("2026-11-19T14:45:00Z"));
    }
    @Test void unknownCoverageAndHaltsNeverBecomeHealthy(){
        assertThat(state("2029-01-02T18:00:00Z").marketState()).isEqualTo("UNKNOWN");
        calendars.assign("TEST",new MarketCalendarService.Policy("US_EQUITIES",180,"HALTED","Synthetic halt",3600));
        assertThat(calendars.quality(asset,NOW,NOW,false,NOW).status()).isEqualTo("MARKET_HALTED");
    }
    @Test void hourlyPartialBarAndDailyLabelCloseAtTheEarlySessionEnd(){
        assertThat(calendars.barClose(asset,Instant.parse("2026-11-27T17:30:00Z"),Duration.ofHours(1))).isEqualTo(Instant.parse("2026-11-27T18:00:00Z"));
        assertThat(calendars.barClose(asset,Instant.parse("2026-11-27T00:00:00Z"),Duration.ofDays(1))).isEqualTo(Instant.parse("2026-11-27T18:00:00Z"));
        assertThat(calendars.barCloses(asset,List.of(
                Instant.parse("2026-11-27T17:30:00Z"),Instant.parse("2026-11-27T15:00:00Z"),
                Instant.parse("2026-11-27T18:00:00Z")),Duration.ofHours(1)))
                .containsExactly(Instant.parse("2026-11-27T18:00:00Z"),Instant.parse("2026-11-27T16:00:00Z"),
                        Instant.parse("2026-11-27T19:00:00Z"));
    }
    @Test void foreignExchangeHasItsOwnTimestampAndExpiry(){
        Instant sunday=Instant.parse("2026-11-22T12:00:00Z"),friday=Instant.parse("2026-11-20T21:00:00Z");
        assertThat(calendars.quality(asset,friday,friday.minusSeconds(86400),true,sunday).status()).isEqualTo("FX_STALE");
        assertThat(calendars.quality(asset,friday,friday,true,sunday).usable()).isTrue();
        assertThat(calendars.quality(asset,friday,sunday,true,sunday).usable()).isTrue();
    }
    @Test void continuousCryptoStillRequiresFreshDataDuringWeekends(){
        long crypto=watchlist.register(new WatchlistService.Entry("BTC-USD","Bitcoin","CRYPTO","USD","BTC/USD","GLOBAL","UTC","00:00","23:59",true));
        Instant sunday=Instant.parse("2026-11-22T12:05:00Z");assertThat(calendars.quality(crypto,sunday.minusSeconds(300),null,false,sunday).usable()).isTrue();
        assertThat(calendars.quality(crypto,sunday.minusSeconds(1800),null,false,sunday).status()).isEqualTo("STALE_DATA");
    }
    @Test void calendarImportRequiresEveryDateAndPreservesCoverage(){
        var first=new MarketCalendarService.Session(LocalDate.of(2026,1,1),null,null,"TEST_CLOSED");var third=new MarketCalendarService.Session(LocalDate.of(2026,1,3),null,null,"TEST_CLOSED");
        assertThatThrownBy(()->calendars.importCalendar(new MarketCalendarService.CalendarImport("GAPPED","UTC","https://example.org/calendar",List.of(first,third)))).isInstanceOf(ResponseStatusException.class);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM market_calendars WHERE code='GAPPED'",Integer.class)).isZero();
        assertThatThrownBy(()->calendars.importCalendar(new MarketCalendarService.CalendarImport("US_EQUITIES","America/New_York","https://example.org/calendar",List.of(first)))).isInstanceOf(ResponseStatusException.class);
    }
    @Test void closureCatchUpTaskIsCreatedOnceAndNotEveryWeekendPoll(){
        at(Instant.parse("2026-11-22T12:00:00Z"));assertThat(watchlist.enqueue()).isEqualTo(4);assertThat(watchlist.enqueue()).isZero();
        at(Instant.parse("2026-11-22T13:00:00Z"));assertThat(watchlist.enqueue()).isZero();
    }
    @Test void portfolioKeepsAnExplicitClosingEstimateAndLossBreachStillAlerts(){
        long fund=portfolios.create(new PortfolioService.CreatePortfolio("Test","EUR",new BigDecimal("1000"),new BigDecimal("100")));
        long position=portfolios.buy(fund,new PortfolioService.Buy("buy","TEST",new BigDecimal("10"),new BigDecimal("100"),BigDecimal.ZERO,BigDecimal.ONE,NOW,"LONG"));
        Instant friday=Instant.parse("2026-11-20T21:00:00Z");at(friday);portfolios.mark(fund,position,new PortfolioService.Mark(new BigDecimal("100"),BigDecimal.ONE,friday,"TEST"));at(Instant.parse("2026-11-22T12:00:00Z"));
        var r=portfolios.evaluate(fund);assertThat(r.status()).isEqualTo("MARKET_CLOSED_ESTIMATE");assertThat(r.loss()).isEqualByComparingTo("0");
        portfolios.mark(fund,position,new PortfolioService.Mark(new BigDecimal("89"),BigDecimal.ONE,friday,"TEST"));
        assertThat(portfolios.evaluate(fund).status()).isEqualTo("LOSS_LIMIT_REACHED");assertThat(portfolios.alerts(fund)).hasSize(1);
    }
}
