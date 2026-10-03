import test from 'node:test';
import assert from 'node:assert/strict';
import {authorizeDownload,validateGrant} from './public/flat-20260925/download-client.js';
const grant=()=>({downloadEventId:7,downloadUrl:'/api/v1/downloads/7/content',expiresAt:new Date(Date.now()+60000).toISOString()});
test('授权只发送 assetId 和修剪后的用途，携带当前 CSRF',async()=>{
  let count=0;
  const result=await authorizeDownload(9,'  用于气象预测算法的研究与验证  ',async(url,options)=>{
    if(++count===1)return {ok:true,json:async()=>({authenticated:true,csrfToken:'current'})};
    assert.equal(url,'/api/v1/downloads');assert.equal(options.method,'POST');assert.equal(options.credentials,'same-origin');
    assert.equal(options.headers['X-CSRF-TOKEN'],'current');assert.deepEqual(JSON.parse(options.body),{assetId:9,purpose:'用于气象预测算法的研究与验证'});
    return {ok:true,json:async()=>grant()};
  });assert.equal(result.downloadEventId,7);assert.equal(count,2);
});
test('未登录不申请授权，带明确状态供恢复登录',async()=>{
  let calls=0;
  await assert.rejects(authorizeDownload(1,'用于科学研究和算法验证',async()=>{calls++;return {ok:true,json:async()=>({authenticated:false})};}),{status:401});
  assert.equal(calls,1);
});
test('非法资产/用途在请求前拒绝',async()=>{
  for(const [id,purpose] of [[0,'用于科学研究和算法验证'],[1,'短用途'],[1,' '.repeat(12)],[1,'a'.repeat(2001)]])await assert.rejects(authorizeDownload(id,purpose,()=>assert.fail('must not request')));
});
test('跨站、任意路径、事件不匹配及过期授权均拒绝',()=>{
  for(const url of ['https://foreign.test/x','//foreign.test/x','/netcdf/x.nc','/api/v1/downloads/8/content','/api/v1/downloads/7/content?x=1'])assert.throws(()=>validateGrant({...grant(),downloadUrl:url}));
  assert.throws(()=>validateGrant({...grant(),expiresAt:'2000-01-01T00:00:00Z'}));
});
test('401/403/404/409/422/429 不重放授权，错误保留状态',async()=>{
  for(const status of [401,403,404,409,422,429]){
    let calls=0;
    await assert.rejects(authorizeDownload(1,'用于科学研究和算法验证',async()=>++calls===1?{ok:true,json:async()=>({authenticated:true,csrfToken:'x'})}:{ok:false,status}),{status});
    assert.equal(calls,2);
  }
});
test('网络中断不自动重试可能已成功的授权 POST',async()=>{
  let calls=0;
  await assert.rejects(authorizeDownload(1,'用于科学研究和算法验证',async()=>{if(++calls===1)return {ok:true,json:async()=>({authenticated:true,csrfToken:'x'})};throw Error('network');}),/未确认/);
  assert.equal(calls,2);
});
