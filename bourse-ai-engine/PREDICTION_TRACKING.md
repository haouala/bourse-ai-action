# Module 3 — Prediction Tracking (model accountability)

This module **tracks** externally generated price forecasts. It does NOT invent prices, train a model, or provide live trading signals.

## Endpoints (trusted backend only)

- `POST /api/v1/predictions` — create a forecast against the latest known canonical daily close.
- `GET /api/v1/predictions/id/{id}` — get forecast and outcome.
- `GET /api/v1/predictions/{symbol}?limit=100` — list up to 200 recent predictions.
- `POST /api/v1/predictions/{symbol}/evaluate` — settle due predictions from historical daily prices.
- `GET /api/v1/predictions/{symbol}/metrics` — mean absolute percentage error and directional accuracy by model name/version and horizon.

Example for a trusted internal integration (illustrative predicted price, NOT financial advice):

```bash
curl -X POST http://localhost:8081/api/v1/predictions \
 -H 'Content-Type: application/json' \
 -d '{"symbol":"NVDA","horizonDays":30,"predictedPrice":140,"modelName":"EXPERIMENT","modelVersion":"v1"}'
curl -X POST http://localhost:8081/api/v1/predictions/NVDA/evaluate
curl http://localhost:8081/api/v1/predictions/NVDA/metrics
```

## Semantics

- Supports **7, 30, 90 and 180 calendar days** from forecast submission, not market sessions.
- The recorded reference price and timestamp come from the database, never from the client.
- Forecast requires at least 35 valid candles (quality gate), and a reference close no older than 7 calendar days.
- Forecast identity, source price, model version, issue time, and target time are immutable after insertion.
- Settlement is idempotent. A pending forecast is evaluated against the **first canonical daily candle** timestamp at/after its target (no more than 7 calendar days late); a forecast with no eligible observation stays pending.
- Signed percentage error: (predicted - realized) / realized * 100; absolute error is its magnitude.
- Directional accuracy checks the sign of predicted vs. reference price against realized vs. reference price.
- Grouped model statistics apply only to forecasts that actually matured and were evaluated. Evaluate only after importing fresh daily prices.
- This is an **audit / evaluation feature**. It should not be interpreted as a calibrated probability or guarantee.

## Production blockers / planned follow-up

- Add authentication/authorization for POST endpoints; do not expose to public Internet.
- Add scheduled settlement and market holiday/timezone calendars.
- Review split and dividend adjustments. Source observations can be inconsistent.
- Evaluate bias due to missing delisted companies, data quality warnings, and model drift.
- For now, limit 500 matured records evaluated per request; repeated calls work through backlog.
- Metrics can be misleading on small samples; always inspect `evaluated`.
- There is no model training or forecast chart yet; those belong to separate modules.

## Tests

```bash
cd bourse-ai-engine
mvn -B -ntp clean verify
```
