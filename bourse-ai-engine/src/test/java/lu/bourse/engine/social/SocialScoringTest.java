package lu.bourse.engine.social;

import org.junit.jupiter.api.Test;
import java.time.*;
import static lu.bourse.engine.social.SocialScoring.*;
import static org.assertj.core.api.Assertions.*;

class SocialScoringTest {
    final Instant now=Instant.parse("2026-11-19T15:00:00Z");
    @Test void rumorCannotBecomeConfirmedJustBySettingADocumentLabel(){
        var r=compute(100,100,100,100,100,Confirmation.OFFICIAL_DOCUMENT,Kind.RUMOR,true,false,Direction.NEGATIVE,Horizon.DAYS,now,now);
        assertThat(r.potential()).isEqualTo(100);assertThat(r.confidence()).isEqualTo(35);assertThat(r.signedScore()).isEqualTo(-35);
    }
    @Test void retractedInformationHasNoRemainingDirectionalContribution(){
        var r=compute(100,100,100,100,100,Confirmation.OFFICIAL_DOCUMENT,Kind.RETRACTION,true,false,Direction.POSITIVE,Horizon.DAYS,now,now);
        assertThat(r.potential()).isZero();assertThat(r.confidence()).isZero();assertThat(r.signedScore()).isZero();
    }
    @Test void exposureAndTimeDecayReduceImpactWhileMixedDirectionStaysUnresolved(){
        var r=compute(100,100,50,100,90,Confirmation.AUTHOR_STATEMENT,Kind.ANNOUNCEMENT,true,false,Direction.MIXED,Horizon.DAYS,now.minusSeconds(86400),now);
        assertThat(r.potential()).isEqualTo(25);assertThat(r.confidence()).isEqualTo(65);assertThat(r.signedScore()).isNull();
    }
}
