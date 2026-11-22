# Module 7 — Machine Learning Direction Engine (baseline research)

First research-grade model built directly in **Java 21**. It predicts a binary
*direction* on historical holdout samples. It **does not** produce future stock
prices, a forecast chart, a calibrated confidence level, trade orders, or a
claim that a model will outperform the market.

## Endpoints

```bash
# Historical daily candles must already exist (>= 240).
curl -X POST 'http://localhost:8081/api/v1/ml/train/NVDA?horizonSessions=10'
curl 'http://localhost:8081/api/v1/ml/history/NVDA?limit=20'
curl 'http://localhost:8081/api/v1/ml/runs/1'
```

`horizonSessions` accepts 5, 10, 20 *market observations*; these are not
the 7/30/90/180 **calendar-day** horizons of Prediction Tracking (module 3).

The POST computes model evaluation **synchronously**, then records the model
version, feature specification, training cutoff, test count, accuracy and Brier
scores in Flyway V9 `ml_training_runs`. It has no background scheduling.

## Features and evaluation

- Daily log returns over the preceding 5 and 20 closes; 20-day realized
  volatility estimated using only closes known by that feature's date.
- Binary outcome: whether close at +h market observations exceeds the close at
  the feature timestamp. No future outcome is an input feature.
- Logistic regression with L2 regularization; deterministic full-batch
  gradient descent with fixed hyperparameters.
- Three **expanding** chronological training windows at 55%, 70%, 85% of
  price history. Each training example's outcome date must occur **strictly
  before** that fold's test start; this removes overlapping-label leakage.
- Fit feature normalization **only on training samples** in every fold.
- Subsequent disjoint holdout windows are evaluated once, without using holdout
  outcomes for training that fold's model.
- Compare accuracy and Brier loss against a constant probability equal to
  training prevalence for that fold. The baseline is not a price trading strategy.
- `BASELINE_NOT_BEATEN` explicitly identifies tests where the ML model does
  not beat this baseline on both reported metrics.
- Requires >=240 known daily closes and valid OHLCV (Module 1 quality gate).
  Works off imported canonical prices; no third-party API credits are spent.

## Limits and next steps

- Three price-only features. **News, insider activity, financial fundamentals
  and linked-company signals are not yet encoded as point-in-time ML features.**
  They require timestamp-safe joins and feature-availability auditing.
- Fixed train/test folds are exploratory validation, **not** a production
  signal or final forward test on untouched data. No hyperparameter search,
  probabilistic calibration, benchmark price returns, confidence intervals,
  drift monitoring, or separate model artifact registry yet.
- Scores are evaluation summaries, not guaranteed win probabilities.
- Split/dividend-adjusted historical series and delisted symbol coverage must
  be audited; stale price warnings cannot be mistaken for data quality success.
- Protect `POST /train` using authentication, authorization, quotas and an
  asynchronous job queue before internet-facing deployment.
- Training snapshots are stored as *metrics*, not trained model coefficients.
  Connecting the model to later future-price forecasting requires a separate
  model-versioned artifact/weights persistence design.

## Tests

```bash
cd bourse-ai-engine
mvn -B -ntp clean verify
```
