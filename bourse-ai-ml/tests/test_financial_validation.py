from datetime import datetime, timedelta, timezone

import pytest

from bourse_ai_ml.financial_validation import simulate_cohorts
from bourse_ai_ml.market import Event


def test_strategy_uses_non_overlapping_cohorts_and_measures_realized_drawdown():
    start = datetime(2025, 1, 1, 20, tzinfo=timezone.utc)
    def event(offset, ret, symbol="NVDA"):
        at = start + timedelta(days=offset)
        return Event(symbol, "USD", "FIXTURE", at.date(), at, (0., 0., 0., 0.),
                     at + timedelta(hours=16), at + timedelta(days=2), ret)
    rows = [event(0, .10), event(0, 0., "MSFT"), event(1, .99), event(2, -.10)]
    result = simulate_cohorts(rows, [.8, .2, .9, .9])
    assert result["cohorts"] == 2
    assert [r["selected"] for r in result["checkpoints"]] == [1, 1]
    assert result["netReturn"] == pytest.approx(-.01)
    assert result["benchmarkNetReturn"] == pytest.approx(-.055)
    assert result["maxRealizedDrawdown"] == pytest.approx(.10)
