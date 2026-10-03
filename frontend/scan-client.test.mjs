import test from 'node:test';
import assert from 'node:assert/strict';
import {createScanClient} from './public/flat-20260925/scan-client.js';
const ok=data=>({ok:true,json:async()=>data});
test('扫描提交先检查管理员和当前 CSRF，不提交目录参数',async()=>{
  const calls=[];const api=createScanClient(async(url,options)=>{calls.push({url,options});return ok(calls.length===1?{authenticated:true,user:{role:'ADMIN'},csrfToken:'fresh'}:{scanRunId:2,status:'RUNNING'});});
  assert.equal((await api.start()).scanRunId,2);assert.equal(calls.length,2);
  assert.equal(calls[1].url,'/api/v1/admin/index-scans');assert.equal(calls[1].options.method,'POST');assert.equal(calls[1].options.headers['X-CSRF-TOKEN'],'fresh');assert.equal(calls[1].options.body,undefined);
});
test('普通用户不触发扫描，管理员接口仍处理403',async()=>{
  let calls=0;await assert.rejects(createScanClient(async()=>{calls++;return ok({authenticated:true,user:{role:'USER'}});}).start(),{status:403});assert.equal(calls,1);
  await assert.rejects(createScanClient(async()=>({ok:false,status:403})).history(),{status:403});
});
test('历史分页、详情均为同源只读请求',async()=>{
  const api=createScanClient(async(url,options)=>{assert.equal(options.method,'GET');assert.equal(options.credentials,'same-origin');assert.equal(options.cache,'no-store');return ok(url.includes('?')?{items:[],page:2,pageSize:20,total:21}:{scanRunId:1,status:'PARTIAL',errors:[]});});
  assert.equal((await api.history(2)).page,2);assert.equal((await api.detail(1)).status,'PARTIAL');
  await assert.rejects(api.history(0));assert.throws(()=>api.detail('../file'));
});
test('冲突不自动重发，网络不确定提示先查历史',async()=>{
  for(const failure of ['conflict','network']){let calls=0;const api=createScanClient(async()=>{if(++calls===1)return ok({authenticated:true,user:{role:'ADMIN'},csrfToken:'x'});if(failure==='network')throw Error();return {ok:false,status:409};});
    await assert.rejects(api.start(),failure==='network'?/未确认/:/已有扫描/);assert.equal(calls,2);
  }
});
test('坏列表响应不显示成功空结果',async()=>{
  await assert.rejects(createScanClient(async()=>ok({})).history(),/格式异常/);
});
