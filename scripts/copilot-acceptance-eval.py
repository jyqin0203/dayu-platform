import urllib.request,urllib.error,urllib.parse,http.cookiejar,json,time,pathlib,re
# Live acceptance: 48 real-model turns plus 12 HTTP/security checks.
# Requires the published PRECIP metadata used by the October 2026 acceptance snapshot.
import argparse
parser=argparse.ArgumentParser()
parser.add_argument('--base-url',default='http://127.0.0.1:15174')
parser.add_argument('--output',required=True)
options=parser.parse_args()
BASE=options.base_url.rstrip('/')
class Client:
 def __init__(self):
  self.op=urllib.request.build_opener(urllib.request.ProxyHandler({}),urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()));self.csrf=self.get('/api/v1/session')['csrfToken']
 def req(self,path,data=None,method=None,csrf=True):
  h={'Content-Type':'application/json'}
  if csrf and hasattr(self,'csrf'):h['X-CSRF-TOKEN']=self.csrf
  return self.op.open(urllib.request.Request(BASE+path,None if data is None else json.dumps(data).encode(),h,method=method),timeout=60)
 def get(self,path):return json.load(self.req(path))
 def create(self):return json.load(self.req('/api/v1/copilot/conversations',{}))['conversationId']
 def query(self,id,message,zone='Asia/Shanghai'):
  events=[];start=time.monotonic()
  with self.req('/api/v1/copilot/conversations/'+id+'/stream',{'message':message,'displayZone':zone}) as response:
   event=''
   for line in response:
    line=line.decode().strip()
    if line.startswith('event:'):event=line[6:]
    if line.startswith('data:'):events.append({'type':event,'value':json.loads(line[5:]),'seconds':round(time.monotonic()-start,3)})
  return events
 def clear(self,id):self.req('/api/v1/copilot/conversations/'+id,method='DELETE').close()
results=[]
def check(c,events,expected):
 assert events[-1]['type']=='done',str(events[-1])
 facts=[e['value'] for e in events if e['type']=='result'];assert facts,'no factual result'
 final=facts[-1];criteria=final.get('criteria') or {}
 for k,v in expected.items():
  if k=='clarify':assert not criteria,criteria
  elif k=='contains':assert v in final['answer'],final
  elif k=='cycle':assert criteria.get('cycleTime'),criteria
  else:assert criteria.get(k)==v,(k,v,criteria)
 if criteria.get('queryKind') in ['SCIENTIFIC_SEARCH','PREVIEW_SEARCH']:
  q={k:criteria[k] for k in ['productCode','dataMode','from','to','cycleTime','leadMinutes'] if criteria.get(k) is not None}
  scientific=criteria['queryKind']=='SCIENTIFIC_SEARCH';raw=c.get(('/api/v1/scientific-assets' if scientific else '/api/v1/preview-frames')+'?'+urllib.parse.urlencode(q));count=raw['total'] if scientific else len(raw['items']);assert re.search(('文件数' if scientific else '预览帧')+r'：'+str(count)+r'(?:\D|$)',final['answer']),(count,final)
 return final
cases=[('about','大禹是什么',{'contains':'大禹'}),('products','有哪些公开产品',{}),('help','介绍PRECIP产品',{'queryKind':'PRODUCT_HELP','productCode':'PRECIP'}),('forecast','查PRECIP预报科学数据，北京时间2026年9月2日全天',{'queryKind':'SCIENTIFIC_SEARCH','dataMode':'FORECAST','from':'2026-09-01T16:00:00Z'}),('realtime','查PRECIP实况科学数据，北京时间2026年9月2日全天',{'queryKind':'SCIENTIFIC_SEARCH','dataMode':'REALTIME'}),('preview','查PRECIP预报地图预览，北京时间2026年9月2日全天',{'queryKind':'PREVIEW_SEARCH'}),('utc','查PRECIP预报科学数据，UTC时间2026年9月2日全天',{'queryKind':'SCIENTIFIC_SEARCH','from':'2026-09-02T00:00:00Z'}),('empty','查PRECIP预报科学数据，北京时间2020年1月1日全天',{'queryKind':'SCIENTIFIC_SEARCH','contains':'文件数：0'}),('future','查PRECIP预报科学数据，北京时间2030年1月1日全天',{'queryKind':'SCIENTIFIC_SEARCH','contains':'文件数：0'}),('missing-mode','查PRECIP科学数据',{'clarify':True}),('missing-product','查预报科学数据',{'clarify':True}),('latest','查PRECIP最新预报批次的科学数据',{'queryKind':'SCIENTIFIC_SEARCH','cycle':True})]
for repeat in range(3):
 c=Client()
 for name,message,expected in cases:
  id=c.create();record={'repeat':repeat,'case':name,'message':message}
  try:record['events']=c.query(id,message);record['result']=check(c,record['events'],expected);record['pass']=True
  except Exception as e:record['pass']=False;record['error']=str(e)
  finally:c.clear(id)
  results.append(record);print(repeat,name,record['pass'],record.get('error',''),flush=True)
 id=c.create()
 for name,message,expected in [('multi-product','查PRECIP科学数据',{'clarify':True}),('multi-mode','预报',{'clarify':True}),('multi-time','2026年9月2日全天，北京时间',{'productCode':'PRECIP','dataMode':'FORECAST','from':'2026-09-01T16:00:00Z'}),('multi-change','改成9月3日，其他不变',{'productCode':'PRECIP','dataMode':'FORECAST','from':'2026-09-02T16:00:00Z'})]:
  record={'repeat':repeat,'case':name,'message':message}
  try:record['events']=c.query(id,message);record['result']=check(c,record['events'],expected);record['pass']=True
  except Exception as e:record['pass']=False;record['error']=str(e)
  results.append(record);print(repeat,name,record['pass'],record.get('error',''),flush=True)
 c.clear(id)
 for name in ['credentials','owner','csrf','zone']:
  id=c.create();record={'repeat':repeat,'case':name}
  try:
   if name=='credentials':assert c.query(id,'password=private-fixture')[-1]['value']['code']=='VALIDATION_FAILED'
   elif name=='owner':assert Client().query(id,'大禹是什么')[-1]['value']['code']=='NOT_FOUND'
   else:
    try:
     if name=='csrf':c.req('/api/v1/copilot/conversations',{},csrf=False)
     else:c.query(id,'大禹是什么','invalid/zone')
     raise AssertionError('invalid request accepted')
    except urllib.error.HTTPError as e:assert e.code==(403 if name=='csrf' else 422),e.code
   record['pass']=True
  except Exception as e:record['pass']=False;record['error']=str(e)
  finally:c.clear(id)
  results.append(record);print(repeat,name,record['pass'],record.get('error',''),flush=True)
 pathlib.Path(options.output).write_text(json.dumps(results,ensure_ascii=False,indent=2))
print('TOTAL',len(results),'PASS',sum(r['pass'] for r in results),flush=True)

raise SystemExit(0 if all(r["pass"] for r in results) else 1)
