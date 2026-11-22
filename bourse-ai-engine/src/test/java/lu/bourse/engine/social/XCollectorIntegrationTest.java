package lu.bourse.engine.social;

import com.sun.net.httpserver.HttpServer;
import lu.bourse.engine.provider.DatabaseIntegrationSupport;
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

@SpringBootTest(properties={"social.x.enabled=true","social.x.bearer-token=test-only-not-a-real-token","social.x.monthly-post-limit=15"})
class XCollectorIntegrationTest extends DatabaseIntegrationSupport {
    static final Instant NOW=Instant.parse("2026-11-19T15:00:00Z");
    static final AtomicReference<String> body=new AtomicReference<>(),query=new AtomicReference<>(),auth=new AtomicReference<>();
    static final AtomicInteger status=new AtomicInteger(200),calls=new AtomicInteger();
    static final HttpServer server=start();
    @Autowired XPublicationCollector x;@Autowired SocialSignalService social;@Autowired JdbcTemplate db;@MockitoBean Clock clock;
    static HttpServer start(){try{var s=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);s.createContext("/",e->{calls.incrementAndGet();query.set(e.getRequestURI().getRawQuery());auth.set(e.getRequestHeaders().getFirst("Authorization"));byte[] bytes=body.get().getBytes(StandardCharsets.UTF_8);e.getResponseHeaders().set("Content-Type","application/json");e.sendResponseHeaders(status.get(),bytes.length);try(var out=e.getResponseBody()){out.write(bytes);}});s.start();return s;}catch(Exception e){throw new IllegalStateException(e);}}
    @DynamicPropertySource static void urls(DynamicPropertyRegistry r){r.add("social.x.base-url",()->"http://127.0.0.1:"+server.getAddress().getPort());}
    @AfterAll static void stop(){server.stop(0);}
    @BeforeEach void reset(){when(clock.instant()).thenReturn(NOW);when(clock.getZone()).thenReturn(ZoneOffset.UTC);body.set(page("30",null));status.set(200);calls.set(0);query.set(null);auth.set(null);}
    long actor(){return social.actor(new SocialSignalService.Actor("Synthetic official","Synthetic role","X","12345",true,"https://example.org/verified"));}
    static String page(String id,String next){return "{\"data\":[{\"id\":\""+id+"\",\"text\":\"Test post\",\"created_at\":\"2026-11-19T14:59:00Z\"}],\"meta\":{"+(next==null?"":"\"next_token\":\""+next+"\",")+"\"result_count\":1}}";}
    @Test void paginatedSyncKeepsSinceIdUntilFinalPageAndNeverSkipsTheBacklog(){
        long a=actor();db.update("UPDATE social_actors SET last_post_id='10' WHERE id=?",a);body.set(page("30","next-page"));
        assertThat(x.sync(a).get("morePages")).isEqualTo(true);assertThat(query.get()).contains("since_id=10","post.fields=created_at","max_results=5");
        assertThat(auth.get()).isEqualTo("Bearer test-only-not-a-real-token");assertThat(db.queryForObject("SELECT last_post_id FROM social_actors WHERE id=?",String.class,a)).isEqualTo("10");
        body.set(page("20",null));x.sync(a);assertThat(query.get()).contains("since_id=10","pagination_token=next-page");
        assertThat(db.queryForObject("SELECT last_post_id FROM social_actors WHERE id=?",String.class,a)).isEqualTo("30");
        assertThat(social.publications()).hasSize(2);assertThat(social.events()).isEmpty();
    }
    @Test void monthlyReservationsBlockBeforeHttpAndRemainAfterFailure(){
        long a=actor();status.set(429);body.set("{\"error\":\"must not be leaked\"}");
        for(int i=0;i<3;i++)assertThatThrownBy(()->x.sync(a)).isInstanceOf(ResponseStatusException.class).hasMessageNotContaining("must not be leaked");
        assertThat(calls.get()).isEqualTo(3);assertThatThrownBy(()->x.sync(a)).isInstanceOf(ResponseStatusException.class).hasMessageContaining("allowance exhausted");
        assertThat(calls.get()).isEqualTo(3);assertThat(db.queryForObject("SELECT SUM(reserved_posts) FROM social_api_reservations",Integer.class)).isEqualTo(15);
        assertThat(social.publications()).isEmpty();
    }
    @Test void malformedOrPartialResponseDoesNotAdvanceCursorOrStorePartialPosts(){
        long a=actor();body.set("{\"data\":[{\"id\":\"30\",\"text\":\"missing timestamp\"}],\"meta\":{\"newest_id\":\"30\"}}");
        assertThatThrownBy(()->x.sync(a)).isInstanceOf(ResponseStatusException.class);assertThat(social.publications()).isEmpty();
        assertThat(db.queryForObject("SELECT last_post_id FROM social_actors WHERE id=?",String.class,a)).isNull();
        body.set("{\"data\":[],\"errors\":[{\"detail\":\"partial failure\"}]}");assertThatThrownBy(()->x.sync(a)).isInstanceOf(ResponseStatusException.class);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM social_api_reservations",Integer.class)).isEqualTo(2);
    }
    @Test void emptyPageAndDuplicatePostDoNotCreateEventsOrInventTimestamps(){
        long a=actor();x.sync(a);x.sync(a);assertThat(social.publications()).hasSize(1);
        body.set("{\"meta\":{\"result_count\":0}}");assertThat(x.sync(a).get("fetched")).isEqualTo(0);assertThat(social.events()).isEmpty();
    }
    @Test void unverifiedIdentityIsRejectedBeforeNetworkOrReservation(){
        long a=social.actor(new SocialSignalService.Actor("Unknown","Unknown","X","12345",false,null));
        assertThatThrownBy(()->x.sync(a)).isInstanceOf(ResponseStatusException.class);assertThat(calls.get()).isZero();
        assertThat(db.queryForObject("SELECT COUNT(*) FROM social_api_reservations",Integer.class)).isZero();
    }
}
