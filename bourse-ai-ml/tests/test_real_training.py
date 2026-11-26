from dataclasses import replace
from datetime import datetime, timedelta, timezone

import pytest

from bourse_ai_ml.market import Event, MarketDataset
from bourse_ai_ml.real_training import chronological_partition, train_market
from bourse_ai_ml.market import PRICE_FEATURES
from bourse_ai_ml.technical import FEATURES as TECHNICAL_FEATURES


def example():
    start = datetime(2024, 1, 1, 20, tzinfo=timezone.utc)
    events = []
    for day in range(220):
        at = start + timedelta(days=day)
        for n, symbol in enumerate(("NVDA", "MSFT", "AAPL")):
            value = ((day * 7 + n * 11) % 17 - 8) / 100
            events.append(Event(symbol, "USD", "TEST", at.date(), at, (value, .02, .1, value / 2),
                                at + timedelta(hours=18), at + timedelta(days=1), value))
    for i in range(-3, 0):
        events[i] = replace(events[i], entry_at=None, label_at=None, net_return=None)
    return MarketDataset(events, [{"symbol": s, "lastExpectedDate": events[-1].day.isoformat()}
                                  for s in ("NVDA", "MSFT", "AAPL")])


def test_boundaries_purge_future_outcomes_and_group_session_dates():
    events = example().events
    a, b = events[300].day, events[450].day
    train, validation, test = chronological_partition(events, a, b)
    assert max(r.label_at.date() for r in train) < min(r.day for r in validation)
    assert max(r.label_at.date() for r in validation) < min(r.day for r in test)
    assert len(train) % 3 == len(validation) % 3 == len(test) % 3 == 0


def test_fits_both_families_and_emits_only_latest_unlabelled_signals():
    result = train_market(example())
    assert set(result["validationBrier"]) == {"LOGISTIC", "HIST_GRADIENT_BOOSTING"}
    assert result["selectedModel"] == min(result["validationBrier"], key=result["validationBrier"].get)
    assert {r["symbol"] for r in result["predictions"]} == {"NVDA", "MSFT", "AAPL"}
    assert all(0 <= r["estimate"] <= 1 for r in result["predictions"])
    assert len(result["walkForward"]) == 3
    assert result["walkForward"][-1]["status"] == "EVALUATED"
    assert "USD" in result["walkForward"][-1]["strategiesByCurrency"]


def enriched_example():
    base=example()
    # Explicit synthetic relationship proves that the extra variables are consumed, not real trading efficacy.
    extra=[0.]*len(TECHNICAL_FEATURES)
    extra[-1]=1.
    events=[]
    for e in base.events:
        values=extra.copy();values[0]=float(e.features[0] > 0)
        values[6]=None  # Missing volume must be imputed only on training, not silently fabricated by the adapter.
        events.append(replace(e,features=(0.,0.,0.,0.)+tuple(values)))
    return MarketDataset(events,base.coverage,PRICE_FEATURES+TECHNICAL_FEATURES)


def test_patterns_are_actually_used_and_compared_to_prices_only_on_reserved_data():
    result=train_market(enriched_example())
    assert result["selectedFeatureSet"] == "TECHNICAL_ENRICHED"
    assert "hammer" in result["features"]
    assert result["technicalStatus"] == "COMPARED"
    assert {c["featureSet"] for c in result["featureComparison"]} == {"PRICE_ONLY","TECHNICAL_ENRICHED"}
    assert result["featureSelectionPolicy"] == "MIN_VALIDATION_BRIER_TEST_RESERVED"
    assert all("validationBrier" in c and "testBrier" in c for c in result["featureComparison"])


def test_changing_test_outcomes_cannot_select_another_feature_set_or_change_live_estimates():
    data=enriched_example();before=train_market(data)
    boundary=datetime.fromisoformat(before["testStart"]).date()
    changed=replace(data,events=[replace(e,net_return=-e.net_return-.00001)
                    if e.day>=boundary and e.net_return is not None else e for e in data.events])
    after=train_market(changed)
    assert (before["selectedFeatureSet"],before["selectedModel"]) == (after["selectedFeatureSet"],after["selectedModel"])
    assert before["validationBrier"] == after["validationBrier"]
    assert before["predictions"] == after["predictions"]
