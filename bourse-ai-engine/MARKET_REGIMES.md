# Module 11 — Market Regime Detection
GET /api/v1/regimes/NVDA

Read-only historical classification (not a predicted regime).
Last 60 daily sessions: total return (60-close change), annualized realized return volatility (sqrt 252) and trailing maximum drawdown.
Labels: BULL when return >=8% and drawdown >-15%; BEAR when return <=-8% or drawdown <=-20%; otherwise SIDEWAYS.
Volatility levels: NORMAL under 20%, ELEVATED >=20%, HIGH >=40% annualized.
Requires 65 daily closes and passing the historical data-quality block gate. Ambiguous or insufficient history returns no classification instead of guessing.
This heuristic is not a macroeconomic or ML regime classifier, and changing thresholds can change labels. Corporate actions, calendars, currencies and stale data remain limitations.
Unit tests: `mvn -B -ntp clean verify`.
