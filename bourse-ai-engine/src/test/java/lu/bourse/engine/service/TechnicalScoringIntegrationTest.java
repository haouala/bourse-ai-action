package lu.bourse.engine.service;

import lu.bourse.engine.market.WatchlistService;
import lu.bourse.engine.explanation.ExplanationService;
import lu.bourse.engine.provider.DatabaseIntegrationSupport;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class TechnicalScoringIntegrationTest extends DatabaseIntegrationSupport {
    @Autowired WatchlistService watchlist;@Autowired JdbcTemplate db;@Autowired MockMvc mvc;
    @Autowired IntelligentScoringService scores;@Autowired TechnicalAnalysisService technical;
    @Autowired FuturePriceForecastService forecasts;@Autowired ExplanationService explanations;
    @MockitoBean Clock clock;
    static final String SYMBOL="TECHSCORE";
    static final Instant NOW=Instant.parse("2026-11-26T23:00:00Z");
    long asset;
    @BeforeEach void fixture(){
        when(clock.instant()).thenReturn(NOW);when(clock.getZone()).thenReturn(ZoneOffset.UTC);
        db.update("DELETE FROM prediction_records WHERE asset_id IN(SELECT id FROM assets WHERE symbol=?)",SYMBOL);
        db.update("DELETE FROM market_candles WHERE asset_id IN(SELECT id FROM assets WHERE symbol=?)",SYMBOL);
        db.update("DELETE FROM asset_market_policies WHERE asset_id IN(SELECT id FROM assets WHERE symbol=?)",SYMBOL);
        db.update("DELETE FROM assets WHERE symbol=?",SYMBOL);
        asset=watchlist.register(new WatchlistService.Entry(SYMBOL,"Technical fixture","CRYPTO","USD","TECH/USD","GLOBAL","UTC","00:00","00:00",false));
        LocalDate end=LocalDate.of(2026,11,25);
        for(int i=0;i<400;i++){
            double c=i<394?90+i*.01+Math.sin(i*.2):new double[]{110,109,107,105,101,101}[i-394];
            double o=i==399?100:c+.3,h=i==399?101.2:c+1,l=i==399?96:c-1;
            insert(end.minusDays(399-i),o,h,l,c);
        }
    }
    void insert(LocalDate day,double o,double h,double l,double c){
        db.update("INSERT INTO market_candles(asset_id,timeframe,open_time,open_price,high_price,low_price,close_price,volume,provider) VALUES (?,'1d',?,?,?,?,?,100,'FIXTURE')",
                asset,Timestamp.from(day.atStartOfDay(ZoneOffset.UTC).toInstant()),BigDecimal.valueOf(o),BigDecimal.valueOf(h),BigDecimal.valueOf(l),BigDecimal.valueOf(c));
    }
    @Test void candlePointsReachTheActualOpportunityScoreAndRiskWithoutRaisingReliability() throws Exception {
        var r=scores.score(SYMBOL,7);
        assertThat(r.technicalAnalysis().usable()).isTrue();assertThat(r.technicalAnalysis().analysis().features().get("hammer")).isEqualTo(1.);
        assertThat(r.technicalAnalysis().analysis().contributions()).anyMatch(c->c.code().equals("CANDLE_PATTERNS")&&c.opportunity()>0);
        assertThat(r.technicalOpportunityDelta()).isPositive();assertThat(r.technicalRiskDelta()).isNotNegative();
        assertThat(r.opportunityScore()).isBetween(0,100);assertThat(r.riskScore()).isBetween(0,100);assertThat(r.reliabilityScore()).isNull();
        mvc.perform(get("/api/v1/technical-analysis/{symbol}",SYMBOL)).andExpect(status().isOk())
                .andExpect(jsonPath("$.analysis.features.hammer").value(1.0));
        mvc.perform(get("/api/v1/scoring/{symbol}",SYMBOL).param("horizonDays","7")).andExpect(status().isOk())
                .andExpect(jsonPath("$.scoreVersion").value("TECHNICAL_SCORE_V2"));
    }
    @Test void futureAndStillOpenPricesDoNotChangeScenarioOrTechnicalInputs(){
        var before=scores.score(SYMBOL,7);var preview=forecasts.preview(SYMBOL,7);
        insert(LocalDate.of(2026,11,26),1000,1001,999,1000);
        insert(LocalDate.of(2026,11,28),2000,2001,1999,2000);
        var after=scores.score(SYMBOL,7);
        assertThat(after.opportunityScore()).isEqualTo(before.opportunityScore());
        assertThat(after.riskScore()).isEqualTo(before.riskScore());
        assertThat(after.technicalAnalysis().analysis()).isEqualTo(before.technicalAnalysis().analysis());
        assertThat(forecasts.preview(SYMBOL,7).central()).isEqualTo(preview.central());
        assertThat(after.referenceTime()).isEqualTo(Instant.parse("2026-11-25T00:00:00Z"));
    }
    @Test void staleOrGappedSeriesCannotAddFreshTechnicalPoints(){
        db.update("DELETE FROM market_candles WHERE asset_id=? AND open_time=?",asset,Timestamp.from(Instant.parse("2026-11-24T00:00:00Z")));
        assertThat(technical.analyze(SYMBOL,"1d").usable()).isFalse();
        assertThat(scores.score(SYMBOL,7).technicalOpportunityDelta()).isZero();
        db.update("DELETE FROM market_candles WHERE asset_id=? AND open_time=?",asset,Timestamp.from(Instant.parse("2026-11-25T00:00:00Z")));
        assertThat(technical.analyze(SYMBOL,"1d").status()).isEqualTo("STALE");
    }
    @Test void explanationV2AddsAuditedPointsWhileV1RemainsSelectableAndSnapshotsStayFrozen(){
        long legacy=db.queryForObject("SELECT id FROM explanation_rulesets WHERE name='DEFAULT_YEAR_V1'",Long.class);
        var old=explanations.calculate(SYMBOL,ExplanationService.Horizon.YEAR,legacy);
        String frozen=db.queryForObject("SELECT result_json FROM explanation_snapshots WHERE id=?",String.class,old.id());
        var current=explanations.calculate(SYMBOL,ExplanationService.Horizon.YEAR,null);
        assertThat(old.method()).isEqualTo(ExplanationService.LEGACY_METHOD);
        assertThat(old.factors()).noneMatch(f->f.key().equals("TECH_PATTERNS"));
        assertThat(current.method()).isEqualTo(ExplanationService.METHOD);
        assertThat(current.factors()).anyMatch(f->f.key().equals("TECH_PATTERNS")&&f.points().signum()>0);
        assertThat(current.rulesetChanged()).isTrue();
        assertThat(db.queryForObject("SELECT result_json FROM explanation_snapshots WHERE id=?",String.class,old.id())).isEqualTo(frozen);
        BigDecimal total=current.factors().stream().map(ExplanationService.Factor::points).reduce(BigDecimal.ZERO,BigDecimal::add);
        assertThat(current.score()).isEqualByComparingTo(BigDecimal.valueOf(50).add(total));
    }
}
