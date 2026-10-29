"""Real HTTP, original cache implementation, real isolated DB/Redis. No production sockets."""
from pathlib import Path
import json,socket,http.client,concurrent.futures,threading,time,math,hashlib,urllib.parse
R=Path('/tmp/dayu-redis-eval-20261008');OUT=Path(__file__).resolve().parents[1]/'results'
class Redis:
 def __init__(self):
  self.s=socket.create_connection(('127.0.0.1',16389),timeout=3);self.f=self.s.makefile('rb');self.cmd('AUTH',(R/'redis-password').read_text().strip())
 def read(self):
  line=self.f.readline();t=line[:1];v=line[1:-2]
  if t==b'+':return v.decode()
  if t==b'-':raise RuntimeError('Isolated Redis error')
  if t==b':':return int(v)
  if t==b'$':
   n=int(v)
   if n<0:return None
   out=self.f.read(n);self.f.read(2);return out.decode()
  if t==b'*':return [self.read() for _ in range(int(v))]
  raise RuntimeError('Bad Redis response')
 def cmd(self,*args):
  parts=[str(a).encode() for a in args];self.s.sendall(b'*'+str(len(parts)).encode()+b'\r\n'+b''.join(b'$'+str(len(p)).encode()+b'\r\n'+p+b'\r\n' for p in parts));return self.read()
 def close(self):self.f.close();self.s.close()
def control(mode,path):
 c=http.client.HTTPConnection('127.0.0.1',18582 if mode=='off' else 18583,timeout=30);c.request('GET',path);r=c.getresponse();v=json.loads(r.read());c.close();assert r.status==200,v;return v
local=threading.local();references={}
def request(mode,q):
 key=(mode,threading.get_ident());conns=getattr(local,'conns',None)
 if conns is None:local.conns=conns={}
 if mode not in conns:conns[mode]=http.client.HTTPConnection('127.0.0.1',18580 if mode=='off' else 18581,timeout=30)
 c=conns[mode];start=time.perf_counter();c.request('GET',q['url']);r=c.getresponse();payload=r.read();elapsed=time.perf_counter()-start
 decoded=json.loads(payload);digest=hashlib.sha256(json.dumps(decoded,sort_keys=True,separators=(',',':')).encode()).hexdigest()
 ok=r.status==200 and bool(decoded.get('files') or decoded.get('latest'))
 if 'expectedFiles' in q:ok=ok and len(decoded.get('files',[]))==q['expectedFiles']
 if q['id'] in references:ok=ok and references[q['id']]==digest
 return {'id':q['id'],'ms':elapsed*1000,'status':r.status,'correct':ok,'sha256':digest}
def measure(pool,mode,queries):
 control(mode,'/reset');start=time.perf_counter();rows=list(pool.map(lambda q:request(mode,q),queries));seconds=time.perf_counter()-start;metrics=control(mode,'/metrics');return rows,seconds,metrics
def aggregate(rows,seconds,metrics,**labels):
 durations=sorted(r['ms'] for r in rows);errors=sum(not r['correct'] for r in rows);lookups=metrics['cacheReads']
 return dict(labels,requests=len(rows),concurrency=4,p95Ms=durations[math.ceil(.95*len(rows))-1],throughputRps=len(rows)/seconds,elapsedSeconds=seconds,errors=errors,errorRate=errors/len(rows),**metrics,cacheHitRate=metrics['cacheHits']/lookups if lookups else None)
def main():
 queries=json.loads((R/'queries.json').read_text());redis=Redis();assert 'tcp_port:16389' in redis.cmd('INFO','server')
 results=[]
 with concurrent.futures.ThreadPoolExecutor(max_workers=4) as pool:
  # Same unmeasured warm-up for both app JVMs; warm DB/HTTP/JIT, then clear Redis before cold phases.
  for mode in ('off','on'):
   for _ in range(3):
    rows=list(pool.map(lambda q:request(mode,q),queries));assert all(r['correct'] for r in rows)
    for row in rows:references[row['id']]=row['sha256']
  for round in (1,2,3):
   order=('off','cold','warm') if round!=2 else ('cold','warm','off')
   for phase in order:
    mode='off' if phase=='off' else 'on'
    if phase=='warm':
     redis.cmd('FLUSHDB');rows=list(pool.map(lambda q:request('on',q),queries));assert all(r['correct'] for r in rows)
    samples=[];elapsed=0.;totals={k:0 for k in ('sql','selects','cacheReads','cacheHits','cacheMisses')}
    for block in range(10):
     if phase=='cold':redis.cmd('FLUSHDB')
     rows,seconds,metrics=measure(pool,mode,queries);samples.extend(rows);elapsed+=seconds
     for k in totals:totals[k]+=metrics[k]
    value=aggregate(samples,elapsed,totals,round=round,phase=phase,endpoint='/api/files.php forecast')
    results.append(value);(OUT/f'{phase}-{round}-samples.json').write_text(json.dumps(samples,separators=(',',':'))+'\n')
    (OUT/'benchmark.json').write_text(json.dumps(results,indent=2)+'\n');print(json.dumps(value),flush=True)
    assert value['errors']==0
  # Actual latest-batch endpoint uses server-side now() in cache keys. Keep URL fixed and measure repeat hits.
  latest=[{'id':1000+i,'url':'/api/fcst_latest.php?'+urllib.parse.urlencode({'path':'WebP/WebP_V2_Dpi500_4KM/forecast','product':q})} for i,q in enumerate(('BT855','BT108','BT120','BT133'))]
  latestResults=[]
  for round in (1,2,3):
   for mode in ('off','on'):
    redis.cmd('FLUSHDB');list(pool.map(lambda q:request(mode,q),latest))
    rows,seconds,metrics=measure(pool,mode,latest*50)
    value=aggregate(rows,seconds,metrics,round=round,phase=mode,endpoint='/api/fcst_latest.php repeats');latestResults.append(value)
    (OUT/f'latest-{mode}-{round}-samples.json').write_text(json.dumps(rows,separators=(',',':'))+'\n')
    (OUT/'latest-benchmark.json').write_text(json.dumps(latestResults,indent=2)+'\n');print(json.dumps(value),flush=True);assert value['errors']==0
  # Warm fixed query set for memory/TTL snapshot, outside timed regions.
  redis.cmd('FLUSHDB');list(pool.map(lambda q:request('on',q),queries))
  keys=redis.cmd('KEYS','dayu:discovery:*');usage=sum(redis.cmd('MEMORY','USAGE',key) or 0 for key in keys)
  ttl={}
  for pattern in ('*:generation','*:timeline:*','*:latest-cycles:*'):
   matching=redis.cmd('KEYS','dayu:discovery:'+pattern);values=[redis.cmd('PTTL',key) for key in matching]
   ttl[pattern]={'keys':len(matching),'minMs':min(values) if values else None,'maxMs':max(values) if values else None}
  (OUT/'redis-footprint.json').write_text(json.dumps({'keys':len(keys),'keyMemoryBytes':usage,'ttl':ttl,'infoMemory':redis.cmd('INFO','memory')},indent=2)+'\n')
 redis.close()
if __name__=='__main__':main()
