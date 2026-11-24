package lu.bourse.engine.market;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
class WatchlistRegistrationTest {
 @Test void onlySimpleUsCandidatesAreEligible(){
  assertThat(WatchlistService.isUsRegistrationCandidate("NVDA", "XNGS")).isTrue();
  assertThat(WatchlistService.isUsRegistrationCandidate("BRK.B", "XNYS")).isTrue();
  assertThat(WatchlistService.isUsRegistrationCandidate("NVDA", null)).isFalse();
  assertThat(WatchlistService.isUsRegistrationCandidate("BNP", "XPAR")).isFalse();
  assertThat(WatchlistService.isUsRegistrationCandidate("BTC/USD", null)).isFalse();
  assertThat(WatchlistService.isUsRegistrationCandidate(null, "XNYS")).isFalse();
 }
}
