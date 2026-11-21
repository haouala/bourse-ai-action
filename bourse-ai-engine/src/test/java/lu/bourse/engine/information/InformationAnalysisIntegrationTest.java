package lu.bourse.engine.information;
import com.fasterxml.jackson.databind.*;
import lu.bourse.engine.provider.DatabaseIntegrationSupport;
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
class InformationAnalysisIntegrationTest extends DatabaseIntegrationSupport {
    @Autowired lu.bourse.engine.explanation.ExplanationService explanations;@Autowired InformationWorkService work;@Autowired InformationAnalysisService analyses;@Autowired JdbcTemplate db;@Autowired ObjectMapper json;@MockitoBean Clock clock;
    static final Instant NOW=Instant.parse("2026-11-20T12:00:00Z");
    static final String TEXT="The company denies the bankruptcy rumor and maintains its outlook.";
    @BeforeEach void setup(){when(clock.instant()).thenReturn(NOW);when(clock.getZone()).thenReturn(ZoneOffset.UTC);if(db.queryForObject("SELECT count(*) FROM assets WHERE symbol='AI_DEMO'",Integer.class)==0)db.update("INSERT INTO assets(symbol,name,asset_type,currency) VALUES ('AI_DEMO','Demo','STOCK','USD')");db.update("UPDATE information_sources SET enabled=TRUE WHERE code='FED_PRESS'");item("one","FED_PRESS");}
    void item(String key,String source){db.update("INSERT INTO information_items(source_code,external_key,content_hash,title,summary,original_url,published_at,collected_at,review_status) VALUES (?,?,'hash','Demo',?,'https://example.org/a',?,?,'PENDING_REVIEW')",source,key,TEXT,Timestamp.from(NOW.minusSeconds(60)),Timestamp.from(NOW));}
    Map<String,Object> claim(){work.enqueue();var job=work.claim();work.content(id(job),(String)job.get("leaseToken"),new InformationWorkService.Content(TEXT,"ARTICLE_EXTRACT","https://example.org/a","PUBLISHER_HTML",null,List.of()));return job;}
    InformationAnalysisService.Completion result(Map<String,Object> job,String direction)throws Exception{
        String hash=db.queryForObject("SELECT c.content_hash FROM information_contents c JOIN information_ai_jobs j ON j.content_id=c.id WHERE j.id=?",String.class,id(job));
        JsonNode a=json.readTree("{\"summary\":\"Un démenti à vérifier.\",\"uncertainties\":[],\"events\":[{\"eventKey\":\"demo-denial\",\"title\":\"Démenti\",\"explanation\":\"Interprétation expérimentale\",\"quote\":\""+TEXT+"\",\"speaker\":null,\"topic\":\"CORPORATE\",\"kind\":\"DENIAL\",\"impacts\":[{\"symbol\":\"AI_DEMO\",\"direction\":\""+direction+"\",\"horizon\":\"DAY\",\"importance\":100,\"relevance\":100,\"novelty\":100,\"interpretationConfidence\":100}]}]}");
        return new InformationAnalysisService.Completion(hash,TEXT.length(),a);
    }
    @Test void resultCarriesSummaryEvidenceAndBoundedScoreButIsNotAutoApproved()throws Exception{var job=claim();long a=analyses.complete(id(job),(String)job.get("leaseToken"),result(job,"NEGATIVE"));assertThat(a).isPositive();assertThat(analyses.asset("AI_DEMO","DAY").get("approvedScore")).isEqualTo(new java.math.BigDecimal("0.0000"));assertThat(db.queryForObject("SELECT proposed_score FROM information_ai_impacts WHERE analysis_id=?",java.math.BigDecimal.class,a)).isEqualByComparingTo("-35");assertThat(analyses.analyses(((Number)((Map<?,?>)job.get("item")).get("id")).longValue()).getFirst().get("result_json").toString()).contains(TEXT,"Un démenti");}
    @Test void immutableCompletionCanBeRetriedAfterLostResponse()throws Exception{var job=claim();var r=result(job,"NEGATIVE");long a=analyses.complete(id(job),(String)job.get("leaseToken"),r);assertThat(analyses.complete(id(job),(String)job.get("leaseToken"),r)).isEqualTo(a);assertThatThrownBy(()->analyses.complete(id(job),"different-worker",r)).isInstanceOf(ResponseStatusException.class);assertThat(db.queryForObject("SELECT count(*) FROM information_ai_analyses",Integer.class)).isEqualTo(1);}
    @Test void unsupportedQuoteUnknownAssetOrPartialCoverageRollsBack()throws Exception{var job=claim();var r=result(job,"NEGATIVE");((com.fasterxml.jackson.databind.node.ObjectNode)r.analysis().get("events").get(0)).put("quote","invented quote which is absent");assertThatThrownBy(()->analyses.complete(id(job),(String)job.get("leaseToken"),r)).isInstanceOf(ResponseStatusException.class);assertThatThrownBy(()->analyses.complete(id(job),(String)job.get("leaseToken"),new InformationAnalysisService.Completion(r.contentHash(),1,r.analysis()))).isInstanceOf(ResponseStatusException.class);assertThat(db.queryForObject("SELECT count(*) FROM information_ai_analyses",Integer.class)).isZero();assertThat(work.jobs().getFirst().get("status")).isEqualTo("RUNNING");}
    @Test void repeatedCoverageAddsAttentionButNotTheSameDirectionalScoreTwice()throws Exception{var first=claim();long a=analyses.complete(id(first),(String)first.get("leaseToken"),result(first,"NEGATIVE"));analyses.review(a,new InformationAnalysisService.Review(true,"Verified interpretation",Map.of()));item("two","FED_PRESS");var second=claim();long b=analyses.complete(id(second),(String)second.get("leaseToken"),result(second,"NEGATIVE"));analyses.review(b,new InformationAnalysisService.Review(true,"Same event",Map.of()));var r=analyses.asset("AI_DEMO","DAY");assertThat(r.get("approvedEventCount")).isEqualTo(1);assertThat(((java.math.BigDecimal)r.get("approvedScore")).abs()).isLessThanOrEqualTo(new java.math.BigDecimal("35"));assertThat(((Map<?,?>)r.get("attention")).get("publications24h")).isEqualTo(2);assertThat(((Map<?,?>)r.get("attention")).get("publisherGroups24h")).isEqualTo(1);}
    @Test void ambiguousDirectionRemainsNullAndDoesNotBecomeNeutralConfidence()throws Exception{var job=claim();long a=analyses.complete(id(job),(String)job.get("leaseToken"),result(job,"UNKNOWN"));analyses.review(a,new InformationAnalysisService.Review(true,"Ambiguous event retained",Map.of()));assertThat(db.queryForObject("SELECT proposed_score FROM information_ai_impacts WHERE analysis_id=?",java.math.BigDecimal.class,a)).isNull();assertThat(analyses.asset("AI_DEMO","DAY").get("approvedEventCount")).isEqualTo(0);}
    @Test void heartbeatExtendsLeaseAndReviewCannotRewriteHistory()throws Exception{var job=claim();when(clock.instant()).thenReturn(NOW.plusSeconds(800));analyses.heartbeat(id(job),(String)job.get("leaseToken"));when(clock.instant()).thenReturn(NOW.plusSeconds(1000));long a=analyses.complete(id(job),(String)job.get("leaseToken"),result(job,"NEGATIVE"));analyses.review(a,new InformationAnalysisService.Review(false,"Unverified",Map.of()));assertThatThrownBy(()->analyses.review(a,new InformationAnalysisService.Review(true,"Change",Map.of()))).isInstanceOf(ResponseStatusException.class);}
    @Test void approvedAiContributesOnlyAfterReviewAndSnapshotsRemainImmutable()throws Exception{
        var job=claim();long a=analyses.complete(id(job),(String)job.get("leaseToken"),result(job,"NEGATIVE"));
        long rules=explanations.createRules(new lu.bourse.engine.explanation.ExplanationService.NewRules("AI_TEST_"+UUID.randomUUID(),new lu.bourse.engine.explanation.ExplanationService.Rules(0,0,20,java.math.BigDecimal.ONE,java.math.BigDecimal.TEN,new java.math.BigDecimal("90"))));
        var before=explanations.calculate("AI_DEMO",lu.bourse.engine.explanation.ExplanationService.Horizon.DAY,rules);assertThat(before.factors().stream().filter(f->f.key().equals("AI_INFORMATION")).findFirst().orElseThrow().points()).isZero();
        analyses.review(a,new InformationAnalysisService.Review(true,"Source and grouping checked",Map.of()));
        var after=explanations.calculate("AI_DEMO",lu.bourse.engine.explanation.ExplanationService.Horizon.DAY,rules);assertThat(before.score()).isNull();assertThat(after.score()).isLessThan(new java.math.BigDecimal("50"));assertThat(explanations.get(before.id()).get("score")).isNull();
    }
    @Test void operatorReviewRequiresASeparateCredential(){
        var api=new lu.bourse.engine.api.InformationController(null,work,analyses,"r".repeat(32));
        assertThatThrownBy(()->api.review(1,null,new InformationAnalysisService.Review(true,"Review",Map.of()))).isInstanceOf(ResponseStatusException.class);
    }
    @Test void conflictingIndependentReportsDoNotProduceAnArbitraryDirection()throws Exception{
        var first=claim();long a=analyses.complete(id(first),(String)first.get("leaseToken"),result(first,"NEGATIVE"));analyses.review(a,new InformationAnalysisService.Review(true,"Same event checked",Map.of("demo-denial","shared-event")));
        item("two","FED_PRESS");var second=claim();long b=analyses.complete(id(second),(String)second.get("leaseToken"),result(second,"POSITIVE"));analyses.review(b,new InformationAnalysisService.Review(true,"Conflicting interpretation retained",Map.of("demo-denial","shared-event")));
        assertThat(db.queryForObject("SELECT count(DISTINCT content_hash) FROM information_contents",Integer.class)).isEqualTo(1);
        var value=analyses.asset("AI_DEMO","DAY");assertThat(value.get("approvedEventCount")).isEqualTo(0);assertThat(value.get("conflictingEventCount")).isEqualTo(1);
    }
    @Test void approvalCanBeWithdrawnWithoutErasingItsHistory()throws Exception{
        var job=claim();long a=analyses.complete(id(job),(String)job.get("leaseToken"),result(job,"NEGATIVE"));analyses.review(a,new InformationAnalysisService.Review(true,"Initially checked",Map.of()));analyses.review(a,new InformationAnalysisService.Review(false,"Source retracted its statement",Map.of()));
        assertThat(analyses.reviews(a)).hasSize(2);assertThat(analyses.reviews(a).getFirst().get("decision")).isEqualTo("APPROVED");assertThat(analyses.asset("AI_DEMO","DAY").get("approvedEventCount")).isEqualTo(0);
    }
    @Test void correctedArticleSupersedesOldApprovedVersionUntilItIsReexamined()throws Exception{
        var job=claim();long a=analyses.complete(id(job),(String)job.get("leaseToken"),result(job,"NEGATIVE"));analyses.review(a,new InformationAnalysisService.Review(true,"Original checked",Map.of()));
        db.update("INSERT INTO information_items(source_code,external_key,content_hash,title,summary,original_url,published_at,collected_at,review_status) VALUES ('FED_PRESS','one','corrected','Correction','Corrected text','https://example.org/a',?,?,'PENDING_REVIEW')",Timestamp.from(NOW.minusSeconds(60)),Timestamp.from(NOW));
        assertThat(analyses.asset("AI_DEMO","DAY").get("approvedEventCount")).isEqualTo(0);assertThat(analyses.reviews(a)).hasSize(1);
    }
    static long id(Map<String,Object> job){return ((Number)job.get("jobId")).longValue();}
}
