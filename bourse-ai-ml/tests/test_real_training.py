from dataclasses import replace
from datetime import datetime, timedelta, timezone

import pytest

from bourse_ai_ml.market import Event, MarketDataset
from bourse_ai_ml.real_training import chronological_partition, train_market


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
