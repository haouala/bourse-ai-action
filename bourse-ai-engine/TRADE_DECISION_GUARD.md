# Module 13 — Trade Decision Guard

`POST /api/v1/trade-guard/review` accepts a *hypothetical* BUY/SELL/HOLD action, currency-consistent available cash and portfolio valuation, current exposure and requested spend.

Fail-closed on stale or missing prices, missing historical model reliability, insufficient opportunity score, excessive risk, bearish or unavailable regime, high volatility, insufficient cash and >25% per-position exposure. Even when all tests pass the result is **REVIEW_REQUIRED**, never APPROVED or a real order.

No broker connector, execution credential, limit order or capital allocation action is invoked. Scores are uncalibrated research heuristics and future outcomes uncertain. The caller-supplied cash/exposure figures are not broker verified; treat all results as diagnostic only. Authentication and verifiable account information are mandatory before any execution integration.

Tests: `mvn -B -ntp clean verify`.
