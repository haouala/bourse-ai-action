package lu.bourse.engine.provider;

import com.sun.net.httpserver.HttpServer;
import lu.bourse.engine.market.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.server.ResponseStatusException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.when;

@SpringBootTest(properties={"market.twelve-data.api-key=intraday-test-only","market.quotas.providers.TWELVE_DATA.per-minute=8"})
class IntradayFlowIntegrationTest extends DatabaseIntegrationSupport {
    static final Instant NOW=Instant.parse("2026-11-19T15:00:00Z");
    static final AtomicReference<String> body=new AtomicReference<>();
    static final AtomicReference<String> query=new AtomicReference<>();
    static final AtomicInteger calls=new AtomicInteger();
    static final HttpServer server=start();
    @Autowired JdbcTemplate db;
    @Autowired WatchlistService watchlist;
    @Autowired MarketTaskService tasks;
    @Autowired TwelveDataSeriesService series;
    @Autowired LocalCandleAggregationService aggregation;
    @MockitoBean Clock clock;
    private static HttpServer start(){try{
        var s=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        s.createContext("/",e->{calls.incrementAndGet();query.set(e.getRequestURI().getRawQuery());byte[] bytes=body.get().getBytes(StandardCharsets.UTF_8);e.getResponseHeaders().set("Content-Type","application/json");e.sendResponseHeaders(200,bytes.length);try(var out=e.getResponseBody()){out.write(bytes);}});s.start();return s;
    }catch(Exception e){throw new IllegalStateException(e);}}
    @DynamicPropertySource static void urls(DynamicPropertyRegistry r){r.add("market.twelve-data.base-url",()->"http://127.0.0.1:"+server.getAddress().getPort());}
    @AfterAll static void stop(){server.stop(0);}
    @BeforeEach void reset(){
        when(clock.instant()).thenReturn(NOW);when(clock.getZone()).thenReturn(ZoneOffset.UTC);
        for(String t:List.of("market_news","financial_facts","company_profiles","asset_provider_symbols","provider_price_observations","market_candles","assets","import_jobs","provider_api_calls"))db.update("DELETE FROM "+t);
        db.update("UPDATE provider_quota_locks SET blocked_until=NULL");calls.set(0);query.set(null);body.set(response("USD",true));
    }
    private static String response(String currency,boolean volume){return "{\"meta\":{\"currency\":\""+currency+"\"},\"values\":[{\"datetime\":\"2026-11-19 14:45:00\",\"open\":\"100\",\"high\":\"102\",\"low\":\"99\",\"close\":\"101\""+(volume?",\"volume\":\"12\"":"")+"},{\"datetime\":\"2026-11-19 15:00:00\",\"open\":\"101\",\"high\":\"102\",\"low\":\"100\",\"close\":\"101\",\"volume\":\"1\"}]}";}
    private Map<String,Object> crypto(){watchlist.register(new WatchlistService.Entry("BTC-USD","Bitcoin","CRYPTO","USD","BTC/USD","GLOBAL","UTC","00:00","23:59",true));return db.queryForList("SELECT a.*,w.provider_symbol FROM assets a JOIN watchlist w ON w.asset_id=a.id").getFirst();}
    @Test void persistsBothDerivedTimeframesAndDoesNotDuplicateThem() {
        var asset = crypto();
        Instant start = NOW.minusSeconds(3600);
        for (int offset = 0; offset < 4; offset++) {
            db.update("""
                    INSERT INTO market_candles(asset_id,timeframe,open_time,open_price,high_price,low_price,close_price,volume,provider)
                    VALUES (?,'15m',?,100,102,99,101,12,'TWELVE_DATA')
                    """, asset.get("id"), java.sql.Timestamp.from(start.plusSeconds(offset * 900L)));
        }
        assertThat(aggregation.sync(asset, "30m").inserted()).isEqualTo(2);
        assertThat(aggregation.sync(asset, "1h").inserted()).isEqualTo(1);
        assertThat(aggregation.sync(asset, "30m").skipped()).isEqualTo(2);
        assertThat(db.queryForObject("""
                SELECT volume FROM market_candles WHERE timeframe='1h' AND provider='DERIVED_15M_TWELVE_DATA'
                """, java.math.BigDecimal.class)).isEqualByComparingTo("48");
        assertThat(calls.get()).isZero();
    }
    @Test void nativeIntervalsAndCryptoIdentityArePreservedAndOpenBarsExcluded(){
        var result=series.sync(crypto(),"15m");assertThat(result.inserted()).isEqualTo(1);
        assertThat(query.get()).contains("interval=15min","timezone=UTC");
        assertThat(db.queryForObject("SELECT asset_type FROM assets",String.class)).isEqualTo("CRYPTO");
        assertThat(db.queryForObject("SELECT timeframe FROM market_candles",String.class)).isEqualTo("15m");
        assertThat(series.sync(crypto(),"15m").skipped()).isEqualTo(1);
        assertThat(java.net.URLDecoder.decode(query.get(), StandardCharsets.UTF_8))
                .contains("start_date=2026-11-17 14:45:00");
    }
    @Test void invalidVolumeOrCurrencyAreRejectedWithoutFabricatedCandles(){
        var a=crypto();body.set(response("USD",false));assertThatThrownBy(()->series.sync(a,"15m")).isInstanceOf(ResponseStatusException.class);
        body.set(response("EUR",true));assertThatThrownBy(()->series.sync(a,"15m")).isInstanceOf(ResponseStatusException.class);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM market_candles",Integer.class)).isZero();
    }
    @Test void taskIsDurableAndSuccessfulProcessingIsNotRepeated(){
        crypto();assertThat(watchlist.enqueue()).isEqualTo(4);
        // Process just the first (15m) task; fixture intentionally only supplies minute timestamps.
        assertThat(tasks.runNext().get("status")).isEqualTo("SUCCEEDED");
        assertThat(db.queryForObject("SELECT COUNT(*) FROM market_sync_tasks WHERE status='SUCCEEDED'",Integer.class)).isEqualTo(1);
        assertThat(watchlist.enqueue()).isZero();assertThat(calls.get()).isEqualTo(1);
    }
    @Test void localQuotaDefersTheTaskBeforeHttp(){
        crypto();watchlist.enqueue();
        for(int i=0;i<8;i++)db.update("INSERT INTO provider_api_calls(provider,reserved_at,credits) VALUES ('TWELVE_DATA',?,1)",java.sql.Timestamp.from(NOW));
        assertThat(tasks.runNext().get("status")).isEqualTo("DEFERRED");assertThat(calls.get()).isZero();
        assertThat(db.queryForObject("SELECT COUNT(*) FROM market_sync_tasks WHERE error_code='LOCAL_QUOTA'",Integer.class)).isEqualTo(1);
    }
}
