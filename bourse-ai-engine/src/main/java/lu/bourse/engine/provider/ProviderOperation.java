package lu.bourse.engine.provider;

import lu.bourse.engine.provider.quota.MarketProvider;

/** Each supported paid-key endpoint makes one single-symbol, one-credit request. */
public enum ProviderOperation {
    TWELVE_DAILY(MarketProvider.TWELVE_DATA),
    EODHD_DAILY(MarketProvider.EODHD),
    FMP_PROFILE(MarketProvider.FMP),
    GDELT_NEWS(null),
    SEC_FACTS(null);

    private final MarketProvider quotaProvider;

    ProviderOperation(MarketProvider quotaProvider) {
        this.quotaProvider = quotaProvider;
    }

    public MarketProvider quotaProvider() {
        return quotaProvider;
    }
}
