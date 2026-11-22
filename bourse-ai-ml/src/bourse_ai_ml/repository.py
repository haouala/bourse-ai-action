"""Own-schema PostgreSQL persistence; no SQL writes or reads in public."""
from contextlib import contextmanager
from datetime import datetime
from importlib.resources import files
import hashlib
import os
import psycopg
from psycopg.rows import dict_row
from psycopg.types.json import Jsonb
from .domain import SOURCE, DatasetSpec, Observation, DataError, canonical, validate, utc


class BatchBusy(RuntimeError):
    pass


class DatasetConflict(RuntimeError):
    pass


def connect(dsn: str | None = None):
    value = dsn if dsn is not None else os.environ.get("BOURSE_ML_DSN", "")
    if not value and not os.environ.get("PGDATABASE"):
        raise DataError("Configurer PGDATABASE ou BOURSE_ML_DSN avant de se connecter")
    return psycopg.connect(value, autocommit=True, row_factory=dict_row, connect_timeout=5,
                          application_name="bourse-ai-ml", options="-c timezone=UTC -c statement_timeout=30000 -c lock_timeout=5000")


def initialize(conn):
    script = files("bourse_ai_ml").joinpath("sql/001_demo.sql").read_text()
    checksum = hashlib.sha256(script.encode()).hexdigest()
    with conn.transaction():
        conn.execute("SELECT pg_advisory_xact_lock(187744, 1)")
        conn.execute("CREATE SCHEMA IF NOT EXISTS bourse_ml")
        conn.execute("REVOKE ALL ON SCHEMA bourse_ml FROM PUBLIC")
        conn.execute("CREATE TABLE IF NOT EXISTS bourse_ml.schema_migrations (version integer PRIMARY KEY, checksum text NOT NULL, applied_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP)")
        old = conn.execute("SELECT checksum FROM bourse_ml.schema_migrations WHERE version=1").fetchone()
        if old:
            if old["checksum"] != checksum:
                raise DatasetConflict("Migration déjà appliquée avec une autre empreinte")
            return
        conn.execute(script, prepare=False)
        conn.execute("INSERT INTO bourse_ml.schema_migrations(version,checksum) VALUES (1,%s)", (checksum,))


def content_digest(rows):
    h = hashlib.sha256()
    for row in rows:
        h.update(canonical(row.payload()).encode()); h.update(b"\n")
    return h.hexdigest()


def seed(conn, spec: DatasetSpec, rows: list[Observation]):
    validate(rows, utc(spec.end+"T23:59:59Z"))
    fingerprint = content_digest(rows)
    with conn.transaction():
        conn.execute("SELECT pg_advisory_xact_lock(hashtextextended(%s,0))", ("dataset:"+spec.dataset_id,))
        old = conn.execute("SELECT * FROM bourse_ml.datasets WHERE dataset_id=%s", (spec.dataset_id,)).fetchone()
        if old:
            actual = conn.execute("SELECT count(*) AS n FROM bourse_ml.observations WHERE dataset_id=%s", (spec.dataset_id,)).fetchone()["n"]
            if old["data_digest"] != fingerprint or old["row_count"] != len(rows) or actual != len(rows) or old["metadata"] != spec.metadata():
                raise DatasetConflict("Jeu existant différent ou incomplet : aucune réécriture autorisée")
            return False
        conn.execute("INSERT INTO bourse_ml.datasets(dataset_id,source,metadata,data_digest,row_count) VALUES (%s,%s,%s,%s,%s)",
                     (spec.dataset_id,SOURCE,Jsonb(spec.metadata()),fingerprint,len(rows)))
        with conn.cursor() as cursor:
            cursor.executemany("""INSERT INTO bourse_ml.observations
              (dataset_id,symbol,as_of,feature_available_at,entry_at,label_at,features,baseline_score,
               entry_price,exit_price,net_return,adverse_close_return,stress_tag)
              VALUES (%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s)""",
              [(spec.dataset_id,r.symbol,r.as_of,r.feature_available_at,r.entry_at,r.label_at,
                Jsonb(list(r.features)),r.baseline_score,r.entry_price,r.exit_price,r.net_return,
                r.adverse_close_return,r.stress_tag) for r in rows])
    return True


