from dataclasses import replace
from datetime import datetime
import numpy as np
import pytest
from bourse_ai_ml.demo import generate, stress_tag
from bourse_ai_ml.domain import DatasetSpec, FEATURES, DataError, utc, validate, canonical
from bourse_ai_ml.training import FOLDS, partition, fit_fold, predict_json, metrics, train


def test_feature_configuration_changes_run_identity_but_not_dataset():
    from bourse_ai_ml.training import training_config
    from bourse_ai_ml.domain import digest
    assert digest(training_config("PRICE_ONLY")) != digest(training_config("ENRICHED"))
    with pytest.raises(DataError): training_config("UNKNOWN")


def test_price_only_model_ignores_absent_news_and_fundamentals(rows):
    missing = [replace(r,features=(r.features[0],None,None,r.features[3],r.features[4])) for r in rows]
    a = fit_fold(rows,FOLDS[0],"PRICE_ONLY")
    b = fit_fold(missing,FOLDS[0],"PRICE_ONLY")
    assert a == b
    assert a['model']['features'] == ['technical','market_momentum','volatility']
    assert len(a['model']['coefficients']) == 3
    assert np.allclose(predict_json(b['model'],missing[:20]),predict_json(b['model'],rows[:20]))
    with pytest.raises(DataError): fit_fold(missing,FOLDS[0],"ENRICHED")


@pytest.fixture(scope="module")
def rows():
    return generate(DatasetSpec(assets=2))


def test_fixture_is_deterministic_and_has_no_real_symbols(rows):
    assert rows == generate(DatasetSpec(assets=2))
    assert {r.symbol for r in rows} == {"DEMO_001", "DEMO_002"}


def test_extending_the_future_cannot_rewrite_past_features_or_outcomes():
    short = generate(DatasetSpec(assets=1, end="2006-12-31"))
    longer = {(r.symbol, r.as_of): r for r in generate(DatasetSpec(assets=1, end="2007-12-31"))}
    assert all(r == longer[r.symbol, r.as_of] for r in short)


@pytest.mark.parametrize("horizon,count", [("DAY",1), ("MONTH",21), ("YEAR",252)])
def test_all_horizons_enter_next_session_and_end_after_given_weekdays(horizon,count):
    rows = generate(DatasetSpec(assets=1, horizon=horizon, end="2007-12-31"))
    r = rows[0]
    assert r.as_of < r.entry_at <= r.label_at
    assert np.busday_count(r.as_of.date(), r.label_at.date()) == count


def test_cost_is_charged_once_on_forward_outcome():
    cheap = generate(DatasetSpec(assets=1, end="2006-12-31", cost_bps=0))
    costly = generate(DatasetSpec(assets=1, end="2006-12-31", cost_bps=25))
    assert costly[0].net_return == pytest.approx(cheap[0].net_return-.0025)
    assert costly[0].features == cheap[0].features


def test_crisis_labels_are_evaluation_only(rows):
    assert "DEMO_FINANCIAL_2008" in {r.stress_tag for r in rows}
    assert "DEMO_COVID_2020" in {r.stress_tag for r in rows}
    assert "DEMO_GEOPOLITICAL_2022" in {r.stress_tag for r in rows}
    assert len(FEATURES) == 5
    assert not any("crisis" in x or "stress" in x or "year" in x for x in FEATURES)
    assert stress_tag("2005-02-01") == "DEMO_NORMAL"


@pytest.mark.parametrize("kwargs", [{"assets":0},{"assets":101},{"horizon":"5m"},{"cost_bps":float('nan')},{"seed":-1}])
def test_invalid_generation_settings_fail(kwargs):
    with pytest.raises(DataError): DatasetSpec(**kwargs)


@pytest.mark.parametrize("change", [
    {"features": (1.,float('nan'),0.,0.,0.)},
    {"feature_available_at": utc("2027-01-01T00:00:00Z")},
    {"symbol": "NVDA"}, {"entry_price": 0}, {"as_of": datetime(2000,1,1)}])
def test_invalid_or_future_data_fail(rows,change):
    with pytest.raises(DataError): validate([replace(rows[0],**change)],utc("2026-01-01T00:00:00Z"))


def test_duplicate_observations_are_not_counted_twice(rows):
    with pytest.raises(DataError): validate([rows[0],rows[0]],utc("2026-01-01T00:00:00Z"))


def test_future_outcomes_cannot_enter_earlier_split(rows):
    train,val,test=partition(rows,FOLDS[0])
    assert max(r.label_at for r in train)<min(r.as_of for r in val)
    assert max(r.label_at for r in val)<min(r.as_of for r in test)
    for part in (train,val,test):
        groups={r.as_of: set() for r in part}
        for r in part: groups[r.as_of].add(r.symbol)
        assert all(len(symbols)==2 for symbols in groups.values())


def test_year_horizon_is_purged_by_label_date_not_fixed_row_gap():
    rows=generate(DatasetSpec(assets=1,horizon="YEAR"))
    train,val,test=partition(rows,FOLDS[1])
    assert max(r.label_at for r in train)<utc("2018-01-01T00:00:00Z")
    assert max(r.label_at for r in val)<utc("2020-01-01T00:00:00Z")
    assert max(r.label_at for r in test)<utc("2022-01-01T00:00:00Z")


def test_json_model_matches_predictions_and_scaler_uses_training_only(rows):
    result=fit_fold(rows,FOLDS[0]);model=result['model']
    tr,_,te=partition(rows,FOLDS[0])
    assert np.allclose(model['mean'],np.mean([r.features for r in tr],axis=0))
    assert np.allclose(predict_json(model,te),[p['estimate'] for p in result['predictions']])
    assert model['promotion_allowed'] is False and model['status']=='DEMO_ONLY'
    canonical(result)  # Rejects NaN/Infinity and non-portable JSON.


def test_changing_test_outcomes_cannot_change_selected_weights(rows):
    original=fit_fold(rows,FOLDS[0])
    changed=[replace(r,net_return=-r.net_return) if r.as_of>=utc('2008-01-01T00:00:00Z') else r for r in rows]
    other=fit_fold(changed,FOLDS[0])
    assert original['model']==other['model']


def test_empty_prediction_bins_are_null_and_returns_are_not_compounded(rows):
    result=metrics(rows[:100],np.full(100,.5),.5)
    assert result['model_selection']['count']==0
    assert result['model_selection']['mean_net_return'] is None
    assert result['reliability_bins'][0]['mean_estimate'] is None
    assert 'sharpe' not in result and 'portfolio_pnl' not in result


@pytest.mark.parametrize('horizon',['DAY','YEAR'])
def test_full_walk_forward_supports_other_horizons(horizon):
    result=train(generate(DatasetSpec(assets=1,horizon=horizon)))
    assert len(result['folds'])==4 and result['promotion_allowed'] is False
    assert all(f['metrics']['rows']>30 for f in result['folds'])
