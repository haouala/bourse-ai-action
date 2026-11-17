package lu.bourse.engine.provider.quota;

import org.springframework.boot.context.properties.ConfigurationProperties;
import java.util.Map;

@ConfigurationProperties("market.quotas")
public record QuotaProperties(Map<MarketProvider, Limits> providers) {
    public QuotaProperties {
        if (providers == null || providers.size() != MarketProvider.values().length) {
            throw new IllegalArgumentException("Configure quota limits for all market providers");
        }
        providers = Map.copyOf(providers);
        // This version supports free plans only. Zero disables outgoing calls.
        check(providers, MarketProvider.TWELVE_DATA, 800, 8);
        check(providers, MarketProvider.EODHD, 20, 20);
        check(providers, MarketProvider.FMP, 250, 5);
    }

    private static void check(Map<MarketProvider, Limits> providers, MarketProvider provider,
                              int maxDaily, int maxPerMinute) {
        Limits limit = providers.get(provider);
        if (limit == null || limit.daily() < 0 || limit.daily() > maxDaily
                || limit.perMinute() < 0 || limit.perMinute() > maxPerMinute) {
            throw new IllegalArgumentException("Invalid free-plan quota for " + provider);
        }
    }

    public record Limits(int daily, int perMinute) {}
}
