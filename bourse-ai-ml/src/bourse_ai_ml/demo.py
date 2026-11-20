"""Reproducible fictional prices. Dates are scenario labels, not historical data."""
from datetime import datetime, timezone
import numpy as np
from .domain import DatasetSpec, HORIZONS, Observation, validate


def stress_tag(day: str) -> str:
    if "2008-09-01" <= day <= "2009-06-30":
        return "DEMO_FINANCIAL_2008"
    if "2020-02-20" <= day <= "2020-06-30":
        return "DEMO_COVID_2020"
    if "2022-02-24" <= day <= "2022-12-31":
        return "DEMO_GEOPOLITICAL_2022"
    return "DEMO_NORMAL"


def generate(spec: DatasetSpec) -> list[Observation]:
    dates = np.arange(np.datetime64(spec.start), np.datetime64(spec.end) + np.timedelta64(1, "D"))
    dates = dates[np.is_busday(dates)]
    n = len(dates)
    horizon = HORIZONS[spec.horizon]
    if n < horizon + 22:
        raise ValueError("Pas assez de séances fictives pour cet horizon")
    tags = [stress_tag(str(day)) for day in dates]
    stress = np.array([t != "DEMO_NORMAL" for t in tags])
    market_rng = np.random.default_rng(spec.seed)
    market_returns = market_rng.normal(0, 1, n) * np.where(stress, .018, .006)
    market_returns += np.where(stress, -.0015, .00025)
    market_momentum = np.array([np.tanh(market_returns[max(0, i-19):i+1].sum()*4) for i in range(n)])
    volatility = np.array([market_returns[max(0, i-19):i+1].std()*np.sqrt(252) for i in range(n)])
    results = []
    amplitudes = {"DAY": (20, 5, 20), "MONTH": (15, 15, 20), "YEAR": (10, 25, 15)}[spec.horizon]
    for asset in range(spec.assets):
        # Independent streams keep the past unchanged when the period is extended.
        fundamental_noise = np.random.default_rng(spec.seed+100+asset*11).normal(0, .09, n)
        news_noise = np.random.default_rng(spec.seed+101+asset*11).normal(0, .30, n)
        price_noise = np.random.default_rng(spec.seed+102+asset*11).normal(0, 1, n)
        overnight_noise = np.random.default_rng(spec.seed+103+asset*11).normal(0, .002, n)
        fund = np.zeros(n); news = np.zeros(n); close = np.zeros(n); opening = np.zeros(n); tech = np.zeros(n)
        close[0] = opening[0] = 70 + asset*17
        for i in range(n):
            fund[i] = np.clip((fund[i-1]*.97 if i else 0)+fundamental_noise[i], -1, 1)
            news[i] = np.clip((news[i-1]*.70 if i else 0)+news_noise[i], -1, 1)
            if i:
                opening[i] = close[i-1]*np.exp(overnight_noise[i])
                signal = .0006*tech[i-1]+.0008*fund[i-1]+.0010*news[i-1]
                log_return = market_returns[i]*(.6+asset*.07)+price_noise[i]*(.009 if not stress[i] else .018)+signal
                close[i] = opening[i]*np.exp(np.clip(log_return, -.3, .3))
            tech[i] = np.tanh(np.log(close[i]/close[max(0, i-20)])*4)
        for i in range(20, n-horizon):
            as_of = datetime.fromisoformat(str(dates[i])+"T16:00:00+00:00")
            entry_at = datetime.fromisoformat(str(dates[i+1])+"T09:00:00+00:00")
            label_at = datetime.fromisoformat(str(dates[i+horizon])+"T16:00:00+00:00")
            entry, exit_ = float(opening[i+1]), float(close[i+horizon])
            cost = spec.cost_bps/10_000
            baseline = 50 + amplitudes[0]*tech[i]+amplitudes[1]*fund[i]+amplitudes[2]*news[i]
            results.append(Observation(f"DEMO_{asset+1:03d}", as_of, as_of, entry_at, label_at,
                tuple(float(v) for v in (tech[i], fund[i], news[i], market_momentum[i], volatility[i])),
                float(np.clip(baseline, 0, 100)), entry, exit_, exit_/entry-1-cost,
                float(close[i+1:i+horizon+1].min()/entry-1-cost), tags[i]))
    results.sort(key=lambda row: (row.as_of, row.symbol))
    validate(results, datetime.fromisoformat(spec.end+"T23:59:59+00:00"))
    return results
