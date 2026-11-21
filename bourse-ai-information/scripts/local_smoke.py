#!/usr/bin/env python3
"""Real JAR + HTTP + Python worker, with synthetic feeds/AI and no paid calls.

Default: an isolated H2 file in a temporary directory, including a restart check.
Optional: explicitly named disposable loopback PostgreSQL test database only.
"""
import argparse
from datetime import datetime, timedelta, timezone
from email.utils import format_datetime
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
from pathlib import Path
import re
import socket
import subprocess
import sys
import tempfile
import threading
import time
from urllib.request import Request, build_opener, ProxyHandler
from xml.sax.saxutils import escape

sys.path.insert(0,str(Path(__file__).resolve().parents[1]/"src"))
from bourse_ai_information.worker import Backend,Cache,Worker,lower_keys
from bourse_ai_information.ai import OpenAI,Budget
from bourse_ai_information.http import Response

QUOTE="DEMO-INFO announces a synthetic change in its financial outlook."
TEXT=QUOTE+" This paragraph is deliberately fictional and is used only to validate the information pipeline."
opener=build_opener(ProxyHandler({}))


class Fixtures(BaseHTTPRequestHandler):
    calls=0
    def do_GET(self):
        podcast=self.path=="/RICHMOND_PODCAST"
        date=format_datetime(datetime.now(timezone.utc)-timedelta(minutes=5))
        extra="<enclosure type='audio/mpeg' url='https://demo.invalid/audio.mp3'/><podcast:transcript url='https://demo.invalid/transcript.txt'/>" if podcast else ""
        body=("<rss xmlns:podcast='https://podcastindex.org/namespace/1.0'><channel><item><guid>"+("podcast" if podcast else "news")+"</guid><title>Synthetic episode</title><description>"+escape(TEXT)+"</description><link>https://demo.invalid/"+("podcast" if podcast else "news")+"</link><pubDate>"+date+"</pubDate>"+extra+"</item></channel></rss>").encode()
        self.send_response(200);self.send_header("Content-Type","application/rss+xml");self.end_headers();self.wfile.write(body)
    def do_POST(self):
        type(self).calls+=1;request=json.loads(self.rfile.read(int(self.headers['Content-Length'])))
        assert request['model']=='DEMO'
        analysis={"summary":"Exemple fictif : changement de perspectives à examiner.","uncertainties":["Données et interprétation de démonstration uniquement."],"events":[{"eventKey":"demo-outlook","title":"Perspectives fictives","explanation":"Interprétation fictive défavorable.","quote":QUOTE,"speaker":None,"topic":"CORPORATE","kind":"ANNOUNCEMENT","impacts":[{"symbol":"DEMO-INFO","direction":"NEGATIVE","horizon":"MONTH","importance":70,"relevance":100,"novelty":70,"interpretationConfidence":80}]}]}
        body=json.dumps({"status":"completed","output":[{"type":"message","content":[{"type":"output_text","text":json.dumps(analysis)}]}]}).encode()
        self.send_response(200);self.send_header("Content-Type","application/json");self.end_headers();self.wfile.write(body)
    def log_message(self,*args):pass


class FixtureFetcher:
    def get(self,url,max_bytes=2_000_000):
        if url.endswith('transcript.txt'):return Response(TEXT.encode(),"text/plain",url)
        return Response(("<article><p>"+TEXT+"</p></article>").encode(),"text/html",url)


