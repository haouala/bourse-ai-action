# Module 9 — Forecast Charts Engine

**Scope:** read-only, chart-ready JSON endpoints for separate visualizations in
Android/web clients. This module does **not** ship an Android app or fabricate
daily intermediate forecasts. Full UI navigation and rendering are part of
the later Android Dashboard module.

## Four independent charts

| Endpoint | Series | Semantics |
| --- | --- | --- |
| `GET /api/v1/charts/NVDA/history?limit=250` | `points[{timestamp,close}]` | **Observed** canonical daily prices, chronological; limit 2–1,000 |
| `GET /api/v1/charts/NVDA/scenarios` | `points[{horizonDays,targetDate,pessimistic,central,optimistic,...}]` | Four **estimated endpoints**, 7/30/90/180 calendar days. Missing horizons separately in `unavailable` |
| `GET /api/v1/charts/NVDA/predictions?limit=100` | `points[{issuedAt,targetTime,observedAt,predictedPrice,realizedPrice,absoluteErrorPct,...}]` | Issued prediction vs realized outcome; pending values are **null** |
| `GET /api/v1/charts/NVDA/ml?limit=100` | `points[{trainedAt,horizonSessions,directionAccuracyPct,baselineAccuracyPct,brierScore,baselineBrier,...}]` | ML historical holdout metrics vs baseline; **not** trading profitability |

All endpoints are **GET/read-only**, use existing imported data, and spend no external-provider credits. They return chronologically ordered arrays suitable for graph components. The requested asset must exist; an asset may have empty history or evaluation series. History is capped at 1,000 candles; predictions at 200 records; ML at 100 runs. Unknown symbols and out-of-bound parameters are rejected.

## Recommended chart rules for the UI

1. Show observed daily price separately from future *scenario endpoints*. The latest historical close is a **reference**, not an estimated future point.
2. A line joining future scenario endpoints is only a **visual guide**. Do not label arbitrary intermediate prices as calculated daily predictions; for a rigorously calculated future trajectory, a separate path model/backtest is needed.
3. Show pessimistic, central, optimistic scenarios as distinct series, with source method/version, sample sizes, baseline comparison and historical coverage. **Do not** call the quantile span an 80% confidence interval.
4. Split prediction errors by model name, version and horizon before comparing. Pending rows must show a missing observed-price value, **not zero**. A settled prediction is historically observed data, not a hindsight forecast.
5. Group ML points by their `horizonSessions` and `modelVersion` and compare each against its own baseline. Test-sample sizes must remain visible; historical classification precision does not equal profit probability.
6. Provide clear warnings that splits/dividends, regime shifts, stale prices, and small holdout sets undermine estimates. Never show fictitious default data when the API reports no available estimates.

## Example

```bash
curl 'http://localhost:8081/api/v1/charts/NVDA/history?limit=250'
curl 'http://localhost:8081/api/v1/charts/NVDA/scenarios'
curl 'http://localhost:8081/api/v1/charts/NVDA/predictions?limit=100'
curl 'http://localhost:8081/api/v1/charts/NVDA/ml?limit=100'
```

```bash
cd bourse-ai-engine
mvn -B -ntp clean verify
```

The scenario service reuses module 8's read-only research forecasts and their
own quality checks. The prediction and ML metrics originate from stored module
3/7 records. No migration is needed. Production API access control and
observability are separate deployment requirements.
