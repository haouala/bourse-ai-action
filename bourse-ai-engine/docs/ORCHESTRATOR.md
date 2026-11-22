# Bourse AI — Orchestrator V19 (local research pipeline)

The orchestrator only coordinates calculations on **already imported daily closes**.
It deliberately does not duplicate the existing market and news schedulers, and
never imports a price, trains a model, sends push alerts or executes a trade.
All results and errors are persisted in PostgreSQL in `orchestration_assessments`.

## Enable in .env (repo root)

```dotenv
ORCHESTRATOR_ENABLED=false
ORCHESTRATOR_MAX_PER_TICK=5
ORCHESTRATOR_DELAY_MS=300000
# Optional, opt-in after checking provider quotas:
MARKET_SCHEDULER_ENABLED=false
INFORMATION_SCHEDULER_ENABLED=false
```

Start as usual with `bash start.sh` from root (loads .env and activates the
`postgres` profile). Flyway upgrades schema V18 to V19 automatically.
For IntelliJ, import the root .env as process environment (not merely a file
placed in the project) and activate profile `postgres`.

## Step-by-step smoke test (Postman or curl)

```bash
BASE=http://127.0.0.1:8081
curl -s "$BASE/actuator/health"
curl -s "$BASE/api/v1/watchlist"
curl -s "$BASE/api/v1/orchestrator/status"
# Import daily candles for one existing watchlist asset using the existing providers.
curl -s -X POST "$BASE/api/v1/orchestrator/enqueue"
curl -s -X POST "$BASE/api/v1/orchestrator/run?maxAssets=5"
curl -s "$BASE/api/v1/orchestrator/runs"
# Repeating enqueue/run with same latest candle must produce 0 additional assessments.
curl -s -X POST "$BASE/api/v1/orchestrator/run?maxAssets=5"
```

## Detailed state machine

- `DEFERRED`: queued, available to claim after next_attempt_at.
- `RUNNING`: short DB claim, no transaction during financial work; ten-minute
  lease is reclaimable after a process crash.
- `SUCCEEDED`: data quality usable, 30-day score and regime available, local
  research rules evaluated. Repeated restarts do not rerun the same candle.
- `PARTIAL`: insufficient/blocked history, stale source superseded or one of
  the calculated research outputs unavailable. No fake zero-risk signal.
  This assessment is final for that candle; import a newer candle to retry.
- `FAILED`: at most three attempts for unexpected backend failures,
  with 5 / 10 minute backoff. Attempt and error **code** are saved without secrets.

The database idempotency key is `(asset_id,candle_time)`; unique constraint and
short guarded claim protect concurrent scheduler/manual invocations. The
scheduler processes at most `ORCHESTRATOR_MAX_PER_TICK` (1–20) assessments per tick,
default five. A manual run is bounded by `maxAssets=1..20`.

The pipeline analyses *only* enabled watchlist assets with canonical `1d`
candles; it assesses quality, 30-day scoring, market regime and saved
research-alert rules. It never equates score with profit probability.

## Existing import and information workers (not replaced)

- Market: `MARKET_SCHEDULER_ENABLED=true` enables the pre-existing
  15-minute enqueue, worker task processing and portfolio monitoring. This
  collector currently relies on **TwelveDataSeriesService** for routine
  timeframes. A direct one-year/five-year history backfill is still performed
  through the existing provider sync API (or a later dedicated backfill job).
  It does *not* automatically choose EODHD fallback for every ticker.
- News: `INFORMATION_SCHEDULER_ENABLED=true` processes *enabled* RSS feeds,
  with `INFORMATION_MINIMUM_INTERVAL_SECONDS=3600`. Podcast fulltext
  processing and AI classification require the separate Python worker.
- SEC and FMP remain explicit integration calls; do not assume the market
  scheduler automatically imports them.
- ML training and historical evaluation are **not** scheduled automatically:
  these expensive operations need separate budgets, dataset freshness checks,
  and an administrative work queue.

## Operational cautions

The REST control routes are intended only for `127.0.0.1` during development.
There is no production-grade operator authentication on
`POST /api/v1/orchestrator/run`; do **not** bind the backend publicly.
Never commit .env and never share API keys in log screenshots.
PostgreSQL migration V19 is additive; keep the existing Docker volume.
If upgrading a live database, back it up first.
