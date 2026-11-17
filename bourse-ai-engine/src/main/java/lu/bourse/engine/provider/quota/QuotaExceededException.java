package lu.bourse.engine.provider.quota;

import java.time.Instant;

public class QuotaExceededException extends RuntimeException {
    private final MarketProvider provider;
    private final Instant retryAt;

    public QuotaExceededException(MarketProvider provider, Instant retryAt) {
        super("Outgoing calls paused for " + provider);
        this.provider = provider;
        this.retryAt = retryAt;
    }

    public MarketProvider provider() { return provider; }
    public Instant retryAt() { return retryAt; }
}