def main():
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--jar',type=Path,required=True);parser.add_argument('--java',default='java');parser.add_argument('--postgres-url');parser.add_argument('--output',type=Path,default=Path('output/smoke.json'));args=parser.parse_args()
    if not args.jar.is_file():parser.error('Build the backend JAR first')
    if args.postgres_url and not re.fullmatch(r'jdbc:postgresql://127\.0\.0\.1:[0-9]+/bourse_information_test_[a-z0-9_]+',args.postgres_url):parser.error('Only an explicitly named disposable loopback PostgreSQL test database is allowed')
    fixtures=ThreadingHTTPServer(('127.0.0.1',0),Fixtures);threading.Thread(target=fixtures.serve_forever,daemon=True).start()
    with socket.socket() as sock:sock.bind(('127.0.0.1',0));port=sock.getsockname()[1]
    base=f'http://127.0.0.1:{port}';secret='synthetic-worker-credential-only-000';review='synthetic-review-credential-only-000'
    def call(method,path,data=None,headers=None):
        request=Request(base+path,data=None if data is None else json.dumps(data).encode(),headers={'Content-Type':'application/json',**(headers or {})},method=method)
        with opener.open(request,timeout=20) as response:raw=response.read();return json.loads(raw) if raw else None
    with tempfile.TemporaryDirectory(prefix='bourse-information-smoke-') as directory:
        directory=Path(directory);log=(directory/'java.log').open('w');process=None
        command=[args.java,'-jar',str(args.jar.resolve()),f'--server.port={port}','--information.ai.enabled=true','--information.ai.model=DEMO',f'--information.ai.worker-token={secret}',f'--information.ai.review-token={review}',f'--information.test-base-url=http://127.0.0.1:{fixtures.server_port}','--information.scheduler.enabled=false','--market.scheduler.enabled=false']
        if args.postgres_url:
            command+=['--spring.profiles.active=postgres','--spring.datasource.url='+args.postgres_url]
        else:command+=['--spring.datasource.url=jdbc:h2:file:'+str(directory/'test')+';MODE=PostgreSQL;NON_KEYWORDS=VALUE']
        def start():
            p=subprocess.Popen(command,stdout=log,stderr=log)
            for _ in range(300):
                if p.poll() is not None:raise RuntimeError('Backend startup failed; see '+str(directory/'java.log'))
                try:
                    if call('GET','/actuator/health')['status']=='UP':return p
                except OSError:pass
                time.sleep(.1)
            p.terminate();p.wait(timeout=10);raise RuntimeError('Backend startup timeout')
        try:
            process=start()
            call('PUT','/api/v1/watchlist',dict(symbol='DEMO-INFO',name='Synthetic information test',assetType='CRYPTO',currency='EUR',providerSymbol='DEMO-INFO',region='GLOBAL',marketZone='UTC',sessionOpen='00:00',sessionClose='23:59',enabled=False))
            for code in ['FED_PRESS','RICHMOND_PODCAST']:
                call('PUT',f'/api/v1/information/sources/{code}/enabled',{'enabled':True});assert call('POST',f'/api/v1/information/sources/{code}/sync')['inserted']==1
            backend=Backend(base,secret)
            def ai_transport(path,body,content_type):
                with opener.open(Request(f'http://127.0.0.1:{fixtures.server_port}'+path,data=body,headers={'Content-Type':content_type}),timeout=10) as response:return json.load(response)
            ai=OpenAI('synthetic-key-not-sent-to-openai',Budget(directory/'budget.sqlite',10,1000000),ai_transport)
            worker=Worker(backend,FixtureFetcher(),ai,Cache(directory/'cache.sqlite'))
            results=[worker.once(),worker.once()];assert all(r['status']=='SUCCEEDED' for r in results),results
            assert worker.once()['status']=='IDLE'
            items=[lower_keys(i) for i in call('GET','/api/v1/information/items')]
            scopes=set()
            for item in items:
                scopes.add(lower_keys(call('GET',f"/api/v1/information/items/{item['id']}/contents")[0])['scope'])
                analysis=lower_keys(call('GET',f"/api/v1/information/items/{item['id']}/analyses")[0]);assert analysis['review_status']=='PROPOSED'
                call('POST',f"/api/v1/information/analyses/{analysis['id']}/review",{'approved':True,'reason':'Synthetic integration check','eventGroups':{}},{'X-Review-Token':review})
            assert scopes=={'ARTICLE_EXTRACT','TRANSCRIPT'},scopes
            value=call('POST','/api/v1/explanations/DEMO-INFO?horizon=MONTH')
            factor=next(f for f in value['factors'] if f['key']=='AI_INFORMATION');assert factor['points']<0
            assert factor['evidence']['approvedEventCount']==1 # One announcement in two media.
            assert factor['evidence']['attention']['publications24h']==2
            process.terminate();process.wait(timeout=15);process=start()
            assert worker.once()['status']=='IDLE'
            assert len(call('GET','/api/v1/information/jobs'))==2
            output={'synthetic':True,'database':'PostgreSQL' if args.postgres_url else 'H2 file','completedJobs':2,'scopes':sorted(scopes),'approvedEventCount':1,'publications24h':2,'restartPersistence':True,'simulatedAiCalls':Fixtures.calls,'paidAiCalls':0,'brokerOrders':0}
            args.output.parent.mkdir(parents=True,exist_ok=True);args.output.write_text(json.dumps(output,indent=2)+'\n');print(json.dumps(output))
        finally:
            if process is not None and process.poll() is None:process.terminate();process.wait(timeout=15)
            log.close();fixtures.shutdown()


if __name__=='__main__':main()