def load(conn, dataset_id):
    header = conn.execute("SELECT * FROM bourse_ml.datasets WHERE dataset_id=%s", (dataset_id,)).fetchone()
    if not header or header["source"] != SOURCE:
        raise DataError("Ce lot accepte uniquement un jeu SYNTHETIC_DEMO")
    with conn.transaction():
        records = conn.execute("SELECT * FROM bourse_ml.observations WHERE dataset_id=%s ORDER BY as_of,symbol", (dataset_id,)).fetchall()
    rows = [Observation(r["symbol"],r["as_of"],r["feature_available_at"],r["entry_at"],r["label_at"],
                        tuple(r["features"]),r["baseline_score"],r["entry_price"],r["exit_price"],
                        r["net_return"],r["adverse_close_return"],r["stress_tag"]) for r in records]
    validate(rows, utc(header["metadata"]["end"]+"T23:59:59Z"))
    if len(rows) != header["row_count"] or content_digest(rows) != header["data_digest"]:
        raise DatasetConflict("Les données ont changé après leur enregistrement")
    return rows


@contextmanager
def run_lock(conn, run_id):
    key = "bourse-ml-run:"+str(run_id)
    acquired = conn.execute("SELECT pg_try_advisory_lock(hashtextextended(%s,0)) AS ok", (key,)).fetchone()["ok"]
    if not acquired:
        raise BatchBusy("Ce même calcul est déjà en cours")
    try:
        yield
    finally:
        conn.execute("SELECT pg_advisory_unlock(hashtextextended(%s,0))", (key,))


def get_run(conn, run_id):
    return conn.execute("SELECT * FROM bourse_ml.runs WHERE run_id=%s", (run_id,)).fetchone()


def begin_run(conn, run_id, dataset_id, config):
    with conn.transaction():
        old = get_run(conn, run_id)
        if old and old["status"] == "SUCCEEDED":
            return old["summary"]
        # The session lock proves a RUNNING predecessor has stopped. Outputs are atomic.
        count = conn.execute("SELECT count(*) AS n FROM bourse_ml.models WHERE run_id=%s", (run_id,)).fetchone()["n"]
        if count:
            raise DatasetConflict("Résultats présents sur un calcul non terminé")
        if old:
            conn.execute("UPDATE bourse_ml.runs SET status='RUNNING',attempts=attempts+1,error_code=NULL,updated_time=CURRENT_TIMESTAMP WHERE run_id=%s", (run_id,))
        else:
            conn.execute("INSERT INTO bourse_ml.runs(run_id,dataset_id,configuration,status,attempts) VALUES (%s,%s,%s,'RUNNING',1)", (run_id,dataset_id,Jsonb(config)))
    return None


def save_result(conn, run_id, dataset_id, summary, results):
    with conn.transaction():
        for result in results:
            model = result["model"]
            if model.get("status") != "DEMO_ONLY" or model.get("promotion_allowed") is not False:
                raise DataError("Activation d'un modèle interdite dans ce lot")
            conn.execute("INSERT INTO bourse_ml.models(run_id,fold,status,promotion_allowed,model,metrics,stress_results) VALUES (%s,%s,'DEMO_ONLY',false,%s,%s,%s)",
                         (run_id,result["fold"],Jsonb(model),Jsonb(result["metrics"]),Jsonb(result["stress_results"])))
            with conn.cursor() as cursor:
                cursor.executemany("INSERT INTO bourse_ml.predictions(run_id,fold,dataset_id,symbol,as_of,estimate) VALUES (%s,%s,%s,%s,%s,%s)",
                    [(run_id,result["fold"],dataset_id,p["symbol"],utc(p["as_of"]),p["estimate"]) for p in result["predictions"]])
        conn.execute("UPDATE bourse_ml.runs SET status='SUCCEEDED',summary=%s,error_code=NULL,updated_time=CURRENT_TIMESTAMP WHERE run_id=%s", (Jsonb(summary),run_id))


def fail_run(conn, run_id):
    conn.execute("UPDATE bourse_ml.runs SET status='FAILED',error_code='BATCH_FAILED',updated_time=CURRENT_TIMESTAMP WHERE run_id=%s AND status='RUNNING'", (run_id,))


def status(conn):
    return {"schema": "bourse_ml", "datasets": conn.execute("SELECT count(*) AS n FROM bourse_ml.datasets").fetchone()["n"],
            "runs": [{**r,"run_id": str(r["run_id"])} for r in conn.execute("SELECT run_id,status,attempts,created_time,updated_time FROM bourse_ml.runs ORDER BY created_time DESC LIMIT 20").fetchall()]}
