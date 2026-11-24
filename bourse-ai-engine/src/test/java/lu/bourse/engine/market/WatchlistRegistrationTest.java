package lu.bourse.engine.market;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
class WatchlistRegistrationTest {
 @Test void onlySimpleUsCandidatesAreEligible(){
  assertThat(WatchlistService.isUsRegistrationCandidate("NVDA")).isTrue();
  assertThat(WatchlistService.isUsRegistrationCandidate("BNP.PA")).isFalse();
  assertThat(WatchlistService.isUsRegistrationCandidate("BTC/USD")).isFalse();
  assertThat(WatchlistService.isUsRegistrationCandidate(null)).isFalse();
 }
}
