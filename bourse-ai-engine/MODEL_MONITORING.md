# Module 18 — Model Monitoring

`GET /api/v1/model-monitoring/NVDA` groups audited Module 7 training runs by model **version and forecast horizon**. It compares only identical model versions/horizons and returns changes in holdout accuracy (percentage points) and Brier loss, as well as Module 3's historically settled price-forecast outcome metrics.

Statuses include `NO_TRAINING_RUNS`, `INSUFFICIENT_SAMPLE` (latest holdout <30), `STALE_MODEL` (latest training older than 45 days), `REVIEW_DRIFT` (accuracy drop >=10 percentage points, Brier deterioration >=0.05 or baseline not beaten), and `RESEARCH_ONLY`.

**Limitations:** This is experiment-to-experiment diagnostic drift, not true deployment feature-drift detection; Module 7 stores metrics but not reusable trained model weights. The endpoint triggers no retraining or trades. Comparisons can be noisy because different test periods have different difficulty. No notifications are enabled. Historical profit cannot be inferred from classification accuracy.

Test: `mvn -B -ntp clean verify` from `bourse-ai-engine`.
