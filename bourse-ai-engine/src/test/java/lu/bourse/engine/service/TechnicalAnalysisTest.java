package lu.bourse.engine.service;

import com.fasterxml.jackson.databind.*;
import org.junit.jupiter.api.Test;
import java.nio.file.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class TechnicalAnalysisTest {
    private static final ObjectMapper JSON=new ObjectMapper();
    private JsonNode fixtures() throws Exception {return JSON.readTree(Files.readString(Path.of("../contracts/technical-features-v1.json")));}
    private List<CandlestickPatterns.Bar> bars(JsonNode fixture) {
        var result=new ArrayList<CandlestickPatterns.Bar>();int i=0;
        for(var row:fixture.required("bars")) {
            Instant open=Instant.parse("2026-01-01T00:00:00Z").plusSeconds(i++*86400L);
            result.add(new CandlestickPatterns.Bar(open,open.plusSeconds(72000),money(row.get(0)),money(row.get(1)),money(row.get(2)),money(row.get(3)),money(row.get(4)),row.get(5).asText(),row.get(6).asBoolean()));
        }
        return result;
    }
    private BigDecimal money(JsonNode n){return n.isNull()?null:n.decimalValue();}
    @Test void sharedContractMatchesEveryFeatureAndExpectedFinancialStructure() throws Exception {
        var root=fixtures();assertThat(root.required("method").asText()).isEqualTo(TechnicalAnalysis.VERSION);
        assertThat(JSON.convertValue(root.required("features"),List.class)).containsExactlyElementsOf(TechnicalAnalysis.FEATURES);
        for(var fixture:root.required("cases")) {
            String name=fixture.required("name").asText();var input=bars(fixture);var result=TechnicalAnalysis.analyze(input);
            var expected=fixture.required("expectedFeatures");
            for(String key:TechnicalAnalysis.FEATURES) {
                if(expected.get(key).isNull())assertThat(result.features().get(key)).as(name+":"+key).isNull();
                else assertThat(result.features().get(key)).as(name+":"+key).isCloseTo(expected.get(key).asDouble(),within(1e-9));
            }
            for(var code:fixture.required("expectedSignals"))assertThat(result.signals()).as(name).anyMatch(s->s.code().equals(code.asText()));
            assertThat(result.opportunityDelta()).isBetween(-8,8);assertThat(result.riskDelta()).isBetween(0,8);
            for(var s:result.signals())for(var point:s.points()) {
                assertThat(point.availableAt()).isAfter(point.time());
                assertThat(s.availableAt()).isAfterOrEqualTo(point.availableAt());
                assertThat(s.availableAt()).isBeforeOrEqualTo(input.getLast().closeTime());
            }
        }
    }
    @Test void elliottCorrectionIsNotKnownBeforeTwoFollowingCloses() throws Exception {
        var fixture=fixtures().required("cases").get(7);var b=bars(fixture);
        assertThat(fixture.required("name").asText()).isEqualTo("elliott_abc_up");
        assertThat(TechnicalAnalysis.analyze(b.subList(0,b.size()-1)).features().get("elliott_abc")).isZero();
        assertThat(TechnicalAnalysis.analyze(b).features().get("elliott_abc")).isEqualTo(1.);
    }
    @Test void gapsAndSourceChangesResetTheContextRatherThanCreatingSignals() throws Exception {
        var b=bars(fixtures().required("cases").get(7));var last=b.getLast();
        b.set(b.size()-1,new CandlestickPatterns.Bar(last.openTime(),last.closeTime(),last.open(),last.high(),last.low(),last.close(),last.volume(),"ANOTHER_PROVIDER",true));
        var result=TechnicalAnalysis.analyze(b);assertThat(result.continuousCandles()).isEqualTo(1);
        assertThat(result.signals()).isEmpty();assertThat(result.features().get("hammer")).isNull();
        assertThat(result.opportunityDelta()).isZero();assertThat(result.riskDelta()).isZero();
    }
    @Test void bearishAndNeutralCandlesParticipateInRiskWithoutLoweringRiskForBullishShapes() throws Exception {
        var fixtures=fixtures().required("cases");
        var hammer=TechnicalAnalysis.analyze(bars(fixtures.get(0)));
        assertThat(hammer.opportunityDelta()).isPositive();assertThat(hammer.riskDelta()).isZero();
        var bearish=TechnicalAnalysis.analyze(bars(fixtures.get(4)));
        assertThat(bearish.opportunityDelta()).isNegative();assertThat(bearish.riskDelta()).isPositive();
        var doji=TechnicalAnalysis.analyze(bars(fixtures.get(2)));
        assertThat(doji.features().get("doji")).isEqualTo(1.);assertThat(doji.riskDelta()).isPositive();
    }
}
