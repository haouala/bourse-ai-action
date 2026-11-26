package lu.bourse.engine.service;

import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static lu.bourse.engine.service.CandlestickPatterns.*;

class CandlestickPatternsTest {
    private Bar bar(int i, String o, String h, String l, String c) {
        Instant open=Instant.parse("2026-11-20T00:00:00Z").plusSeconds(i*900L);
        return new Bar(open,open.plusSeconds(900),new BigDecimal(o),new BigDecimal(h),new BigDecimal(l),
                new BigDecimal(c),new BigDecimal("100"),"TEST",i>0);
    }
    private List<Bar> falling() {
        return new ArrayList<>(List.of(bar(0,"110","111","108","109"),bar(1,"109","110","106","107"),
                bar(2,"107","108","104","105"),bar(3,"105","106","100","101")));
    }
    private Pattern find(List<Bar> bars,String code) {
        return analyze(bars).patterns().stream().filter(p->p.code().equals(code)).findFirst().orElseThrow();
    }
    @Test void hammerNeedsPriorDeclineAndOnlyNextClosedCandleCanConfirm() {
        var bars=falling();bars.add(bar(4,"100","101.2","96","101"));
        var signal=find(bars,"HAMMER");assertThat(signal.direction()).isEqualTo("BULLISH");
        assertThat(signal.confirmation()).isEqualTo("AWAITING_NEXT_BAR");assertThat(signal.confirmationAt()).isNull();
        bars.add(bar(5,"101","103","100","102"));
        assertThat(find(bars,"HAMMER").confirmation()).isEqualTo("PRICE_CONFIRMED");
        assertThat(find(bars,"HAMMER").confirmationAt()).isEqualTo(bars.get(5).closeTime());
        bars.set(5,bar(5,"101","102","99","100"));bars.add(bar(6,"100","110","99","109"));
        assertThat(find(bars,"HAMMER").confirmation()).isEqualTo("NOT_CONFIRMED");
    }
    @Test void sameHammerShapeInAnUptrendIsNotCalledBullishHammer() {
        var bars=new ArrayList<>(List.of(bar(0,"90","92","89","91"),bar(1,"91","94","90","93"),
                bar(2,"93","96","92","95"),bar(3,"95","100","94","99"),bar(4,"100","101.2","96","101")));
        assertThat(analyze(bars).patterns()).noneMatch(p->p.code().equals("HAMMER"));
    }
    @Test void dojiIsNeutralAndZeroRangeBarsAreIgnored() {
        var p=find(List.of(bar(0,"100","105","95","100.5")),"DOJI");
        assertThat(p.direction()).isEqualTo("NEUTRAL");assertThat(p.confirmation()).isEqualTo("NOT_APPLICABLE");
        assertThat(analyze(List.of(bar(0,"100","100","100","100"))).patterns()).isEmpty();
    }
    @Test void bullishEngulfingUsesBodiesOppositeColoursAndPriorTrend() {
        var bars=falling();bars.add(bar(4,"100","107","99","106"));
        assertThat(find(bars,"BULLISH_ENGULFING").confirmation()).isEqualTo("AWAITING_NEXT_BAR");
        bars.set(4,bar(4,"102","107","99","106"));
        assertThat(analyze(bars).patterns()).noneMatch(p->p.code().equals("BULLISH_ENGULFING"));
    }
    @Test void bearishEngulfingAndDownwardConfirmationAreSymmetric() {
        var bars=new ArrayList<>(List.of(bar(0,"90","92","89","91"),bar(1,"91","94","90","93"),
                bar(2,"93","96","92","95"),bar(3,"95","100","94","99"),bar(4,"100","101","93","94")));
        assertThat(find(bars,"BEARISH_ENGULFING").direction()).isEqualTo("BEARISH");
        bars.add(bar(5,"94","95","90","92"));
        assertThat(find(bars,"BEARISH_ENGULFING").confirmation()).isEqualTo("PRICE_CONFIRMED");
    }
    @Test void gapOrProviderSwitchPreventsDirectionalDetectionAndConfirmation() {
        var bars=falling();Bar b=bar(4,"100","101.2","96","101");
        bars.add(new Bar(b.openTime(),b.closeTime(),b.open(),b.high(),b.low(),b.close(),b.volume(),"OTHER",true));
        assertThat(analyze(bars).patterns()).isEmpty();
        bars.set(4,new Bar(b.openTime(),b.closeTime(),b.open(),b.high(),b.low(),b.close(),b.volume(),"TEST",false));
        assertThat(analyze(bars).patterns()).isEmpty();
        bars.set(4,b);Bar next=bar(6,"101","103","100","102");
        bars.add(new Bar(next.openTime(),next.closeTime(),next.open(),next.high(),next.low(),next.close(),next.volume(),"TEST",false));
        assertThat(find(bars,"HAMMER").confirmation()).isEqualTo("UNAVAILABLE");
    }
    @Test void volumeIsComparedToTwentyPriorBarsAndNeverInvented() {
        var bars=new ArrayList<Bar>();for(int i=0;i<20;i++)bars.add(bar(i,"100","102","99","101"));
        Bar b=bar(20,"101","105","98","101");
        bars.add(new Bar(b.openTime(),b.closeTime(),b.open(),b.high(),b.low(),b.close(),new BigDecimal("200"),"TEST",true));
        Pattern p=find(bars,"DOJI");assertThat(p.volumeRatio()).isEqualByComparingTo("2");assertThat(p.volumeStatus()).isEqualTo("ABOVE_USUAL");
        bars.set(0,new Bar(bars.getFirst().openTime(),bars.getFirst().closeTime(),new BigDecimal("100"),new BigDecimal("102"),new BigDecimal("99"),new BigDecimal("101"),null,"TEST",false));
        assertThat(find(bars,"DOJI").volumeRatio()).isNull();assertThat(find(bars,"DOJI").volumeStatus()).isEqualTo("UNAVAILABLE");
    }
    @Test void worksAtTinyCryptoPricesAndDoesNotRequireFloatingPointRounding() {
        var p=find(List.of(bar(0,"0.00000001","0.000000012","0.000000008","0.0000000101")),"DOJI");
        assertThat(p.code()).isEqualTo("DOJI");
    }
    @Test void exposesOnlyTwelveRecentBarsAndHandlesEmptyAndInvalidData() {
        var bars=new ArrayList<Bar>();for(int i=0;i<30;i++)bars.add(bar(i,"100","102","98","100"));
        assertThat(analyze(bars).patterns()).hasSize(12);assertThat(analyze(bars).inspectedCandles()).isEqualTo(12);
        assertThat(analyze(List.of()).status()).isEqualTo("NO_DATA");
        assertThat(analyze(List.of(bar(0,"100","99","95","101"))).patterns()).isEmpty();
    }
}
