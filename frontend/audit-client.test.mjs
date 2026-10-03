import test from 'node:test';
import assert from 'node:assert/strict';
import {auditParams,loadAudits} from './public/flat-20260925/audit-client.js';
const form={from:'2026-10-01T00:00',to:'2026-10-03T12:00',productCode:'BT108',userId:'3',organization:'复旦大学'};
test('审计筛选UTC参数、可选条件和一基分页',()=>{
  const p=auditParams(form,2);assert.equal(p.get('from'),'2026-10-01T00:00:00.000Z');assert.equal(p.get('userId'),'3');assert.equal(p.get('organization'),'复旦大学');assert.equal(p.get('page'),'2');assert.equal(p.get('pageSize'),'20');
  const empty=auditParams({...form,productCode:'',userId:'',organization:''});assert.equal(empty.has('userId'),false);assert.equal(empty.has('organization'),false);
});
test('错误日期、倒序时间、非法ID/编码/机构不发送请求',()=>{
  for(const change of [{from:'2026-02-30T00:00'},{from:'2027-01-01T00:00'},{userId:'0'},{userId:'1.5'},{productCode:'../x'},{organization:'a'.repeat(256)}])assert.throws(()=>auditParams({...form,...change}));
  assert.throws(()=>auditParams(form,0));
});
test('只读同源请求保留真实分页和AUTHORIZED状态',async()=>{
  const result=await loadAudits(form,1,async(url,options)=>{assert.ok(url.startsWith('/api/v1/admin/download-audits?'));assert.equal(options.method,'GET');assert.equal(options.cache,'no-store');assert.equal(options.credentials,'same-origin');return {ok:true,json:async()=>({items:[{status:'AUTHORIZED'}],page:1,pageSize:20,total:1})};});
  assert.equal(result.items[0].status,'AUTHORIZED');
});
test('401/403/422保留错误状态，坏响应不伪装空列表',async()=>{
  for(const status of [401,403,422])await assert.rejects(loadAudits(form,1,async()=>({ok:false,status})),{status});
  await assert.rejects(loadAudits(form,1,async()=>({ok:true,json:async()=>({items:[],page:2,pageSize:20,total:0})})),/格式/);
});
test('网络错误与取消明确返回',async()=>{
  await assert.rejects(loadAudits(form,1,async()=>{throw Error();}),/读取失败/);
  await assert.rejects(loadAudits(form,1,async()=>{throw new DOMException('cancel','AbortError');}),{name:'AbortError'});
});
