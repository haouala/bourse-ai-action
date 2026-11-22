# Module 10 — Intelligent Scoring (research-only)
GET /api/v1/scoring/NVDA?horizonDays=30

Returns independent **opportunity**, **risk**, and **reliability evidence** indexes (0–100).
- Opportunity is a bounded heuristic from module 8's central estimated historical return.
- Risk rises with downside from the pessimistic scenario, 60-observation realized daily volatility and quality warnings; **higher = riskier**, not better.
- Reliability is **null**, not zero or 50, until >=30 historically settled predictions of the SAME model version and horizon are available, along with the historical forecast baseline comparison.
- A historical quality block or unavailable forecast returns null scores and an explanatory status. No score is a calibrated win probability.
- No model training, securities recommendation, trade execution or false certainty.
- This module does not automatically combine insider/company signals because point-in-time calibration is missing.

The quality gate and price scenarios are inherited from prior modules. Endpoint is GET/read-only. A formula's transparency does not prove predictive efficacy.
Run `mvn -B -ntp clean verify` under `bourse-ai-engine`; review with real adjusted market datasets before any use for investments.
