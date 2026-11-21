package lu.bourse.engine.information;
import lu.bourse.engine.provider.DatabaseIntegrationSupport;
import lu.bourse.engine.api.InformationWorkerController;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.server.ResponseStatusException;
import java.time.*;
import java.sql.Timestamp;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.when;

@SpringBootTest(properties={"information.ai.enabled=true"})
class InformationWorkIntegrationTest extends DatabaseIntegrationSupport {
    @Autowired InformationWorkService work;@Autowired JdbcTemplate db;@MockitoBean Clock clock;
    static final Instant NOW=Instant.parse("2026-11-20T12:00:00Z");
    @BeforeEach void setup(){when(clock.instant()).thenReturn(NOW);when(clock.getZone()).thenReturn(ZoneOffset.UTC);db.update("UPDATE information_sources SET enabled=TRUE WHERE code='FED_PRESS'");db.update("INSERT INTO information_items(source_code,external_key,content_hash,title,summary,original_url,published_at,collected_at,review_status) VALUES ('FED_PRESS','job-demo','demo','Demo','Original summary','https://example.org/a',?,?,'PENDING_REVIEW')",Timestamp.from(NOW.minusSeconds(60)),Timestamp.from(NOW));}
    @Test void enqueueAndClaimAreIdempotentAndExclusive(){assertThat(work.enqueue()).isEqualTo(1);assertThat(work.enqueue()).isZero();assertThat(work.claim().get("status")).isEqualTo("RUNNING");assertThat(work.claim().get("status")).isEqualTo("IDLE");}
    @Test void expiredWorkerCannotWriteAfterAnotherWorkerReclaims(){work.enqueue();var a=work.claim();when(clock.instant()).thenReturn(NOW.plusSeconds(901));var b=work.claim();assertThat(b.get("jobId")).isEqualTo(a.get("jobId"));assertThatThrownBy(()->work.content(id(a),(String)a.get("leaseToken"),content())).isInstanceOf(ResponseStatusException.class);assertThat(work.content(id(b),(String)b.get("leaseToken"),content())).isPositive();}
    @Test void fallbackPreservesSummaryAndContentIsImmutable(){work.enqueue();var a=work.claim();long content=work.content(id(a),(String)a.get("leaseToken"),content());assertThat(work.content(id(a),(String)a.get("leaseToken"),content())).isEqualTo(content);assertThatThrownBy(()->work.content(id(a),(String)a.get("leaseToken"),new InformationWorkService.Content("invented","SUMMARY_ONLY","https://example.org/a","RSS_SUMMARY",null,List.of()))).isInstanceOf(ResponseStatusException.class);}
    @Test void retriesAreDelayedAndBoundedAndContentSurvives(){work.enqueue();for(int i=0;i<3;i++){when(clock.instant()).thenReturn(NOW.plusSeconds(3601L*i));var a=work.claim();if(i==0)work.content(id(a),(String)a.get("leaseToken"),content());else assertThat(a.get("content")).isNotNull();work.fail(id(a),(String)a.get("leaseToken"),new InformationWorkService.Failure("TEMPORARY",true));assertThat(work.claim().get("status")).isEqualTo("IDLE");}assertThat(work.jobs().getFirst().get("status")).isEqualTo("FAILED");}
    @Test void missingAndFutureDatesOrDisabledSourcesAreNotEnqueued(){db.update("UPDATE information_items SET published_at=?",Timestamp.from(NOW.plusSeconds(60)));assertThat(work.enqueue()).isZero();db.update("UPDATE information_items SET published_at=NULL,review_status='DATE_MISSING'");assertThat(work.enqueue()).isZero();}
    @Test void serviceCredentialIsRequiredAndNotExposedInJobListing(){var api=new InformationWorkerController(work,null,"x".repeat(32));assertThatThrownBy(()->api.claim(null)).isInstanceOf(ResponseStatusException.class);assertThatThrownBy(()->new InformationWorkerController(work,null,"").claim("Bearer x")).isInstanceOf(ResponseStatusException.class);api.enqueue("Bearer "+"x".repeat(32));api.claim("Bearer "+"x".repeat(32));assertThat(work.jobs().getFirst()).doesNotContainKeys("lease_token","lease_until");}
    @Test void dailyBudgetDeferralDoesNotExhaustProviderRetryAttempts(){
        work.enqueue();var job=work.claim();work.fail(id(job),(String)job.get("leaseToken"),new InformationWorkService.Failure("DAILY_BUDGET_EXHAUSTED",true));assertThat(work.jobs().getFirst().get("status")).isEqualTo("PENDING");assertThat(work.jobs().getFirst().get("attempts")).isEqualTo(0);
        assertThat(work.claim().get("status")).isEqualTo("IDLE");when(clock.instant()).thenReturn(NOW.plusSeconds(86400));assertThat(work.claim().get("status")).isEqualTo("RUNNING");
    }
    @Test void firstActivationDoesNotAnalyzeYearsOfOldPodcastEpisodes(){
        db.update("UPDATE information_items SET published_at=?",java.sql.Timestamp.from(NOW.minusSeconds(14*86400L)));assertThat(work.enqueue()).isZero();
    }
    static long id(Map<String,Object> job){return ((Number)job.get("jobId")).longValue();}
    static InformationWorkService.Content content(){return new InformationWorkService.Content("Demo\nOriginal summary","SUMMARY_ONLY","https://example.org/a","RSS_SUMMARY","HTTP_403",List.of());}
}
