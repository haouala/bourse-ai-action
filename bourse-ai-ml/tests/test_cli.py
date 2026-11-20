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
