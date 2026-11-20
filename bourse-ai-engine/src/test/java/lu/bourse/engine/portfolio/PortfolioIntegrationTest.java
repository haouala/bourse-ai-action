package lu.bourse.engine.portfolio;

import lu.bourse.engine.market.*;
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
class PortfolioIntegrationTest extends DatabaseIntegrationSupport {
    @Autowired PortfolioService service;
    @Autowired PortfolioMonitor monitor;
    @Autowired WatchlistService watchlist;
    @Autowired JdbcTemplate db;
    @MockitoBean Clock clock;
    static final Instant NOW=Instant.parse("2026-11-19T15:00:00Z");
    @BeforeEach void reset(){
        when(clock.instant()).thenReturn(NOW);
        when(clock.getZone()).thenReturn(ZoneOffset.UTC);
        for(String t:List.of("market_news","financial_facts","company_profiles","asset_provider_symbols","provider_price_observations","market_candles","assets","import_jobs","provider_api_calls"))db.update("DELETE FROM "+t);
        register("TEST","EUR");
    }
    private static BigDecimal n(String x){return new BigDecimal(x);}
    private void register(String symbol,String currency){watchlist.register(new WatchlistService.Entry(symbol,symbol,"STOCK",currency,symbol,"US","America/New_York","09:30","16:00",true));}
    private long portfolio(){return service.create(new PortfolioService.CreatePortfolio("Test","EUR",n("1000"),n("100")));}
    private PortfolioService.Buy buy(String key,String symbol,String quantity,String price,String fees,String fx,String horizon){return new PortfolioService.Buy(key,symbol,n(quantity),n(price),n(fees),n(fx),NOW.minusSeconds(3600),horizon);}
    private PortfolioService.Trade sell(String key,String qty,String price,String fees){return new PortfolioService.Trade(key,"SELL",n(qty),n(price),n(fees),BigDecimal.ONE,NOW.minusSeconds(60));}
    private void mark(long portfolio,long position,String price){service.mark(portfolio,position,new PortfolioService.Mark(n(price),BigDecimal.ONE,NOW,"TEST"));}

