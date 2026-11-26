from dataclasses import replace
import os
import uuid
import psycopg
import pytest
from bourse_ai_ml import repository as db
from bourse_ai_ml import batch
from bourse_ai_ml.demo import generate
from bourse_ai_ml.domain import DatasetSpec, DataError, digest
from bourse_ai_ml.training import training_config

pytestmark = pytest.mark.postgres


@pytest.fixture(scope="module")
def tiny():
    spec=DatasetSpec(assets=1,end="2005-12-31")
    return spec,generate(spec)


def counts(conn):
    return [conn.execute(f'SELECT count(*) AS n FROM bourse_ml.{name}').fetchone()['n']
            for name in ('datasets','observations','runs','models','predictions')]


def test_migration_is_idempotent_and_checksum_guarded(connection):
    db.initialize(connection)
    assert connection.execute('SELECT count(*) AS n FROM bourse_ml.schema_migrations').fetchone()['n']==1
    connection.execute("UPDATE bourse_ml.schema_migrations SET checksum='changed'")
    with pytest.raises(db.DatasetConflict): db.initialize(connection)
    assert connection.execute("SELECT count(*) AS n FROM information_schema.tables WHERE table_schema='bourse_ml'").fetchone()['n']==6


def test_seed_is_idempotent_and_roundtrips_all_inputs(connection,tiny):
    spec,rows=tiny
    assert db.seed(connection,spec,rows)
    before=counts(connection)
    assert not db.seed(connection,spec,rows)
    assert counts(connection)==before
    assert db.load(connection,spec.dataset_id)==rows


def test_corruption_is_detected_instead_of_rewriting_history(connection,tiny):
    spec,rows=tiny;db.seed(connection,spec,rows)
    connection.execute('UPDATE bourse_ml.observations SET baseline_score=99 WHERE dataset_id=%s AND as_of=%s',(spec.dataset_id,rows[0].as_of))
    with pytest.raises(db.DatasetConflict): db.load(connection,spec.dataset_id)


def test_partial_dataset_is_not_silently_reseeded(connection,tiny):
    spec,rows=tiny;db.seed(connection,spec,rows)
    connection.execute('DELETE FROM bourse_ml.observations WHERE dataset_id=%s AND as_of=%s',(spec.dataset_id,rows[0].as_of))
    with pytest.raises(db.DatasetConflict): db.seed(connection,spec,rows)


def test_failed_insert_rolls_back_dataset_and_observations(connection,tiny):
    spec,rows=tiny
    bad=[replace(rows[0],symbol="DEMO_001';DROP TABLE bourse_ml.datasets;--")]+rows[1:]
    with pytest.raises(psycopg.errors.CheckViolation): db.seed(connection,spec,bad)
    assert counts(connection)==[0,0,0,0,0]


def test_database_rejects_future_feature_availability(connection,tiny):
    spec,rows=tiny;db.seed(connection,spec,rows)
    with pytest.raises(psycopg.errors.CheckViolation):
        connection.execute("UPDATE bourse_ml.observations SET feature_available_at=label_at WHERE dataset_id=%s",(spec.dataset_id,))


def test_same_run_is_locked_across_process_connections(connection):
    with db.connect(os.environ['BOURSE_ML_TEST_DSN']) as other:
        run=uuid.uuid4()
        with db.run_lock(connection,run):
            with pytest.raises(db.BatchBusy):
                with db.run_lock(other,run): pass
        with db.run_lock(other,run): pass


def test_full_batch_is_idempotent_and_never_retrains_success(connection,monkeypatch):
    spec=DatasetSpec(assets=2)
    result=batch.execute(connection,spec)
    assert result['status']=='SUCCEEDED' and not result['promotion_allowed']
    before=counts(connection)
    assert before[2:4]==[1,4] and before[4]>100
    monkeypatch.setattr(batch,'train',lambda _:pytest.fail('Un calcul réussi ne doit pas être réentraîné'))
    again=batch.execute(connection,spec)
    assert again['reused'] and again['run_id']==result['run_id']
    assert counts(connection)==before
    assert connection.execute('SELECT DISTINCT status,promotion_allowed FROM bourse_ml.models').fetchall()==[{'status':'DEMO_ONLY','promotion_allowed':False}]


def test_output_failure_rolls_back_all_models_and_can_recover(connection,monkeypatch):
    spec=DatasetSpec(assets=2,seed=99)
    original=db.save_result
    def fail_after_first_model(conn,run_id,dataset_id,summary,results):
        results[1]['model']['promotion_allowed']=True
        return original(conn,run_id,dataset_id,summary,results)
    monkeypatch.setattr(db,'save_result',fail_after_first_model)
    with pytest.raises(DataError): batch.execute(connection,spec)
    assert counts(connection)[2:]==[1,0,0]
    assert connection.execute('SELECT status,error_code FROM bourse_ml.runs').fetchone()=={'status':'FAILED','error_code':'BATCH_FAILED'}
    monkeypatch.setattr(db,'save_result',original)
    result=batch.execute(connection,spec)
    assert result['status']=='SUCCEEDED'
    assert connection.execute('SELECT attempts FROM bourse_ml.runs').fetchone()['attempts']==2


