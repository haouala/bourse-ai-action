import json
from pathlib import Path
from types import SimpleNamespace

import pytest
from bourse_ai_ml.technical import FEATURES, VERSION, features


def fixtures():
    return json.loads((Path(__file__).resolve().parents[2]/"contracts/technical-features-v1.json").read_text())


def rows(case):
    return [SimpleNamespace(open_price=b[0], high_price=b[1], low_price=b[2], close_price=b[3], volume=b[4])
            for b in case["bars"]]


def test_shared_java_python_contract_covers_every_feature():
    root=fixtures()
    assert root["method"] == VERSION
    assert tuple(root["features"]) == FEATURES
    for case in root["cases"]:
        got=dict(zip(FEATURES, features(rows(case)), strict=True))
        for name, expected in case["expectedFeatures"].items():
            if expected is None:
                assert got[name] is None, (case["name"], name)
            else:
                assert got[name] == pytest.approx(expected, abs=1e-9), (case["name"], name)


def test_elliott_cannot_be_used_at_the_pivot_before_confirmation():
    case=next(c for c in fixtures()["cases"] if c["name"] == "elliott_abc_up")
    data=rows(case)
    assert dict(zip(FEATURES, features(data[:-1])))["elliott_abc"] == 0.
    assert dict(zip(FEATURES, features(data)))["elliott_abc"] == 1.


def test_later_prices_do_not_change_features_computed_at_an_earlier_cutoff():
    case=next(c for c in fixtures()["cases"] if c["name"] == "triple_bottom")
    data=rows(case)
    old=features(data[:26])
    data[-1]=SimpleNamespace(open_price=1000., high_price=1001., low_price=999., close_price=1000., volume=100)
    assert features(data[:26]) == old


def test_missing_ohlc_remains_missing_instead_of_becoming_no_pattern():
    case=next(c for c in fixtures()["cases"] if c["name"] == "missing_gap")
    result=dict(zip(FEATURES, features(rows(case))))
    assert result["ohlc_available"] == 0.
    assert result["hammer"] is None
    assert result["relative_volume_20"] is None
