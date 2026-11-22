"""Portable JSON and CSV, no pickle; exports can be retried after DB success."""
import csv
from io import StringIO
from pathlib import Path
import hashlib
import json
import os
import uuid
from . import repository as db
from .domain import DataError, canonical


def atomic_write(path: Path, text: str):
    temporary = path.with_name(path.name+"."+uuid.uuid4().hex+".tmp")
    try:
        temporary.write_text(text, encoding="utf-8")
        os.replace(temporary, path)
    finally:
        temporary.unlink(missing_ok=True)


def export(conn, run_id: uuid.UUID, output: Path) -> Path:
    row = db.get_run(conn, run_id)
    if not row or row["status"] != "SUCCEEDED":
        raise DataError("Seul un calcul terminé peut être exporté")
    directory = output / str(run_id)
    directory.mkdir(parents=True, exist_ok=True)
    models = [r["model"] for r in conn.execute("SELECT model FROM bourse_ml.models WHERE run_id=%s ORDER BY fold", (run_id,)).fetchall()]
    predictions = conn.execute("""SELECT p.fold,p.symbol,p.as_of,p.estimate,o.label_at,o.net_return,
        o.adverse_close_return,o.baseline_score,o.stress_tag
        FROM bourse_ml.predictions p JOIN bourse_ml.observations o
        ON o.dataset_id=p.dataset_id AND o.symbol=p.symbol AND o.as_of=p.as_of
        WHERE p.run_id=%s ORDER BY p.fold,p.as_of,p.symbol""", (run_id,)).fetchall()
    stream = StringIO(newline="")
    fields = ("run_id","fold","symbol","as_of","estimate","label_at","net_return",
              "adverse_close_return","baseline_score","stress_tag")
    writer = csv.DictWriter(stream, fieldnames=fields); writer.writeheader()
    for p in predictions:
        writer.writerow({**p,"run_id":str(run_id),"as_of":p["as_of"].isoformat(),"label_at":p["label_at"].isoformat()})
    texts = {"summary.json": json.dumps(row["summary"],ensure_ascii=False,indent=2,allow_nan=False)+"\n",
             "models.json": json.dumps({"run_id":str(run_id),"source":"SYNTHETIC_DEMO",
                 "promotion_allowed":False,"models":models},ensure_ascii=False,indent=2,allow_nan=False)+"\n",
             "predictions.csv": stream.getvalue()}
    for name, text in texts.items():
        atomic_write(directory/name, text)
    manifest = {"run_id":str(run_id),"source":"SYNTHETIC_DEMO", "files":{
        name:hashlib.sha256(text.encode()).hexdigest() for name,text in texts.items()}}
    atomic_write(directory/"complete.json", canonical(manifest)+"\n")
    return directory
