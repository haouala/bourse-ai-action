"""Integration proof on an explicitly disposable, empty PostgreSQL database.

Starts the actual Java JAR, then Python against that SAME database. Never run
this bootstrap tool against a user database; the normal CLI does not launch Java.
"""
import argparse
from datetime import date, datetime, time as clock_time, timedelta, timezone
import math
import hashlib
import json
import os
from pathlib import Path
import re
import socket
import subprocess
import sys
import time
import urllib.request
from psycopg import sql
from psycopg.conninfo import conninfo_to_dict
from bourse_ai_ml import repository as db
from bourse_ai_ml.domain import canonical


def snapshot(conn, exclude=()):
    tables=conn.execute("SELECT table_name FROM information_schema.tables WHERE table_schema='public' AND table_type='BASE TABLE' ORDER BY table_name").fetchall()
    content={}
    for table in tables:
        name=table['table_name']
        if name in exclude:
            continue
        rows=conn.execute(sql.SQL('SELECT to_jsonb(t) AS value FROM {}.{} AS t').format(sql.Identifier('public'),sql.Identifier(name))).fetchall()
        content[name]=sorted(canonical(r['value']) for r in rows)
    return {'tables':len(content),'rows':sum(len(v) for v in content.values()),'sha256':hashlib.sha256(canonical(content).encode()).hexdigest()}


def seed_market_fixture(conn):
    first, end = date(2024, 1, 2), date(2025, 2, 28)
    source = "https://example.test/fixture/calendar"
    conn.execute("INSERT INTO market_calendars(code,zone_id,coverage_start,coverage_end,source_url) VALUES ('ML_FIXTURE','UTC',%s,%s,%s)",
                 (first, end, source))
    asset = conn.execute("""
        INSERT INTO assets(symbol,name,asset_type,currency) VALUES ('MLFIXTURE','Synthetic ML smoke','STOCK','USD')
        RETURNING id
        """).fetchone()["id"]
    conn.execute("INSERT INTO asset_market_policies(asset_id,calendar_code) VALUES (%s,'ML_FIXTURE')", (asset,))
    conn.execute("""
        INSERT INTO daily_series_qualifications(asset_id,provider,adjustment,evidence_url,qualified_at)
        VALUES (%s,'FIXTURE','SPLIT_ADJUSTED',%s,'2024-01-02T00:00:00Z')
        """, (asset, source))
    day = first
    index = 0
    while day <= end:
        midnight = datetime.combine(day, clock_time(), timezone.utc)
        if day.weekday() < 5:
            open_at, close_at = midnight + timedelta(hours=14), midnight + timedelta(hours=20)
            conn.execute("""
                INSERT INTO market_sessions(calendar_code,session_date,opens_at,closes_at,reason,source_url)
                VALUES ('ML_FIXTURE',%s,%s,%s,'TEST_SESSION',%s)
                """, (day, open_at, close_at, source))
            price = 110 + 12 * math.sin(index / 6) + 3 * math.cos(index / 2)
            price = round(price, 4)
            opening = round(price * (1 + .012 * math.sin(index / 3)), 4)
            conn.execute("""
                INSERT INTO market_candles(asset_id,timeframe,open_time,open_price,high_price,low_price,close_price,volume,provider)
                VALUES (%s,'1d',%s,%s,%s,%s,%s,1000,'FIXTURE')
                """, (asset, midnight, opening, max(opening, price), min(opening, price), price))
            index += 1
        else:
            conn.execute("""
                INSERT INTO market_sessions(calendar_code,session_date,reason,source_url)
                VALUES ('ML_FIXTURE',%s,'WEEKEND',%s)
                """, (day, source))
        day += timedelta(days=1)
    return first.isoformat(), end.isoformat() + "T23:00:00Z"


def verify_real_path(conn, cli, port, output):
    start, cutoff = seed_market_fixture(conn)
    protected_tables = ("ml_market_runs", "ml_market_predictions")
    before = snapshot(conn, exclude=protected_tables)
    options = ("--start", start, "--as-of", cutoff, "--horizon", "DAY", "--symbols", "MLFIXTURE")
    coverage = cli("market-coverage", *options)["coverage"]
    assert coverage[0]["status"] == "READY"
    first = cli("train-market", *options)
    again = cli("train-market", *options)
    assert not first["reused"] and again["reused"] and first["runId"] == again["runId"]
    assert len(first["predictions"]) == 1 and len(first["walkForward"]) == 3
    assert set(first["validationBrier"]) == {"LOGISTIC", "HIST_GRADIENT_BOOSTING"}
    assert first["technicalStatus"] == "COMPARED"
    assert "hammer" in first["candidateFeatures"] and "elliott_abc" in first["candidateFeatures"]
    assert {c["featureSet"] for c in first["featureComparison"]} == {"PRICE_ONLY", "TECHNICAL_ENRICHED"}
    assert snapshot(conn, exclude=protected_tables) == before
    with urllib.request.urlopen(f"http://127.0.0.1:{port}/api/v1/ml/market/MLFIXTURE", timeout=3) as response:
        published = json.load(response)
    assert len(published) == 1 and published[0]["runId"] == first["runId"]
    assert published[0]["testCount"] == first["testCount"]
    assert published[0]["featureSet"] == first["selectedFeatureSet"]
    assert published[0]["features"] == first["features"]
    assert len(published[0]["featureComparison"]) == 2
    with urllib.request.urlopen(f"http://127.0.0.1:{port}/api/v1/technical-analysis/MLFIXTURE", timeout=5) as response:
        technical = json.load(response)
    assert technical["analysis"]["method"] == "TECHNICAL_FEATURES_V1"
    assert technical["analysis"]["features"]["ohlc_available"] == 1.
    assert not technical["usable"]  # The fixture calendar ends in 2025; stale evidence cannot alter today's score.
    (output / "market-fixture-report.json").write_text(json.dumps(first, indent=2) + "\n")
    return {"fixture": "SYNTHETIC_PRICES_IN_JAVA_SCHEMA", "run_id": first["runId"],
            "second_run_reused": again["reused"], "api_verified": True, "test_count": first["testCount"]}


