from pathlib import Path
import json
import psycopg
from bourse_ai_ml import cli
from bourse_ai_ml.domain import DataError


def test_missing_database_configuration_is_explicit_and_does_not_connect(monkeypatch,capsys):
    monkeypatch.delenv('PGDATABASE',raising=False);monkeypatch.delenv('BOURSE_ML_DSN',raising=False)
    assert cli.main(['status'])==2
    assert json.loads(capsys.readouterr().out)['error']=='INVALID_DATA_OR_CONFIGURATION'


def test_connection_error_does_not_leak_password_or_dsn(monkeypatch,capsys):
    def fail(): raise psycopg.OperationalError('password=SECRET @ server details')
    monkeypatch.setattr(cli.db,'connect',fail)
    assert cli.main(['status'])==2
    output=capsys.readouterr().out
    assert 'SECRET' not in output and 'server details' not in output


def test_invalid_demo_arguments_are_rejected_before_any_database_connection(monkeypatch,capsys):
    monkeypatch.setattr(cli.db,'connect',lambda: (_ for _ in ()).throw(AssertionError('must not connect')))
    assert cli.main(['demo','--assets','101'])==2
    assert json.loads(capsys.readouterr().out)['error']=='INVALID_DATA_OR_CONFIGURATION'


def test_database_dates_are_serializable(capsys):
    from datetime import datetime,timezone
    cli.emit({'date':datetime(2026,1,1,tzinfo=timezone.utc)})
    assert json.loads(capsys.readouterr().out)['date'].endswith('+00:00')


def test_train_requests_repair_before_reading_market_data(monkeypatch, capsys):
    from contextlib import nullcontext
    from io import BytesIO

    calls = []
    preparation = {"missing": 1, "repairs": [{"symbol": "NVDA", "status": "INSERTED"}]}

    def repair(request, timeout):
        calls.append("repair")
        assert request.full_url == "http://localhost:8081/api/v1/orchestrator/repair-daily"
        assert json.loads(request.data) == {
            "symbols": ["NVDA"], "start": "2021-11-24", "asOf": "2026-11-24T23:00:00+00:00",
            "maxRepairs": 2,
        }
        return BytesIO(json.dumps(preparation).encode())

    def train(*args):
        calls.append("train")
        return {"predictions": []}

    monkeypatch.setattr(cli.db, "connect", lambda: nullcontext(object()))
    monkeypatch.setattr(cli, "urlopen", repair)
    monkeypatch.setattr(cli, "execute_market", train)
    assert cli.main([
        "train-market", "--start", "2021-11-24", "--as-of", "2026-11-24T23:00:00Z",
        "--symbols", "NVDA", "--repair-backend", "http://localhost:8081", "--max-repairs", "2",
    ]) == 0
    assert calls == ["repair", "train"]
    assert json.loads(capsys.readouterr().out)["dailyRepair"] == preparation
