#!/usr/bin/env python3
"""Local synthetic-data walkthrough. No real quotes, provider calls, subscriptions or orders."""
import argparse
import datetime as dt
import json
import math
import urllib.request
import uuid

parser=argparse.ArgumentParser(description=__doc__)
parser.add_argument('--base-url',default='http://127.0.0.1:8081')
options=parser.parse_args()
base=options.base_url.rstrip('/')
def call(method,path,data=None):
    payload=None if data is None else json.dumps(data).encode()
    request=urllib.request.Request(base+path,data=payload,method=method,headers={'Content-Type':'application/json'})
    with urllib.request.urlopen(request,timeout=30) as response:
        value=response.read()
        return json.loads(value) if value else None
def iso(value):return value.isoformat().replace('+00:00','Z')

assert call('GET','/actuator/health')['status']=='UP'
symbol='DEMO-'+uuid.uuid4().hex[:8].upper()
now=dt.datetime.now(dt.timezone.utc).replace(microsecond=0)
last=dt.datetime.fromtimestamp(math.floor(now.timestamp()/900)*900,tz=dt.timezone.utc)
call('PUT','/api/v1/watchlist',dict(symbol=symbol,name='Synthetic demo only',assetType='CRYPTO',currency='EUR',providerSymbol=symbol,region='GLOBAL',marketZone='UTC',sessionOpen='00:00',sessionClose='23:59',enabled=False))
bars=[]
for i in range(40,0,-1):
    price=100+(40-i)/10
    bars.append(dict(openTime=iso(last-dt.timedelta(minutes=15*i)),open=price,high=price,low=price,close=price,volume=10))
imported=call('POST','/api/v1/history/import',dict(symbol=symbol,name='Synthetic demo only',assetType='CRYPTO',currency='EUR',timeframe='15m',provider='SYNTHETIC_DEMO',bars=bars))
assert imported['inserted']==40
assert call('POST','/api/v1/history/import',dict(symbol=symbol,name='Synthetic demo only',assetType='CRYPTO',currency='EUR',timeframe='15m',provider='SYNTHETIC_DEMO',bars=bars))['skipped']==40
calendar=call('GET','/api/v1/calendars/assets/'+symbol+'/state')
assert calendar['marketState']=='OPEN'
explanation=call('POST','/api/v1/explanations/'+symbol+'?horizon=DAY')
assert explanation['id']>0
assert call('GET','/api/v1/explanations/snapshots/'+str(explanation['id']))['id']==explanation['id']
seasonality=call('GET','/api/v1/seasonality/'+symbol)
assert len(seasonality['statistics']['monthly'])==12 and len(seasonality['statistics']['quarterly'])==4
fund=call('POST','/api/v1/portfolios',dict(name='Synthetic demo '+symbol,baseCurrency='EUR',initialCapital=1000,maxLossAmount=100))['id']
position=call('POST','/api/v1/portfolios/'+str(fund)+'/positions',dict(clientOrderId='demo-buy',symbol=symbol,quantity=1,price=100,fees=0,fxToBase=1,executedAt=iso(now-dt.timedelta(hours=1)),horizon='SHORT'))['positionId']
call('PUT','/api/v1/portfolios/'+str(fund)+'/positions/'+str(position)+'/mark',dict(price=95,fxToBase=1,observedAt=iso(now),fxObservedAt=iso(now),source='SYNTHETIC_DEMO'))
risk=call('POST','/api/v1/portfolios/'+str(fund)+'/evaluate')
assert risk['loss']==5 and risk['remainingLossBudget']==95
sources=call('GET','/api/v1/information/sources')
print(json.dumps(dict(synthetic=True,symbol=symbol,portfolioId=fund,positionId=position,explanationId=explanation['id'],importedCandles=40,loss=risk['loss'],remainingLossBudget=risk['remainingLossBudget'],informationSources=len(sources),realProviderCalls=0,brokerOrders=0),indent=2))
