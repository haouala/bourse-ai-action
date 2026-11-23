package lu.bourse.engine.news;

import org.junit.jupiter.api.Test;
import java.time.Instant;
import static org.assertj.core.api.Assertions.assertThat;

class NewsCollectionScheduleTest {
    @Test void ngramsUsesIndependentQuarterHourMinuteRatherThanGkgManifestSlot() {
        assertThat(NewsCollectionService.ngramCandidate(Instant.parse("2026-11-23T15:08:00Z")))
            .isEqualTo("20261123150100");
        assertThat(NewsCollectionService.ngramCandidate(Instant.parse("2026-11-23T15:02:00Z")))
            .isEqualTo("20261123144600");
        assertThat(NewsCollectionService.ngramCandidate(Instant.parse("2026-11-23T15:25:00Z")))
            .isEqualTo("20261123151600");
    }
}
