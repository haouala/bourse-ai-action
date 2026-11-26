"""Financial backtest at non-overlapping realized checkpoints."""
from itertools import groupby

import numpy as np
from sklearn.metrics import brier_score_loss, log_loss, roc_auc_score


def simulate_cohorts(rows, probabilities, threshold=.60):
    """Allocate equally across selected assets per cohort, then hold cash until exit."""
    equity = benchmark = peak = 1.
    drawdown = 0.
    prior_exit = None
    checkpoints = []
    pairs = sorted(zip(rows, probabilities, strict=True), key=lambda pair: (pair[0].day, pair[0].symbol))
    for day, group in groupby(pairs, key=lambda pair: pair[0].day):
        cohort = list(group)
        decision_at = max(row.as_of for row, _ in cohort)
        if prior_exit is not None and decision_at < prior_exit:
            continue
        eligible = [(row, p) for row, p in cohort if row.entry_at > decision_at]
        if not eligible:
            continue
        picks = [row.net_return for row, probability in eligible if probability >= threshold]
        base = [row.net_return for row, _ in eligible]
        prior_exit = max(row.label_at for row, _ in eligible)
        equity *= 1 + (sum(picks) / len(picks) if picks else 0)
        benchmark *= 1 + sum(base) / len(base)
        peak = max(peak, equity)
        drawdown = max(drawdown, 1 - equity / peak)
        checkpoints.append({"decisionDate": day.isoformat(), "exitAt": prior_exit.isoformat(),
                            "selected": len(picks), "eligible": len(base),
                            "equity": equity, "benchmarkEquity": benchmark})
    return {"threshold": threshold, "cohorts": len(checkpoints), "netReturn": equity - 1,
            "benchmarkNetReturn": benchmark - 1, "maxRealizedDrawdown": drawdown,
            "checkpoints": checkpoints}


def evaluate_predictions(rows, probabilities, prevalence):
    y = np.asarray([row.net_return > 0 for row in rows], dtype=int)
    probabilities = np.asarray(probabilities)
    bins = []
    for i in range(5):
        low, high = i / 5, (i + 1) / 5
        mask = (probabilities >= low) & ((probabilities < high) if i < 4 else (probabilities <= high))
        if mask.any():
            bins.append({"from": low, "to": high, "count": int(mask.sum()),
                         "estimate": float(probabilities[mask].mean()), "observed": float(y[mask].mean())})
    currencies = {}
    for currency in sorted({row.currency for row in rows}):
        mask = np.asarray([row.currency == currency for row in rows])
        subset = [row for row in rows if row.currency == currency]
        currencies[currency] = simulate_cohorts(subset, probabilities[mask])
    return {"brier": float(brier_score_loss(y, probabilities)),
            "constantBrier": float(brier_score_loss(y, np.full(len(y), prevalence))),
            "logLoss": float(log_loss(y, probabilities, labels=[0, 1])),
            "auc": float(roc_auc_score(y, probabilities)) if len(set(y)) > 1 else None,
            "calibration": bins, "strategiesByCurrency": currencies}
