"""Integration proof on an explicitly disposable, empty PostgreSQL database.

Starts the actual Java JAR, then Python against that SAME database. Never run
this bootstrap tool against a user database; the normal CLI does not launch Java.
"""
import argparse
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


def snapshot(conn):
    tables=conn.execute("SELECT table_name FROM information_schema.tables WHERE table_schema='public' AND table_type='BASE TABLE' ORDER BY table_name").fetchall()
    content={}
    for table in tables:
        name=table['table_name']
        rows=conn.execute(sql.SQL('SELECT to_jsonb(t) AS value FROM {}.{} AS t').format(sql.Identifier('public'),sql.Identifier(name))).fetchall()
        content[name]=sorted(canonical(r['value']) for r in rows)
    return {'tables':len(content),'rows':sum(len(v) for v in content.values()),'sha256':hashlib.sha256(canonical(content).encode()).hexdigest()}


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
                    assert len(json.load(response))==3
                proof={'same_postgresql_database':True,'java_remained_available':True,
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