def test_price_only_and_enriched_share_dataset_but_never_reuse_each_others_models(connection):
    spec=DatasetSpec(assets=1,seed=307)
    enriched=batch.execute(connection,spec)
    price=batch.execute(connection,spec,"PRICE_ONLY")
    assert enriched['dataset_id']==price['dataset_id']
    assert enriched['run_id']!=price['run_id']
    assert price['config']['features']==['technical','market_momentum','volatility']
    assert price['folds'][0]['metrics']['heuristic_selection'] is None
    assert batch.execute(connection,spec,"PRICE_ONLY")['reused'] is True


def test_interrupted_run_without_outputs_is_recovered(connection):
    spec=DatasetSpec(assets=2,seed=55)
    db.seed(connection,spec,generate(spec));config=training_config()
    run=uuid.uuid5(uuid.NAMESPACE_URL,'bourse-ai-ml:'+spec.dataset_id+':'+digest(config))
    db.begin_run(connection,run,spec.dataset_id,config)
    result=batch.execute(connection,spec)
    assert result['status']=='SUCCEEDED'
    assert connection.execute('SELECT attempts FROM bourse_ml.runs').fetchone()['attempts']==2


def test_batch_can_run_without_any_access_to_backend_tables(connection):
    connection.execute('CREATE TABLE IF NOT EXISTS public.ml_backend_sentinel(id integer PRIMARY KEY,value text NOT NULL)')
    connection.execute("INSERT INTO public.ml_backend_sentinel VALUES (1,'unchanged') ON CONFLICT DO NOTHING")
    connection.execute("DO $$ BEGIN IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname='bourse_ml_test_worker') THEN CREATE ROLE bourse_ml_test_worker NOLOGIN; END IF; END $$")
    connection.execute('GRANT USAGE ON SCHEMA bourse_ml TO bourse_ml_test_worker')
    connection.execute('GRANT SELECT,INSERT,UPDATE ON ALL TABLES IN SCHEMA bourse_ml TO bourse_ml_test_worker')
    with db.connect(os.environ['BOURSE_ML_TEST_DSN']) as worker:
        worker.execute('SET ROLE bourse_ml_test_worker')
        with pytest.raises(psycopg.errors.InsufficientPrivilege): worker.execute('SELECT * FROM public.ml_backend_sentinel')
        result=batch.execute(worker,DatasetSpec(assets=2,seed=71))
        assert result['status']=='SUCCEEDED'
    assert connection.execute('SELECT * FROM public.ml_backend_sentinel').fetchall()==[{'id':1,'value':'unchanged'}]


def test_cli_exports_and_reexports_without_retraining(connection,tmp_path,monkeypatch,capsys):
    import csv,hashlib,json
    from bourse_ai_ml import cli
    monkeypatch.setenv('BOURSE_ML_DSN',os.environ['BOURSE_ML_TEST_DSN'])
    assert cli.main(['init-db'])==0;capsys.readouterr()
    assert cli.main(['demo','--assets','2','--seed','12','--output',str(tmp_path)])==0
    result=json.loads(capsys.readouterr().out);directory=tmp_path/result['run_id']
    manifest=json.loads((directory/'complete.json').read_text())
    for name,h in manifest['files'].items(): assert hashlib.sha256((directory/name).read_bytes()).hexdigest()==h
    with (directory/'predictions.csv').open() as stream:
        predictions=list(csv.DictReader(stream))
    assert len(predictions)==result['prediction_count']
    assert json.loads((directory/'models.json').read_text())['promotion_allowed'] is False
    monkeypatch.setattr(batch,'train',lambda _:pytest.fail('Export ne doit pas entraîner'))
    assert cli.main(['report',result['run_id'],'--output',str(tmp_path)])==0
    capsys.readouterr()
    assert cli.main(['status'])==0
    assert json.loads(capsys.readouterr().out)['runs'][0]['status']=='SUCCEEDED'


def test_export_failure_does_not_turn_success_into_failed_run(connection,tmp_path,monkeypatch,capsys):
    from bourse_ai_ml import cli
    monkeypatch.setenv('BOURSE_ML_DSN',os.environ['BOURSE_ML_TEST_DSN'])
    def failed_export(*args): raise PermissionError('private/path secret')
    original=cli.export;monkeypatch.setattr(cli,'export',failed_export)
    assert cli.main(['demo','--assets','2','--seed','33','--output',str(tmp_path)])==2
    assert 'secret' not in capsys.readouterr().out
    run=connection.execute('SELECT * FROM bourse_ml.runs').fetchone()
    assert run['status']=='SUCCEEDED'
    monkeypatch.setattr(cli,'export',original)
    assert cli.main(['report',str(run['run_id']),'--output',str(tmp_path)])==0
    capsys.readouterr()
