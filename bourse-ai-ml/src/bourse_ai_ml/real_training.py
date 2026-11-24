"""Fit research classifiers using dated market features and mature net-return labels."""
from dataclasses import asdict
from importlib.metadata import version

import numpy as np
from sklearn.ensemble import HistGradientBoostingClassifier
from sklearn.linear_model import LogisticRegression
from sklearn.metrics import brier_score_loss
from sklearn.pipeline import make_pipeline
from sklearn.preprocessing import StandardScaler

from .domain import DataError, digest
from .market import PRICE_FEATURES

VERSION = "market-classifiers-v1"


def chronological_partition(events, validation_day, test_day, end_day=None):
    """Keep all symbols on a session date together and purge labels at each boundary."""
    train, validation, test = [], [], []
    for event in events:
        if event.label_at is None:
            continue
        label_day = event.label_at.date()
        if event.day < validation_day and label_day < validation_day:
            train.append(event)
        elif validation_day <= event.day < test_day and label_day < test_day:
            validation.append(event)
        elif event.day >= test_day and (end_day is None or label_day < end_day):
            test.append(event)
    if any(len({r.day for r in part}) < 15 or len(part) < 30 for part in (train, validation, test)):
        raise DataError("Historique insuffisant après purge : chaque période exige 15 dates et 30 observations")
    return train, validation, test


def matrix(rows):
    return np.asarray([r.features for r in rows], dtype=float)


def targets(rows):
    return np.asarray([r.net_return > 0 for r in rows], dtype=int)


def fit_candidates(train, validation):
    x, y = matrix(train), targets(train)
    vx, vy = matrix(validation), targets(validation)
    if len(set(y)) < 2:
        raise DataError("L’entraînement exige des rendements nets positifs et non positifs")
    estimators = {
        "LOGISTIC": make_pipeline(StandardScaler(), LogisticRegression(max_iter=1000, C=1.0)),
        "HIST_GRADIENT_BOOSTING": HistGradientBoostingClassifier(
            max_iter=100, max_leaf_nodes=15, min_samples_leaf=20,
            l2_regularization=1.0, early_stopping=False, random_state=42),
    }
    scores = {}
    for name, model in estimators.items():
        model.fit(x, y)
        scores[name] = float(brier_score_loss(vy, model.predict_proba(vx)[:, 1]))
    return estimators, scores, float(y.mean())


def train_market(dataset):
    dates = sorted({e.day for e in dataset.events if e.label_at is not None})
    if len(dates) < 75:
        raise DataError("Au moins 75 dates labellisées sont nécessaires")
    validation_day, test_day = dates[len(dates) * 3//5], dates[len(dates) * 4//5]
    train, validation, test = chronological_partition(dataset.events, validation_day, test_day)
    models, scores, prevalence = fit_candidates(train, validation)
    selected = min(scores, key=scores.get)
    latest = {}
    expected = {r["symbol"]: r["lastExpectedDate"] for r in dataset.coverage}
    for event in dataset.events:
        if event.day.isoformat() == expected[event.symbol] and event.net_return is None:
            latest[event.symbol] = event
    live = list(latest.values())
    probabilities = models[selected].predict_proba(matrix(live))[:, 1] if live else []
    return {
        "features": list(PRICE_FEATURES),
        "selectedModel": selected,
        "validationBrier": scores,
        "testBrier": {name: float(brier_score_loss(targets(test), m.predict_proba(matrix(test))[:, 1]))
                      for name, m in models.items()},
        "constantBrier": float(brier_score_loss(targets(test), np.full(len(test), prevalence))),
        "trainingCount": len(train), "validationCount": len(validation), "testCount": len(test),
        "validationStart": validation_day.isoformat(), "testStart": test_day.isoformat(),
        "predictions": [{"symbol": r.symbol, "currency": r.currency, "provider": r.provider,
                         "asOf": r.as_of.isoformat(), "estimate": float(p)}
                        for r, p in zip(live, probabilities, strict=True)],
    }


def fingerprint(dataset, settings):
    # Serialize in sorted dataset order, using explicit ISO timestamps.
    features = [(r.symbol, r.currency, r.provider, r.day.isoformat(), r.as_of.isoformat(), r.features,
                 r.entry_at.isoformat() if r.entry_at else None, r.label_at.isoformat() if r.label_at else None,
                 r.net_return) for r in dataset.events]
    config = {"trainer": VERSION, "numpy": version("numpy"), "sklearn": version("scikit-learn"),
              "scipy": version("scipy"), **settings}
    return digest({"config": config, "events": features, "coverage": dataset.coverage})
