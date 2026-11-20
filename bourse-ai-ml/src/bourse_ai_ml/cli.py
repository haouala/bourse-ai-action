"""Manual independent batch: no network feed, no scheduler or live rule writes."""
import argparse
from datetime import date, datetime
import json
from pathlib import Path
import sys
import uuid
import psycopg
from . import repository as db
from .batch import execute
from .domain import DatasetSpec, DataError, HORIZONS
from .reporting import export


def emit(value):
    def dates(obj):
        if isinstance(obj,(date,datetime)):
            return obj.isoformat()
        raise TypeError("Type non exportable")
    print(json.dumps(value,ensure_ascii=False,allow_nan=False,default=dates))


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description="Bourse AI ML : données fictives, modèles non activables")
    commands = parser.add_subparsers(dest="command", required=True)
    commands.add_parser("init-db",help="Créer ou vérifier le schéma bourse_ml seulement")
    demo = commands.add_parser("demo",help="Générer, apprendre, évaluer et enregistrer les données fictives")
    demo.add_argument("--assets",type=int,default=8)
    demo.add_argument("--seed",type=int,default=42)
    demo.add_argument("--horizon",choices=HORIZONS,default="MONTH")
    demo.add_argument("--cost-bps",type=float,default=20.)
    demo.add_argument("--output",type=Path,default=Path("output"))
    commands.add_parser("status",help="Voir les derniers calculs du batch")
    report = commands.add_parser("report",help="Réexporter un résultat terminé sans réentraîner")
    report.add_argument("run_id",type=uuid.UUID)
    report.add_argument("--output",type=Path,default=Path("output"))
    args = parser.parse_args(argv)
    try:
        spec = DatasetSpec(seed=args.seed,assets=args.assets,horizon=args.horizon,cost_bps=args.cost_bps) if args.command=="demo" else None
        with db.connect() as conn:
            if args.command == "init-db":
                db.initialize(conn)
                emit({"status":"READY","schema":"bourse_ml","backend_tables_modified":False})
            elif args.command == "status":
                emit(db.status(conn))
            elif args.command == "report":
                path=export(conn,args.run_id,args.output)
                emit({"run_id":str(args.run_id),"output":str(path),"retrained":False})
            else:
                result=execute(conn,spec)
                path=export(conn,uuid.UUID(result["run_id"]),args.output)
                emit({k:result[k] for k in ("run_id","dataset_id","status","model_status","promotion_allowed","observation_count","prediction_count","reused")} | {"output":str(path)})
        return 0
    except db.BatchBusy:
        emit({"error":"BATCH_BUSY","message":"Ce même calcul est déjà en cours. Réessayer après sa fin."})
        return 3
    except (DataError,db.DatasetConflict) as exc:
        emit({"error":"INVALID_DATA_OR_CONFIGURATION","message":str(exc)})
        return 2
    except psycopg.Error:
        # Do not expose DSN, user credentials, server errors or SQL data in CLI output.
        emit({"error":"DATABASE_ERROR","message":"Connexion ou schéma PostgreSQL indisponible. Vérifier PGHOST, PGPORT, PGDATABASE, PGUSER et les droits ; lancer init-db avant demo."})
        return 2
    except OSError:
        emit({"error":"EXPORT_ERROR","message":"Export local impossible. Un résultat déjà validé en base est conservé ; relancer report avec un répertoire accessible."})
        return 2
    except Exception:
        emit({"error":"BATCH_FAILED","message":"Calcul interrompu. Consulter son état avec status ; aucune règle active n'a été modifiée."})
        return 1


if __name__ == "__main__":
    sys.exit(main())
