# Module 16 — Research Alerts

Opt-in server-side rules for PRICE_ABOVE, PRICE_BELOW, RISK_SCORE_ABOVE. Stored events are emitted by explicit `POST /api/v1/research-alerts/{symbol}/check`. GET rules/history are read-only; rule creation and disabling are authenticated operations in a production deployment.

**No scheduler, notification push, email, Telegram or trading order delivery is activated here.** A repeated check against the same observed candle timestamp is deduplicated by (rule_id,observation_time). Last price must be <=7 days old. Risk score is a research-only heuristic and must not be treated as certainty.

Protect mutation endpoints with authentication and rate limits. Add secure subscriptions and message delivery only in a separately reviewed integration. Tests: `mvn -B -ntp clean verify`.
