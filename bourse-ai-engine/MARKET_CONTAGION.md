# Module 6 — Market Contagion Engine

A bounded, **read-only** exploration of statistical relationships between the daily
returns of companies linked in Module 5. This module never generates orders,
price forecasts, risk adjustments, or causal claims.

## Endpoint

```bash
curl 'http://localhost:8081/api/v1/contagion/NVDA?lookbackSessions=250&limit=15'
```

The queried stock is the **source** and each actively linked neighbour is the
**target**. Each pair includes the types of business relationship, number
of common dates, correlation for lags 0, 1, 2 and 5 common observations,
sample sizes, quality warnings and status. Repeat with the other symbol
as root if the reverse lead-lag direction is needed.

## Method and limitations

1. Read canonical daily close prices from the existing `market_candles` table,
   strictly up to the present database time.
2. Compute log returns `ln(close[t] / close[t-1])` individually for each asset.
3. Align returns by their UTC date. Only dates where **both assets** have daily
   returns are compared; no forward-fill of missing dates.
4. Pearson r at lag `k` uses `source[t-k]` and `target[t]` on the ordered
   common-date sequence. It does **not** use source future prices.
5. Return `INSUFFICIENT_OVERLAP` below 30 aligned pairs and `NO_VARIANCE`
   where the correlation denominator is zero. Do not substitute a fabricated 0.
6. Reject pairs whose OHLCV history fails the Module 1 blocking quality gate.
   Non-blocking warnings are returned without hiding the analysis.
7. Bound the request: history 60–1,000 daily observations, maximum 30 linked peers,
   and four fixed lags. No provider credits are spent.

Important: same-date association is not a lead/lag forecast. Lagged correlation
does not prove one company causes another's stock move. Comparisons are sensitive
to common market factors, earnings announcements, survivorship, holiday
misalignment, price adjustments, changing liquidity and multiple-hypothesis
testing. Unadjusted splits, inconsistent exchange calendars and stale prices
are especially dangerous. These statistics **must not** be interpreted as a
probability of profit or fed directly into automated trades.

## Future modules, NOT implemented here

- Market/sector factor residualization and multiple-testing corrections.
- Walk-forward validation, stability by rolling window, change-point detection.
- Price shock scenarios, conditional relationships and calibrated probability.
- Model training and connection to Trade Decision Guard.

## Tests and deployment

```bash
cd bourse-ai-engine
mvn -B -ntp clean verify
```

No changes to the database schema: Module 6 reads Module 5's existing relationship
ledger and daily candles. Production access control and API rate limits remain
separate deployment prerequisites.