    @Test void buysAreIdempotentAndConflictingReplaysRejected(){
        long f=portfolio();var request=buy("b1","TEST","5","100","2","1","LONG");long p=service.buy(f,request);
        assertThat(service.buy(f,request)).isEqualTo(p);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM position_trades",Integer.class)).isEqualTo(1);
        assertThatThrownBy(()->service.buy(f,buy("b1","TEST","4","100","2","1","LONG"))).isInstanceOf(ResponseStatusException.class);
        assertThat(service.positions(f).getFirst().signalTimeframe()).isEqualTo("1d");
        mark(f,p,"100");assertThat(service.evaluate(f).knownEquity()).isEqualByComparingTo("998");
    }
    @Test void realizedLossSurvivesPartialSaleAndCashAndFeesAreIncluded(){
        long f=portfolio(),p=service.buy(f,buy("b1","TEST","5","100","2","1","SHORT"));
        service.recordTrade(f,p,sell("s1","2","90","1"));mark(f,p,"95");var risk=service.evaluate(f);
        assertThat(risk.cash()).isEqualByComparingTo("677");
        assertThat(risk.knownEquity()).isEqualByComparingTo("962");
        assertThat(risk.loss()).isEqualByComparingTo("38");
        assertThat(db.queryForObject("SELECT realized_pnl_base FROM position_trades WHERE side='SELL'",BigDecimal.class)).isEqualByComparingTo("-21.8");
        service.recordTrade(f,p,sell("s2","3","80","1"));
        var closed=service.evaluate(f);
        assertThat(closed.knownEquity()).isEqualByComparingTo("916");
        assertThat(closed.loss()).isEqualByComparingTo("84");
        assertThat(service.positions(f).getFirst().status()).isEqualTo("CLOSED");
    }
    @Test void globalBreachIsLatchedUntilRecoveryThenRearmed(){
        long f=portfolio(),p=service.buy(f,buy("b1","TEST","10","100","0","1","LONG"));
        mark(f,p,"89");assertThat(service.evaluate(f).status()).isEqualTo("LOSS_LIMIT_REACHED");service.evaluate(f);
        assertThat(service.alerts(f)).hasSize(1);
        mark(f,p,"100");service.evaluate(f);mark(f,p,"88");service.evaluate(f);
        assertThat(service.alerts(f)).hasSize(2);
        assertThat(service.positions(f).getFirst().quantity()).isEqualByComparingTo("10");
    }
    @Test void missingAndStaleDataNeverProduceAHealthyRiskResult(){
        long f=portfolio(),p=service.buy(f,buy("b1","TEST","5","100","0","1","LONG"));
        var missing=service.evaluate(f);assertThat(missing.status()).isEqualTo("DATA_INCOMPLETE");assertThat(missing.loss()).isNull();
        service.mark(f,p,new PortfolioService.Mark(n("80"),BigDecimal.ONE,NOW.minusSeconds(1800),"TEST"));
        assertThat(service.evaluate(f).status()).isEqualTo("STALE_DATA");assertThat(service.alerts(f)).isEmpty();
    }
    @Test void manualFxConvertsForeignPurchaseAndValuationWithoutAssumingParity(){
        register("USD-ASSET","USD");long f=portfolio();long p=service.buy(f,buy("b1","USD-ASSET","5","100","0","0.9","MEDIUM"));
        assertThat(service.evaluate(f).status()).isEqualTo("DATA_INCOMPLETE");
        service.mark(f,p,new PortfolioService.Mark(n("110"),n("0.92"),NOW,"TEST"));
        var r=service.evaluate(f);assertThat(r.cash()).isEqualByComparingTo("550");assertThat(r.knownEquity()).isEqualByComparingTo("1056");
        assertThatThrownBy(()->service.buy(f,buy("b2","TEST","1","100","0","0.9","LONG"))).isInstanceOf(ResponseStatusException.class);
    }
    @Test void oversellingAndOverspendingRollBackWithoutChangingTheLedger(){
        long f=portfolio(),p=service.buy(f,buy("b1","TEST","5","100","0","1","SHORT"));
        assertThatThrownBy(()->service.recordTrade(f,p,sell("s1","6","100","0"))).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(()->service.buy(f,buy("b2","TEST","6","100","0","1","SHORT"))).isInstanceOf(ResponseStatusException.class);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM position_trades",Integer.class)).isEqualTo(1);
    }
    @Test void sellingTwiceWithTheSameKeyDoesNotSellTwice(){
        long f=portfolio(),p=service.buy(f,buy("b1","TEST","5","100","0","1","SHORT"));var r=sell("s1","2","95","0");
        long t=service.recordTrade(f,p,r);assertThat(service.recordTrade(f,p,r)).isEqualTo(t);
        assertThat(service.positions(f).getFirst().quantity()).isEqualByComparingTo("3");
    }
    @Test void markOrderingAndOwnershipAreEnforced(){
        long f=portfolio(),p=service.buy(f,buy("b1","TEST","1","100","0","1","LONG"));mark(f,p,"100");
        assertThatThrownBy(()->service.mark(f,p,new PortfolioService.Mark(n("90"),BigDecimal.ONE,NOW.minusSeconds(1),"TEST"))).isInstanceOf(ResponseStatusException.class);
        long other=portfolio();assertThatThrownBy(()->service.mark(other,p,new PortfolioService.Mark(n("90"),BigDecimal.ONE,NOW,"TEST"))).isInstanceOf(ResponseStatusException.class);
    }
    @Test void planAllocatesOnlyTheGlobalBudgetAndDoesNotEmitOrders(){
        long f=portfolio(),p=service.buy(f,buy("b1","TEST","10","100","0","1","LONG"));mark(f,p,"100");
        var plan=service.plan(f,p);assertThat((BigDecimal)plan.get("proposedProtection")).isEqualByComparingTo("90");
        assertThat(plan.get("executableOrder")).isEqualTo(false);assertThat((BigDecimal)plan.get("targetPrice")).isEqualByComparingTo("120");
    }
    @Test void watchlistAcceptsOneHundredStocksAndThirtyCryptoWithCapsPerType(){
        for(int i=1;i<100;i++)register("S"+i,"EUR");
        assertThatThrownBy(()->register("EXTRA","EUR")).isInstanceOf(ResponseStatusException.class);
        for(int i=0;i<30;i++)watchlist.register(new WatchlistService.Entry("C"+i,"Crypto "+i,"CRYPTO","USD","C"+i+"/USD","GLOBAL","UTC","00:00","23:59",true));
        assertThat(watchlist.list()).hasSize(130);
        assertThatThrownBy(()->watchlist.register(new WatchlistService.Entry("C30","C30","CRYPTO","USD","C30/USD","GLOBAL","UTC","00:00","23:59",true))).isInstanceOf(ResponseStatusException.class);
    }
    @Test void enqueueIsIdempotentForEveryTimeframeAndMarketHoursRespectDst(){
        assertThat(watchlist.enqueue()).isEqualTo(4);assertThat(watchlist.enqueue()).isZero();
        var row=watchlist.list().getFirst();
        assertThat(WatchlistService.sessionOpen(row,Instant.parse("2026-11-21T15:00:00Z"))).isFalse();
        assertThat(WatchlistService.sessionOpen(row,Instant.parse("2026-07-01T13:30:00Z"))).isTrue();
        assertThat(WatchlistService.sessionOpen(row,Instant.parse("2026-11-19T13:30:00Z"))).isFalse();
    }
    @Test void monitorUsesFreshClosedCandlesForSameCurrencyPositions(){
        long f=portfolio(),p=service.buy(f,buy("b1","TEST","10","100","0","1","LONG"));
        long a=db.queryForObject("SELECT id FROM assets WHERE symbol='TEST'",Long.class);
        db.update("INSERT INTO market_candles(asset_id,timeframe,open_time,open_price,high_price,low_price,close_price,volume,provider) VALUES (?,'15m',?,89,90,88,89,100,'TEST')",a,java.sql.Timestamp.from(NOW.minusSeconds(900)));
        assertThat(monitor.evaluate(f).status()).isEqualTo("LOSS_LIMIT_REACHED");assertThat(service.alerts(f)).hasSize(1);
    }
}
