package lu.bourse.engine.information;

import com.sun.net.httpserver.HttpServer;
import lu.bourse.engine.provider.DatabaseIntegrationSupport;
import lu.bourse.engine.social.SocialSignalService;
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
import java.util.concurrent.atomic.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.when;

@SpringBootTest
class InformationIntegrationTest extends DatabaseIntegrationSupport {
    static final Instant NOW=Instant.parse("2026-11-19T15:00:00Z");
    static final AtomicReference<String> body=new AtomicReference<>(),conditional=new AtomicReference<>();static final AtomicInteger status=new AtomicInteger(),calls=new AtomicInteger();
    static final HttpServer server=start();
    @Autowired InformationService info;@Autowired SocialSignalService social;@Autowired JdbcTemplate db;@MockitoBean Clock clock;
    static HttpServer start(){try{var s=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);s.createContext("/",e->{calls.incrementAndGet();conditional.set(e.getRequestHeaders().getFirst("If-None-Match"));e.getResponseHeaders().set("ETag","test-etag");e.getResponseHeaders().set("Content-Type","application/rss+xml");if(status.get()==304){e.sendResponseHeaders(304,-1);e.close();return;}byte[] bytes=body.get().getBytes(StandardCharsets.UTF_8);e.sendResponseHeaders(status.get(),bytes.length);try(var out=e.getResponseBody()){out.write(bytes);}});s.start();return s;}catch(Exception e){throw new IllegalStateException(e);}}
    @DynamicPropertySource static void urls(DynamicPropertyRegistry r){r.add("information.test-base-url",()->"http://127.0.0.1:"+server.getAddress().getPort());}
    @AfterAll static void stop(){server.stop(0);}
    @BeforeEach void setup(){when(clock.instant()).thenReturn(NOW);when(clock.getZone()).thenReturn(ZoneOffset.UTC);body.set(feed("Synthetic official says tariffs may change","Thu, 19 Nov 2026 14:00:00 GMT"));status.set(200);calls.set(0);conditional.set(null);}
    static String feed(String text,String date){return "<rss><channel><item><guid>one</guid><title>Policy statement</title><link>https://example.org/statement</link><description>"+text+"</description>"+(date==null?"":"<pubDate>"+date+"</pubDate>")+"</item></channel></rss>";}
    void ready(){db.update("UPDATE information_sources SET next_attempt_at=NULL WHERE code='FED_PRESS'");}
    @Test void disabledByDefaultAndSourceCannotBeCalledBeforeExplicitEnable(){assertThatThrownBy(()->info.sync("FED_PRESS")).isInstanceOf(ResponseStatusException.class);assertThat(calls.get()).isZero();assertThat(info.sources()).hasSize(12);}
    @Test void defaultPollingIsHourlyWithoutChangingMarketIntervals(){
        info.enable("FED_PRESS",true);info.sync("FED_PRESS");
        assertThat(lu.bourse.engine.persistence.SqlIds.instant(db.queryForObject("SELECT next_attempt_at FROM information_sources WHERE code='FED_PRESS'",Object.class))).isEqualTo(NOW.plusSeconds(3600));
        when(clock.instant()).thenReturn(NOW.plusSeconds(3599));
        assertThatThrownBy(()->info.sync("FED_PRESS")).isInstanceOf(ResponseStatusException.class);
        when(clock.instant()).thenReturn(NOW.plusSeconds(3600));
        info.sync("FED_PRESS");assertThat(calls.get()).isEqualTo(2);
    }
    @Test void rssPersistsDatesAndSecondImportIsIdempotent(){info.enable("FED_PRESS",true);assertThat(info.sync("FED_PRESS").get("inserted")).isEqualTo(1);ready();assertThat(info.sync("FED_PRESS").get("inserted")).isEqualTo(0);assertThat(info.items(null)).hasSize(1);assertThat(social.publications()).hasSize(1);assertThat(info.items(null).getFirst().get("published_at")).isNotNull();assertThat(conditional.get()).isEqualTo("test-etag");}
    @Test void correctedFeedTextRetainsBothVersionsAndOriginalPublication(){info.enable("FED_PRESS",true);info.sync("FED_PRESS");ready();body.set(feed("Corrected statement about tariffs","Thu, 19 Nov 2026 14:00:00 GMT"));info.sync("FED_PRESS");assertThat(info.items(null)).hasSize(2);assertThat(social.publications()).hasSize(2);}
    @Test void missingOrFutureDatesDoNotBecomeAuthoritativePublications(){info.enable("FED_PRESS",true);body.set(feed("Undated statement",null));info.sync("FED_PRESS");assertThat(info.items(null).getFirst().get("review_status")).isEqualTo("DATE_MISSING");ready();body.set(feed("Future statement","Fri, 20 Nov 2026 14:00:00 GMT"));info.sync("FED_PRESS");assertThat(social.publications()).isEmpty();}
    @Test void nameMatchDoesNotAssertWhoSpokeAndQuoteMustBeVerbatim(){long actor=social.actor(new SocialSignalService.Actor("Synthetic official","Policy role","OFFICIAL_WEB","test-official",true,"https://example.org/identity"));info.enable("FED_PRESS",true);info.sync("FED_PRESS");long item=((Number)info.items(null).getFirst().get("id")).longValue();assertThat(info.mentions(item).getFirst().get("attribution_status")).isEqualTo("NAME_MATCH_ONLY");assertThatThrownBy(()->info.attribute(item,new InformationService.Attribution(actor,"This phrase was never collected","https://example.org/proof"))).isInstanceOf(ResponseStatusException.class);var r=info.attribute(item,new InformationService.Attribution(actor,"tariffs may change","https://example.org/proof"));assertThat(r.get("publicationId")).isPositive();assertThat(info.mentions(item).getFirst().get("attribution_status")).isEqualTo("HUMAN_VERIFIED_QUOTE");assertThat(info.attribute(item,new InformationService.Attribution(actor,"tariffs may change","https://example.org/proof"))).isEqualTo(r);}
    @Test void httpFailuresReleaseLeaseAndPersistBackoffWithoutLeakingRemoteBody(){info.enable("FED_PRESS",true);status.set(429);body.set("remote secret must not leak");assertThatThrownBy(()->info.sync("FED_PRESS")).isInstanceOf(ResponseStatusException.class).hasMessageNotContaining("remote secret");assertThat(info.runs().getFirst().get("status")).isEqualTo("FAILED");assertThatThrownBy(()->info.sync("FED_PRESS")).isInstanceOf(ResponseStatusException.class);assertThat(calls.get()).isEqualTo(1);assertThat(db.queryForObject("SELECT lease_token FROM information_sources WHERE code='FED_PRESS'",String.class)).isNull();}
    @Test void conditionalNotModifiedDoesNotDownloadOrDuplicateItems(){info.enable("FED_PRESS",true);info.sync("FED_PRESS");ready();status.set(304);assertThat(info.sync("FED_PRESS").get("status")).isEqualTo("NOT_MODIFIED");assertThat(info.items(null)).hasSize(1);}
    @Test void xmlFailureIsAtomicAndDoesNotLeaveRunningImport(){info.enable("FED_PRESS",true);body.set("<!DOCTYPE rss [<!ENTITY x SYSTEM 'file:///etc/passwd'>]><rss>&x;</rss>");assertThatThrownBy(()->info.sync("FED_PRESS")).isInstanceOf(ResponseStatusException.class);assertThat(info.items(null)).isEmpty();assertThat(info.runs().getFirst().get("status")).isEqualTo("FAILED");}
    @Test void expiredLeaseIsReclaimedAndOldRunIsMarkedAbandoned(){info.enable("FED_PRESS",true);db.update("UPDATE information_sources SET lease_token='old-worker',lease_until=? WHERE code='FED_PRESS'",java.sql.Timestamp.from(NOW.minusSeconds(1)));db.update("INSERT INTO information_sync_runs(source_code,started_at,status) VALUES ('FED_PRESS',?,'RUNNING')",java.sql.Timestamp.from(NOW.minusSeconds(120)));assertThat(info.sync("FED_PRESS").get("status")).isEqualTo("SUCCEEDED");assertThat(db.queryForObject("SELECT COUNT(*) FROM information_sync_runs WHERE status='ABANDONED'",Integer.class)).isEqualTo(1);}
}
