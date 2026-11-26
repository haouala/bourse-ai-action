# Android Kotlin — Unified Research Dashboard

This change DOES NOT replace the existing Android screens or the 15m/30m/1h/1d candle graph.

From Market → select an asset → **Intelligence : prévisions, graphiques, ML et risque**.

The new screen uses the integrated Java backend through the existing OkHttp Transport and AppStore:

- GET `/api/v1/charts/{symbol}/history?limit=250`: observed 1d closes and dated line plot;
- GET `/api/v1/charts/{symbol}/scenarios`: independent low/central/high markers for the 7/30/90/180 day horizons, **no fabricated price trajectory**;
- GET `/api/v1/charts/{symbol}/predictions?limit=100`: observed vs predicted point pairs only when the record is `EVALUATED`;
- GET `/api/v1/charts/{symbol}/ml?limit=100`: chronological accuracy and baseline comparisons, matched to a single model version and forecast horizon;
- GET `/api/v1/scoring/{symbol}?horizonDays=30`: opportunity/risk/unavailable reliability;
- GET `/api/v1/regimes/{symbol}`: historical market regime and realized volatility;
- GET `/api/v1/ai-bottlenecks/{symbol}`: documented evidence and explicit insufficiency state.

The screen's DEMO mode uses `DemoResearch.kt` fake data, completely isolated from `HttpTransport`.
Missing or malformed backend responses show a clear error; they never turn into a bullish or zero-risk score. Pending predictions never get an invented observed close.

**Current scope:** read-only research dashboard. It does not place paper/real orders, create evidence assertions or subscribe to push notifications. The existing portfolio, news and alert screens stay available. Later we should wire the portfolio risk / decision guard / paper trading actions behind user confirmation and authenticated APIs.

Tests: `ResearchChartsTest` covers demo contract compatibility, missing outcome integrity and chart chronology. CI must build Kotlin debug APK and run unit tests against this same backend revision. APK is a development binary and fake data is clearly labeled.
