"""Atomically publish completed research runs and forecasts to the shared database."""
import json

from .domain import DataError, canonical, utc
from .market import read_market
from .real_training import fingerprint, train_market
from .repository import run_lock


def execute_market(conn, start, cutoff, horizon, cost_bps, symbols):
    dataset = read_market(conn, start, cutoff, horizon, cost_bps, symbols)
    settings = {"start": start.isoformat(), "cutoff": cutoff.isoformat(),
                "horizon": horizon, "costBps": cost_bps}
    run_id = fingerprint(dataset, settings)
    with run_lock(conn, run_id):
        prior = conn.execute("SELECT summary FROM public.ml_market_runs WHERE run_id=%s", (run_id,)).fetchone()
        if prior:
            return {**json.loads(prior["summary"]), "reused": True}
        result = train_market(dataset)
        predictions = result.pop("predictions")
        summary = {**result, "runId": run_id, "source": "POSTGRES_MARKET_CANDLES", "status": "RESEARCH_ONLY",
                   "settings": settings, "coverage": dataset.coverage, "predictions": predictions}
        with conn.transaction():
            conn.execute("INSERT INTO public.ml_market_runs(run_id,horizon,data_cutoff,summary) VALUES (%s,%s,%s,%s)",
                         (run_id, horizon, cutoff, canonical(summary)))
            with conn.cursor() as cursor:
                cursor.executemany("""
                    INSERT INTO public.ml_market_predictions(run_id,symbol,as_of,currency,provider,estimate)
                    VALUES (%s,%s,%s,%s,%s,%s)
                    """, [(run_id, p["symbol"], utc(p["asOf"]), p["currency"], p["provider"], p["estimate"])
                          for p in predictions])
        return {**summary, "reused": False}


def market_report(conn, run_id):
    row = conn.execute("SELECT summary FROM public.ml_market_runs WHERE run_id=%s", (run_id,)).fetchone()
    if not row:
        raise DataError("Calcul de marché introuvable")
    return json.loads(row["summary"])