def main():
    p=argparse.ArgumentParser();p.add_argument('--jar',type=Path,required=True)
    p.add_argument('--output',type=Path,default=Path('output/shared-backend'))
    args=p.parse_args();jar=args.jar.resolve();assert jar.is_file()
    dsn=os.environ['BOURSE_ML_TEST_DSN']
    with db.connect(dsn) as conn:
        info=conn.info
        assert info.dbname.startswith('bourse_ml_test_') and re.fullmatch('[a-z0-9_]+',info.dbname)
        assert info.host in ('127.0.0.1','localhost'), 'Integration bootstrap requires a local disposable database'
        assert snapshot(conn)['tables']==0, 'Database must be empty before Java bootstrap'
        env=os.environ.copy();env.update(
            SPRING_DATASOURCE_URL=f'jdbc:postgresql://{info.host}:{info.port}/{info.dbname}',
            SPRING_DATASOURCE_USERNAME=info.user,
            SPRING_DATASOURCE_PASSWORD=conninfo_to_dict(dsn).get('password',os.environ.get('PGPASSWORD','')),
            SPRING_DATASOURCE_DRIVER_CLASS_NAME='org.postgresql.Driver',
            MARKET_SCHEDULER_ENABLED='false',INFORMATION_SCHEDULER_ENABLED='false',SOCIAL_X_ENABLED='false',
            TWELVE_DATA_API_KEY='',EODHD_API_KEY='',FMP_API_KEY='',SOCIAL_X_BEARER_TOKEN='',
            BOURSE_ML_DSN=dsn)
        with socket.socket() as sock:
            sock.bind(('127.0.0.1',0));port=sock.getsockname()[1]
        args.output.mkdir(parents=True,exist_ok=True)
        java=str(Path(env['JAVA_HOME'])/'bin/java') if env.get('JAVA_HOME') else 'java'
        with (args.output/'backend.log').open('w') as log:
            server=subprocess.Popen([java,'-Xmx384m','-jar',str(jar),f'--server.port={port}',
                '--server.address=127.0.0.1','--spring.profiles.active=postgres'],env=env,stdout=log,stderr=subprocess.STDOUT)
            try:
                deadline=time.monotonic()+90
                while True:
                    if server.poll() is not None:raise RuntimeError('Java startup failed; inspect backend.log')
                    try:
                        with urllib.request.urlopen(f'http://127.0.0.1:{port}/actuator/health',timeout=2) as response:
                            if json.load(response)['status']=='UP':break
                    except Exception:pass
                    if time.monotonic()>deadline:raise RuntimeError('Java startup timeout')
                    time.sleep(.5)
                before=snapshot(conn);assert before['tables']>=42
                def cli(*values):
                    result=subprocess.run([sys.executable,'-m','bourse_ai_ml',*values],env=env,capture_output=True,text=True,check=True)
                    return json.loads(result.stdout)
                cli('init-db')
                first=cli('demo','--assets','8','--horizon','MONTH','--output',str(args.output))
                again=cli('demo','--assets','8','--horizon','MONTH','--output',str(args.output))
                assert first['run_id']==again['run_id'] and again['reused']
                after=snapshot(conn);assert before==after,'Java public data changed during ML batch'
                with urllib.request.urlopen(f'http://127.0.0.1:{port}/api/v1/explanations/rulesets',timeout=3) as response:
                    rules=json.load(response)
                    assert len(rules)==6 and {r['method'] for r in rules}=={'EXPLAINABLE_RULES_V1_EXPERIMENTAL','EXPLAINABLE_RULES_V2_TECHNICAL'}
                market_proof=verify_real_path(conn,cli,port,args.output)
                proof={'market_adapter_training_api':market_proof,'same_postgresql_database':True,'java_remained_available':True,
                    'public_before':before,'public_after':after,'ml_schema':'bourse_ml',
                    'backend_rules_modified':False,'demo_result':first,'second_run_reused':again['reused'],
                    'postgres_version':conn.execute('SHOW server_version').fetchone()['server_version']}
                (args.output/'shared-backend-proof.json').write_text(json.dumps(proof,ensure_ascii=False,indent=2)+'\n')
                print(json.dumps(proof,ensure_ascii=False))
            finally:
                server.terminate()
                try:server.wait(timeout=10)
                except subprocess.TimeoutExpired:server.kill();server.wait()


if __name__=='__main__':main()
