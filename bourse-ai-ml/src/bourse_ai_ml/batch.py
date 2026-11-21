"""One execution per dataset/config fingerprint; DB results commit atomically."""
import uuid
from . import repository as db
from .demo import generate
from .domain import DatasetSpec, digest, SOURCE
from .training import train, training_config


def execute(conn, spec: DatasetSpec, feature_set="ENRICHED") -> dict:
    config = training_config(feature_set)
    run_id = uuid.uuid5(uuid.NAMESPACE_URL, "bourse-ai-ml:"+spec.dataset_id+":"+digest(config))
    with db.run_lock(conn, run_id):
        existing = db.get_run(conn, run_id)
        if existing and existing["status"] == "SUCCEEDED":
            # Validate the immutable dataset even on reuse: do not silently accept corruption.
            db.load(conn, spec.dataset_id)
            return {**existing["summary"], "reused": True}
        rows = generate(spec)
        db.seed(conn, spec, rows)
        previous = db.begin_run(conn, run_id, spec.dataset_id, config)
        if previous is not None:
            return {**previous, "reused": True}
        try:
            persisted = db.load(conn, spec.dataset_id)
            result = train(persisted, feature_set)
            folds = [{k:v for k,v in fold.items() if k not in ("predictions", "model")} for fold in result["folds"]]
            summary = {"run_id": str(run_id), "dataset_id": spec.dataset_id,
                       "source": SOURCE, "status": "SUCCEEDED", "model_status": "DEMO_ONLY",
                       "promotion_allowed": False, "dataset": spec.metadata(), "observation_count": len(rows),
                       "config": config, "folds": folds, "limitations": result["limitations"],
                       "prediction_count": sum(len(f["predictions"]) for f in result["folds"])}
            db.save_result(conn, run_id, spec.dataset_id, summary, result["folds"])
            return {**summary, "reused": False}
        except Exception:
            db.fail_run(conn, run_id)
            raise
