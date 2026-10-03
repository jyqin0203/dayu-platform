import test from 'node:test';
import assert from 'node:assert/strict';
import {readFile} from 'node:fs/promises';
import {toUtc,timeInput,scientificParams,searchScientific} from './public/flat-20260925/scientific-client.js';
const form={productCode:'CTH',dataMode:'FORECAST',zone:'Asia/Shanghai',from:'2026-10-03T08:00',to:'2026-10-04T08:00',cycleTime:'2026-10-03T06:00',leadMinutes:'120'};
test('两个页面都提供独立检索导航，账号模块不包含检索入口',async()=>{
  for(const path of ['public/index.html','public/flat-20260925/index.html']){
    const html=await readFile(new URL(path,import.meta.url),'utf8');
    assert.match(html,/<nav[^>]*>[\s\S]*?<button id="science-open">数据检索<\/button>[\s\S]*?<\/nav>/);
    assert.match(html,/<script type="module" src="scientific-search.js"><\/script>/);
  }
  const account=await readFile(new URL('public/flat-20260925/account.js',import.meta.url),'utf8');
  assert.doesNotMatch(account,/account-search|openScientificSearch|scientific-search\.js/);
});
test('北京时间跨日转换与 UTC 往返不依赖机器时区',()=>{
  assert.equal(toUtc('2026-10-03T06:00','Asia/Shanghai'),'2026-10-02T22:00:00.000Z');
  assert.equal(timeInput('2026-10-02T22:00:00Z','Asia/Shanghai'),'2026-10-03T06:00');
  assert.equal(toUtc('2026-10-03T06:00','UTC'),'2026-10-03T06:00:00.000Z');
  assert.throws(()=>toUtc('2026-02-30T00:00','UTC'));
  assert.throws(()=>toUtc('2026-10-03T00:00','invalid'));
});
test('预报包含可选起报/时效，分页从1开始，实况忽略残余预报条件',()=>{
  const params=scientificParams(form,2);
  assert.equal(params.get('page'),'2');assert.equal(params.get('pageSize'),'20');
  assert.equal(params.get('cycleTime'),'2026-10-02T22:00:00.000Z');assert.equal(params.get('leadMinutes'),'120');
  assert.equal(scientificParams({...form,dataMode:'REALTIME'}).has('cycleTime'),false);
  assert.equal(scientificParams({...form,cycleTime:'',leadMinutes:''}).has('leadMinutes'),false);
});
test('倒置时间、非法模式/产品、时效、页码不发送请求',async()=>{
  for(const values of [{from:'2026-10-05T00:00'},{dataMode:'bad'},{productCode:'../x'},{leadMinutes:'-1'},{leadMinutes:'1.5'}]){
    await assert.rejects(searchScientific({...form,...values},1,()=>{throw Error('should not fetch');}),error=>!error.message.includes('should not fetch'));
  }
  assert.throws(()=>scientificParams(form,0));
});
test('查询端点只读且返回分页元数据，包括空结果',async()=>{
  for(const total of [0,21]){
    const result=await searchScientific(form,1,async(url,options)=>{
      assert.ok(url.startsWith('/api/v1/scientific-assets?'));assert.equal(options.credentials,'same-origin');
      assert.equal(options.method,undefined);return {ok:true,json:async()=>({items:[],page:1,pageSize:20,total})};
    });assert.equal(result.total,total);
  }
});
test('服务错误/网络异常/取消/错误页码均不冒充空结果',async()=>{
  await assert.rejects(searchScientific(form,1,async()=>({ok:false,status:422})),/条件/);
  await assert.rejects(searchScientific(form,1,async()=>{throw Error();}),/网络/);
  await assert.rejects(searchScientific(form,1,async()=>{throw new DOMException('cancel','AbortError');}),{name:'AbortError'});
  await assert.rejects(searchScientific(form,1,async()=>({ok:true,json:async()=>({items:[],total:0,page:2,pageSize:20})})),/格式/);
});
