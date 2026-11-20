"""Date-grouped, label-purged walk-forward evaluation; no live activation."""
from dataclasses import dataclass, asdict
import importlib.metadata
import numpy as np
from sklearn.linear_model import LogisticRegression
from sklearn.metrics import brier_score_loss, log_loss, roc_auc_score
from sklearn.pipeline import make_pipeline
from sklearn.preprocessing import StandardScaler
from .domain import FEATURES, SOURCE, TRAINER_VERSION, Observation, DataError, utc


@dataclass(frozen=True)
class Fold:
    name: str
    validation_start: str
    test_start: str
    test_end: str  # exclusive


FOLDS = (
    Fold("financial_2008", "2006-01-01", "2008-01-01", "2010-01-01"),
    Fold("covid_2020", "2018-01-01", "2020-01-01", "2022-01-01"),
    Fold("geopolitical_2022", "2020-01-01", "2022-01-01", "2024-01-01"),
    Fold("final_holdout", "2022-01-01", "2024-01-01", "2026-01-01"),
)
CS = (.1, 1., 10.)
SELECTION_THRESHOLD = .60


def training_config() -> dict:
    return {"trainer_version": TRAINER_VERSION, "features": list(FEATURES),
            "folds": [asdict(f) for f in FOLDS], "C_grid": list(CS),
            "selection_threshold": SELECTION_THRESHOLD,
            "versions": {n: importlib.metadata.version(n) for n in ("numpy", "scikit-learn", "scipy")}}


def partition(rows: list[Observation], fold: Fold):
    a, b, c = (utc(s+"T00:00:00Z") for s in (fold.validation_start, fold.test_start, fold.test_end))
    if not a < b < c:
        raise DataError("Découpage chronologique invalide")
    train = [r for r in rows if r.as_of < a and r.label_at < a]
    validation = [r for r in rows if a <= r.as_of < b and r.label_at < b]
    test = [r for r in rows if b <= r.as_of < c and r.label_at < c]
    if min(len(train), len(validation), len(test)) < 30:
        raise DataError("Périodes insuffisantes pour ce découpage chronologique")
    return train, validation, test


def arrays(rows):
    return np.array([r.features for r in rows]), np.array([r.is_up for r in rows])


def event_stats(rows, selected):
    chosen = [r for r, flag in zip(rows, selected, strict=True) if flag]
    return {"count": len(chosen), "positive_fraction": float(np.mean([r.is_up for r in chosen])) if chosen else None,
            "mean_net_return": float(np.mean([r.net_return for r in chosen])) if chosen else None,
            "worst_net_return": min((r.net_return for r in chosen), default=None),
            "worst_adverse_close_return": min((r.adverse_close_return for r in chosen), default=None)}


def metrics(rows, probabilities, train_prevalence):
    p = np.asarray(probabilities, dtype=float); _, y = arrays(rows)
    if p.shape != y.shape or not np.isfinite(p).all() or (p < 0).any() or (p > 1).any():
        raise DataError("Probabilités invalides")
    bins = []
    for i in range(5):
        selected = (p >= i/5) & ((p < (i+1)/5) if i < 4 else (p <= 1))
        bins.append({"lower": i/5, "upper": (i+1)/5, "count": int(selected.sum()),
                     "mean_estimate": float(p[selected].mean()) if selected.any() else None,
                     "observed_positive_fraction": float(y[selected].mean()) if selected.any() else None})
    return {"rows": len(rows), "distinct_dates": len({r.as_of for r in rows}),
            "brier": float(brier_score_loss(y, p)),
            "log_loss": float(log_loss(y, np.column_stack((1-p, p)), labels=[0, 1])),
            "auc": float(roc_auc_score(y, p)) if len(set(y)) == 2 else None,
            "train_prevalence_brier": float(brier_score_loss(y, np.full(len(y), train_prevalence))),
            "reliability_bins": bins,
            "model_selection": event_stats(rows, p >= SELECTION_THRESHOLD),
            "heuristic_selection": event_stats(rows, [r.baseline_score >= 60 for r in rows])}


def predict_json(model: dict, rows: list[Observation]) -> np.ndarray:
    if model["features"] != list(FEATURES) or model["source"] != SOURCE:
        raise DataError("Modèle ou composantes incompatibles")
    x, _ = arrays(rows)
    z = (x-np.asarray(model["mean"]))/np.asarray(model["scale"])
    logits = np.clip(z @ np.asarray(model["coefficients"]) + model["intercept"], -700, 700)
    return 1/(1+np.exp(-logits))


def fit_fold(rows: list[Observation], fold: Fold) -> dict:
    train, validation, test = partition(rows, fold)
    x, y = arrays(train); vx, vy = arrays(validation)
    if len(set(y)) < 2:
        raise DataError("L'apprentissage exige des résultats positifs et négatifs")
    best = None
    for c in CS:
        pipeline = make_pipeline(StandardScaler(), LogisticRegression(C=c, max_iter=1000, solver="lbfgs"))
        pipeline.fit(x, y)
        value = float(brier_score_loss(vy, pipeline.predict_proba(vx)[:, 1]))
        if best is None or value < best[0]:
            best = value, c, pipeline
    loss, c, pipeline = best
    scaler, classifier = pipeline.steps[0][1], pipeline.steps[1][1]
    model = {"source": SOURCE, "status": "DEMO_ONLY", "promotion_allowed": False,
             "features": list(FEATURES), "mean": scaler.mean_.tolist(), "scale": scaler.scale_.tolist(),
             "coefficients": classifier.coef_[0].tolist(), "intercept": float(classifier.intercept_[0]),
             "C": c, "validation_brier": loss, "fold": asdict(fold),
             "training_last_outcome": max(r.label_at for r in train).isoformat(),
             "training_rows": len(train), "validation_rows": len(validation),
             "probability_status": "EXPERIMENTAL_SYNTHETIC_ONLY"}
    tx, _ = arrays(test); probabilities = pipeline.predict_proba(tx)[:, 1]
    if not np.allclose(predict_json(model, test), probabilities, atol=1e-12):
        raise DataError("Le modèle JSON ne reproduit pas les prédictions")
    prevalence = float(y.mean())
    stress_results = {}
    for tag in sorted({r.stress_tag for r in test}):
        positions = [i for i, r in enumerate(test) if r.stress_tag == tag]
        stress_results[tag] = metrics([test[i] for i in positions], probabilities[positions], prevalence)
    return {"fold": fold.name, "model": model, "metrics": metrics(test, probabilities, prevalence),
            "stress_results": stress_results,
            "predictions": [{"symbol": r.symbol, "as_of": r.as_of.isoformat(),
                             "label_at": r.label_at.isoformat(), "estimate": float(p),
                             "actual_up": r.is_up, "net_return": r.net_return,
                             "baseline_score": r.baseline_score, "stress_tag": r.stress_tag}
                            for r, p in zip(test, probabilities, strict=True)]}


def train(rows: list[Observation]) -> dict:
    folds = [fit_fold(rows, fold) for fold in FOLDS]
    return {"source": SOURCE, "status": "DEMO_ONLY", "promotion_allowed": False,
            "config": training_config(), "folds": folds,
            "limitations": ["Synthetic data do not validate investment performance",
                "Stress labels are evaluation metadata, never model inputs",
                "Correlated and overlapping events are not independent trials",
                "Event return statistics are not a portfolio equity curve or Sharpe ratio",
                "Daily synthetic weekdays do not implement real exchange calendars",
                "No Java rules, portfolio loss limits or orders are changed"]}
