"""Only synthetic fixtures and the dedicated Redis PID are mutated after performance runs."""
from benchmark import R,OUT,Redis,control
import http.client,json,time,datetime,urllib.parse,os,signal,concurrent.futures,math

def get(path):
 c=http.client.HTTPConnection('127.0.0.1',18581,timeout=15);start=time.perf_counter();c.request('GET',path);r=c.getresponse();body=json.loads(r.read());elapsed=(time.perf_counter()-start)*1000;c.close();return r.status,body,elapsed

def main():
 checks=[];redis=Redis();assert 'tcp_port:16389' in redis.cmd('INFO','server')
 fixture=control('on','/fixture/reset');assert fixture['created']==1
 cycle=datetime.datetime.fromisoformat(fixture['cycle'].replace('Z','+00:00'))
 query='/api/v1/preview-frames?'+urllib.parse.urlencode({'productCode':'BT855','dataMode':'FORECAST','from':fixture['cycle'],'to':(cycle+datetime.timedelta(hours=4)).isoformat().replace('+00:00','Z'),'cycleTime':fixture['cycle']})
 for _ in range(2):
  status,body,ms=get(query);assert status==200 and len(body['items'])==1
 before=redis.cmd('GET','dayu:discovery:BT855:generation');start=time.perf_counter();assert control('on','/fixture/add/2')['created']==1
 status,body,ms=get(query);assert status==200 and len(body['items'])==2
 after=redis.cmd('GET','dayu:discovery:BT855:generation');assert before!=after
 checks.append({'test':'actual scanner insert + AssetIndexChanged invalidates fixed preview key','pass':True,'beforeItems':1,'afterItems':2,'firstRequestMs':ms,'scanAndFirstReadMs':(time.perf_counter()-start)*1000,'generationChanged':True})
 # Warm then corrupt only this experiment's payload: existing malformed-JSON fallback must repair it.
 keys=redis.cmd('KEYS','dayu:discovery:BT855:v'+after+':timeline:*');assert len(keys)==1
 redis.cmd('SET',keys[0],'{bad-json','PX',120000)
 status,body,ms=get(query);assert status==200 and len(body['items'])==2
 repaired=json.loads(redis.cmd('GET',keys[0]));assert len(repaired['frames'])==2
 checks.append({'test':'malformed payload falls back and repairs','pass':True,'items':2,'requestMs':ms})
 # Pause only the private Redis process, preserving old values; always resume in finally.
 pid=int((R/'redis.pid').read_text());cmd=open('/proc/'+str(pid)+'/cmdline','rb').read().decode(errors='replace');assert 'redis-server' in cmd and '16389' in cmd
 os.kill(pid,signal.SIGSTOP)
 try:
  assert control('on','/fixture/add/3')['created']==1
  control('on','/reset')
  with concurrent.futures.ThreadPoolExecutor(max_workers=4) as pool:results=list(pool.map(lambda _:get(query),range(12)))
  assert all(status==200 and len(body['items'])==3 for status,body,_ in results)
  metrics=control('on','/metrics');latencies=sorted(ms for _,_,ms in results)
  checks.append({'test':'Redis unresponsive + scan update + HTTP fallback','pass':True,'requests':12,'concurrency':4,'http200':12,'items':3,'p95Ms':latencies[math.ceil(len(latencies)*.95)-1],**metrics})
 finally:os.kill(pid,signal.SIGCONT)
 assert redis.cmd('PING')=='PONG'
 status,body,ms=get(query);assert status==200 and len(body['items'])==3
 resumed=redis.cmd('GET','dayu:discovery:BT855:generation');assert resumed!=after
 control('on','/reset');status,body,ms=get(query);metrics=control('on','/metrics');assert status==200 and len(body['items'])==3 and metrics['cacheHits']==1
 checks.append({'test':'resume retries pending invalidation before stale payload read; hit restored','pass':True,'items':3,'requestMs':ms,'generationChanged':True,**metrics})
 # Warm public product cache and exact preview query before a real transactional admin action.
 for _ in range(2):
  status,products,ms=get('/api/v1/products');assert status==200 and any(p['code']=='BT855' for p in products)
 catalogueTtlKeys=redis.cmd('KEYS','dayu:catalog:v*:published');catalogTtls=[redis.cmd('PTTL',k) for k in catalogueTtlKeys]
 cgen=redis.cmd('GET','dayu:catalog:generation');dgen=redis.cmd('GET','dayu:discovery:BT855:generation')
 assert control('on','/fixture/disable')['disabled']
 status,products,ms=get('/api/v1/products');assert status==200 and not any(p['code']=='BT855' for p in products)
 previewStatus,_,previewMs=get(query);assert previewStatus==404
 assert redis.cmd('GET','dayu:catalog:generation')!=cgen and redis.cmd('GET','dayu:discovery:BT855:generation')!=dgen
 checks.append({'test':'CatalogService AFTER_COMMIT invalidates catalog and product previews','pass':True,'catalogueTtlBeforeMs':catalogTtls,'publicListExcludesDisabled':True,'previewStatus':previewStatus,'previewMs':previewMs})
 (OUT/'correctness.json').write_text(json.dumps(checks,indent=2)+'\n');redis.close()
 print(json.dumps(checks,indent=2))
if __name__=='__main__':main()
