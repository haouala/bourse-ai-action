"""Fit research classifiers using dated market features and mature net-return labels."""
from importlib.metadata import version

import numpy as np
from sklearn.ensemble import HistGradientBoostingClassifier
from sklearn.linear_model import LogisticRegression
from sklearn.impute import SimpleImputer
from sklearn.metrics import brier_score_loss
from sklearn.pipeline import make_pipeline
from sklearn.preprocessing import StandardScaler

from .domain import DataError, digest
from .market import PRICE_FEATURES
from .financial_validation import evaluate_predictions

VERSION = "market-classifiers-walk-forward-v2-technical"


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


def matrix(rows, columns=None):
    values = np.asarray([r.features for r in rows], dtype=float)
    return values if columns is None else values[:, columns]


def targets(rows):
    return np.asarray([r.net_return > 0 for r in rows], dtype=int)


def fit_candidates(train, validation, columns=None):
    x, y = matrix(train, columns), targets(train)
    vx, vy = matrix(validation, columns), targets(validation)
    if len(set(y)) < 2:
        raise DataError("L’entraînement exige des rendements nets positifs et non positifs")
    estimators = {
        "LOGISTIC": make_pipeline(SimpleImputer(strategy="median", keep_empty_features=True),
                                  StandardScaler(), LogisticRegression(max_iter=1000, C=1.0)),
        "HIST_GRADIENT_BOOSTING": HistGradientBoostingClassifier(
            max_iter=100, max_leaf_nodes=15, min_samples_leaf=20,
            l2_regularization=1.0, early_stopping=False, random_state=42),
    }
    scores = {}
    for name, model in estimators.items():
        model.fit(x, y)
        scores[name] = float(brier_score_loss(vy, model.predict_proba(vx)[:, 1]))
    return estimators, scores, float(y.mean())


def select_candidates(train, validation, feature_names):
    """Select both feature set and estimator using validation only; test outcomes never choose them."""
    if any(len(r.features) != len(feature_names) for r in train+validation):
        raise DataError("Schéma de variables incompatible avec les observations")
    sets = {"PRICE_ONLY": list(range(len(PRICE_FEATURES)))}
    if len(feature_names) > len(PRICE_FEATURES) and any(r.features[-1] == 1. for r in train):
        sets["TECHNICAL_ENRICHED"] = list(range(len(feature_names)))
    candidates, validation_scores = {}, {}
    prior = None
    for name, columns in sets.items():
        models, scores, prior = fit_candidates(train, validation, columns)
        candidates[name] = models
        validation_scores[name] = scores
    feature_set, model = min(((s, m) for s in sets for m in candidates[s]),
                             key=lambda pair: validation_scores[pair[0]][pair[1]])
    return sets, candidates, validation_scores, prior, feature_set, model


def train_market(dataset):
    dates = sorted({e.day for e in dataset.events if e.label_at is not None})
    if len(dates) < 75:
        raise DataError("Au moins 75 dates labellisées sont nécessaires")
    validation_day, test_day = dates[len(dates) * 3//5], dates[len(dates) * 4//5]
    train, validation, test = chronological_partition(dataset.events, validation_day, test_day)
    sets, candidates, feature_scores, prevalence, feature_set, selected = select_candidates(train, validation, dataset.feature_names)
    columns = sets[feature_set]
    models, scores = candidates[feature_set], feature_scores[feature_set]
    tests = []
    cut = lambda fraction: dates[len(dates) * fraction // 10]
    for val_start, test_start, test_end in ((cut(4), cut(5), cut(6)), (cut(5), cut(6), test_day)):
        period = {"validationStart": val_start.isoformat(), "testStart": test_start.isoformat(),
                  "testEndExclusive": test_end.isoformat()}
        try:
            t, v, oos = chronological_partition(dataset.events, val_start, test_start, test_end)
            fold_sets, fold_candidates, val_scores, prior, fold_set, name = select_candidates(t, v, dataset.feature_names)
        except DataError as exc:
            tests.append({**period, "status": "INSUFFICIENT_EVIDENCE", "reason": str(exc)})
            continue
        tests.append({**period, "status": "EVALUATED", "model": name, "testCount": len(oos),
                      "featureSet": fold_set,
                      **evaluate_predictions(oos, fold_candidates[fold_set][name].predict_proba(matrix(oos, fold_sets[fold_set]))[:, 1], prior)})
    holdout = evaluate_predictions(test, models[selected].predict_proba(matrix(test, columns))[:, 1], prevalence)
    tests.append({"validationStart": validation_day.isoformat(), "testStart": test_day.isoformat(),
                  "status": "EVALUATED", "model": selected, "featureSet": feature_set, "testCount": len(test), **holdout})
    latest = {}
    expected = {r["symbol"]: r["lastExpectedDate"] for r in dataset.coverage}
    for event in dataset.events:
        if event.day.isoformat() == expected[event.symbol] and event.net_return is None:
            latest[event.symbol] = event
    live = list(latest.values())
    probabilities = models[selected].predict_proba(matrix(live, columns))[:, 1] if live else []
    comparison = []
    for candidate_set, candidate_models in candidates.items():
        name = min(feature_scores[candidate_set], key=feature_scores[candidate_set].get)
        estimate = candidate_models[name].predict_proba(matrix(test, sets[candidate_set]))[:, 1]
        comparison.append({"featureSet": candidate_set, "model": name,
                           "features": [dataset.feature_names[i] for i in sets[candidate_set]],
                           "validationBrier": feature_scores[candidate_set][name],
                           "testBrier": float(brier_score_loss(targets(test), estimate))})
    return {
        "trainerVersion": VERSION,
        "features": [dataset.feature_names[i] for i in columns],
        "candidateFeatures": list(dataset.feature_names),
        "selectedFeatureSet": feature_set,
        "featureComparison": comparison,
        "featureSelectionPolicy": "MIN_VALIDATION_BRIER_TEST_RESERVED",
        "technicalStatus": "COMPARED" if len(sets) > 1 else "OHLCV_UNAVAILABLE_OR_LEGACY_DATASET",
        "selectedModel": selected,
        "validationStatus": ("ABOVE_CONSTANT_ON_HOLDOUT" if holdout["brier"] < holdout["constantBrier"]
                             else "CONSTANT_NOT_BEATEN"),
        "walkForward": tests,
        "validationBrier": scores,
        "testBrier": {name: float(brier_score_loss(targets(test), m.predict_proba(matrix(test, columns))[:, 1]))
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
    return digest({"config": config, "featureNames": dataset.feature_names, "events": features, "coverage": dataset.coverage})
