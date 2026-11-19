package lu.bourse.engine.social;

import lu.bourse.engine.market.WatchlistService;
import lu.bourse.engine.portfolio.PortfolioService;
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
import static lu.bourse.engine.social.SocialScoring.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.when;

@SpringBootTest
class SocialSignalIntegrationTest extends DatabaseIntegrationSupport {
    static final Instant NOW=Instant.parse("2026-11-19T15:00:00Z");
    @Autowired SocialSignalService social;@Autowired XPublicationCollector collector;@Autowired JdbcTemplate db;
    @Autowired WatchlistService watchlist;@Autowired PortfolioService portfolios;
    @MockitoBean Clock clock;
    @BeforeEach void reset(){
        when(clock.instant()).thenReturn(NOW);when(clock.getZone()).thenReturn(ZoneOffset.UTC);
        for(String t:List.of("market_news","financial_facts","company_profiles","asset_provider_symbols","provider_price_observations","market_candles","assets","import_jobs","provider_api_calls"))db.update("DELETE FROM "+t);
        register("IMPORTER");register("PRODUCER");
    }
    void register(String symbol){watchlist.register(new WatchlistService.Entry(symbol,symbol,"STOCK","EUR",symbol,"US","America/New_York","09:30","16:00",true));}
    long actor(boolean confirmed){return social.actor(new SocialSignalService.Actor("Test official","Policy role","X",confirmed?"12345":"54321",confirmed,confirmed?"https://example.org/identity":null));}
    void profile(long actor,int authority,Topic topic,Instant from,Instant until){social.profile(actor,new SocialSignalService.Profile(topic,authority,from,until,"Synthetic authority profile, not a real person"));}
    long publication(long actor,String id,Instant at){return social.publication(new SocialSignalService.Publication(actor,id,"Synthetic tariff announcement","https://example.org/posts/"+id,at));}
    long event(long post,Confirmation c){return social.event(new SocialSignalService.Event("test-tariffs","Synthetic tariffs",Topic.TARIFFS,Kind.ANNOUNCEMENT,c,"https://example.org/evidence",List.of(post)));}
    SocialSignalService.Assessment assessment(String symbol,Direction direction){return new SocialSignalService.Assessment(symbol,direction,100,100,100,100,Horizon.INTRADAY,"Synthetic exposure scenario; not a forecast");}
    long prepared(){long a=actor(true);profile(a,100,Topic.TARIFFS,NOW.minusSeconds(86400),null);return event(publication(a,"1",NOW),Confirmation.OFFICIAL_DOCUMENT);}

