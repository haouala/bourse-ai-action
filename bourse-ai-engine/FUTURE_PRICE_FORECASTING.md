# Module 8 — Future Price Forecasting (historical scenario baseline)

**Research-only approximate price scenarios.** This module does not guarantee a future value, supply trading advice, produce a calibrated probability distribution, or expose a model-7 ML inference. Module 7 currently stores validation metrics, NOT a deployed model artifact or trained parameters. We therefore implement a fully labeled, independently evaluated **historical empirical baseline**, not a fabricated ML prediction.

## Endpoints

```bash
# Read-only preview. 7, 30, 90 or 180 CALENDAR days.
curl 'http://localhost:8081/api/v1/forecasts/NVDA?horizonDays=30'

# Explicitly issue an audit record of the CENTRAL price in Module 3.
curl -X POST 'http://localhost:8081/api/v1/forecasts/NVDA/issue?horizonDays=30'

# Later, once the target date passes and prices are imported:
curl -X POST 'http://localhost:8081/api/v1/predictions/NVDA/evaluate'
curl 'http://localhost:8081/api/v1/predictions/NVDA/metrics'
```

Response includes: reference price, currency, timestamp, calendar-day horizon, approximate target date, pessimistic / central / optimistic prices, historic training and holdout sample counts, holdout median absolute percentage error, holdout no-change benchmark error, empirical historical 10–90% scenario coverage, model version and explicit cautions.

## Methodology

1. Retrieve up to 10,000 canonical daily closes no later than query time; require >=250, unique ordered UTC dates and a reference no older than 7 days.
2. For every historical starting date, find the **first observed close** on or after its +h calendar-day target, no more than seven days late. Missing outcomes are excluded, not forward-filled.
3. Split the price timeline at 70% into an earlier training segment and later untouched validation segment. **Training samples must have their entire outcome before the split**. Validation samples must begin after the split and end within observed history.
4. Require >=80 training examples and >=20 historical holdout outcomes at the requested horizon, otherwise respond with HTTP 422 rather than hallucinating predictions.
5. Estimate historical log-return quantiles (10%, 50%, 90%) **from training samples only**. Multiply the latest actual close by `exp(quantile)` for the three scenario prices.
6. Evaluate the central price using holdout absolute percentage error, compare to a naive unchanged-price benchmark, and count the historical fraction of realized returns contained inside the 10–90% training interval.
7. `GET` previews do not write. `POST /issue` deliberately registers the **central** scenario with Module 3's immutable prediction ledger for subsequent real-world error tracking.

## Important cautions

- The 10th and 90th percentiles are **empirical scenarios, not calibrated 80% predictive confidence intervals**. Their historical holdout coverage is returned for context, not as a promised future coverage probability.
- Overlapping historical horizons make samples dependent: sample counts are not independent information counts. Non-stationary market regimes can invalidate these quantiles.
- Historical split/merger/dividend adjustments, FX conversion, exchanges' holiday calendars, execution fees, macro factors, insider activity, news, relationships, and future prices are not modeled.
- **Module 7 ML weights are not yet persisted.** The forecasting model is a transparent historical baseline, not ML trained/inferred output.
- A holdout result that does not beat the no-change benchmark is labeled `BASELINE_NOT_BEATEN`, not presented as reliable.
- A fresh stock price and adequate history are required. Some 180-day forecasts will be unavailable due to insufficient validation data.
- Never treat the result as a buy/sell signal. Each future integration to the trade guard must independently enforce risk and validation thresholds.
- Authenticate and rate-limit `POST /issue` before production; preview is read-only but requires appropriate application-level access controls.

**Charts are NOT part of this module.** Module 9 can render these four dated scenarios and error/coverage metrics, with clear uncertainties, but must not invent intermediate paths between horizons.

## Tests

```bash
cd bourse-ai-engine
mvn -B -ntp clean verify
```

This module reuses Module 3 `prediction_records` for audited issuance. No extra database migration is necessary.