    @Test void oneEventCanHaveOppositeDirectionsForDifferentAssetsAndKeepsAudit(){
        long event=prepared();var negative=social.assess(event,assessment("IMPORTER",Direction.NEGATIVE));var positive=social.assess(event,assessment("PRODUCER",Direction.POSITIVE));
        assertThat(negative.result().signedScore()).isEqualTo(-95.0);assertThat(positive.result().signedScore()).isEqualTo(95.0);
        assertThat(negative.reviewOnly()).isTrue();assertThat(negative.method()).isEqualTo(METHOD);
        assertThat(social.scores(event)).hasSize(2);
        assertThat((String)social.scores(event).getFirst().get("input_snapshot")).contains("Synthetic exposure","applicableProfiles","identity_evidence_url","collected_at","weights");
    }
    @Test void publicationAndCanonicalEventReplayDoNotMultiplyScoresOrEvents(){
        long a=actor(true);long p=publication(a,"1",NOW);assertThat(publication(a,"1",NOW)).isEqualTo(p);
        long e=event(p,Confirmation.AUTHOR_STATEMENT);assertThat(event(p,Confirmation.AUTHOR_STATEMENT)).isEqualTo(e);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM social_publications",Integer.class)).isEqualTo(1);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM social_events",Integer.class)).isEqualTo(1);
        assertThatThrownBy(()->social.publication(new SocialSignalService.Publication(a,"1","Edited content","https://example.org/posts/1",NOW))).isInstanceOf(ResponseStatusException.class);
    }
    @Test void authorityDependsOnTopicAndValidityAtPublicationNotCurrentRole(){
        long a=actor(true);profile(a,100,Topic.TARIFFS,NOW.minusSeconds(7200),NOW.minusSeconds(3600));profile(a,90,Topic.CRYPTO,NOW.minusSeconds(7200),null);
        long e=event(publication(a,"1",NOW),Confirmation.OFFICIAL_DOCUMENT);
        assertThat(social.assess(e,assessment("IMPORTER",Direction.NEGATIVE)).result().authority()).isZero();
        assertThatThrownBy(()->profile(a,50,Topic.CRYPTO,NOW.minusSeconds(10),null)).isInstanceOf(ResponseStatusException.class);
    }
    @Test void rumorsAndUnverifiedAccountsHaveSeparateConfidenceCaps(){
        long a=actor(false);profile(a,100,Topic.TARIFFS,NOW.minusSeconds(86400),null);long e=event(publication(a,"1",NOW),Confirmation.OFFICIAL_DOCUMENT);
        var r=social.assess(e,assessment("IMPORTER",Direction.NEGATIVE)).result();assertThat(r.potential()).isEqualTo(100);assertThat(r.confidence()).isEqualTo(30);
        social.revise(e,new SocialSignalService.Revision(Confirmation.UNCONFIRMED,"https://example.org/rumor",false));
        assertThat(((Number)social.scores(e).getFirst().get("confidence_score")).intValue()).isEqualTo(30);
    }
    @Test void repostDoesNotRefreshAgeOrAddInfluenceAndUnknownDirectionHasNoSignedScore(){
        long a=actor(true);profile(a,100,Topic.TARIFFS,NOW.minusSeconds(86400),null);long e=event(publication(a,"1",NOW.minusSeconds(900)),Confirmation.OFFICIAL_DOCUMENT);
        var first=social.assess(e,assessment("IMPORTER",Direction.UNKNOWN));assertThat(first.result().potential()).isEqualTo(50);assertThat(first.result().signedScore()).isNull();
        social.link(e,publication(a,"2",NOW));var score=social.rescore(e).getFirst();assertThat(score.result().potential()).isEqualTo(50);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM social_asset_assessments",Integer.class)).isEqualTo(1);
    }
    @Test void highImpactEmitsOneReviewPerOpenPositionAndRetractionEmitsCorrection(){
        long f=portfolios.create(new PortfolioService.CreatePortfolio("Test","EUR",new BigDecimal("1000"),new BigDecimal("100")));
        portfolios.buy(f,new PortfolioService.Buy("test-order","IMPORTER",BigDecimal.ONE,new BigDecimal("100"),BigDecimal.ZERO,BigDecimal.ONE,NOW.minusSeconds(3600),"LONG"));
        long e=prepared();social.assess(e,assessment("IMPORTER",Direction.NEGATIVE));social.rescore(e);social.rescore(e);
        assertThat(portfolios.alerts(f)).hasSize(1);
        social.revise(e,new SocialSignalService.Revision(Confirmation.UNCONFIRMED,"https://example.org/correction",true));
        var latest=social.scores(e).getFirst();assertThat((BigDecimal)latest.get("potential_impact")).isEqualByComparingTo("0");assertThat(((Number)latest.get("confidence_score")).intValue()).isZero();
        assertThat(portfolios.alerts(f)).hasSize(2);assertThat(portfolios.positions(f).getFirst().quantity()).isEqualByComparingTo("1");
    }
    @Test void failedFuturePublicationAndInvalidBoundsDoNotPersist(){
        long a=actor(true);assertThatThrownBy(()->publication(a,"future",NOW.plusSeconds(1))).isInstanceOf(ResponseStatusException.class);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM social_publications",Integer.class)).isZero();
        long e=preparedDifferentActor(a);assertThatThrownBy(()->social.assess(e,new SocialSignalService.Assessment("IMPORTER",Direction.POSITIVE,101,100,100,100,Horizon.DAYS,"invalid"))).isInstanceOf(ResponseStatusException.class);
    }
    long preparedDifferentActor(long a){return event(publication(a,"1",NOW),Confirmation.AUTHOR_STATEMENT);}
    @Test void xIsDisabledByDefaultWithoutReservingOrRequesting(){
        long a=actor(true);assertThatThrownBy(()->collector.sync(a)).isInstanceOf(ResponseStatusException.class).hasMessageContaining("disabled");
        assertThat(db.queryForObject("SELECT COUNT(*) FROM social_api_reservations",Integer.class)).isZero();
    }
}
