import test from 'node:test';
import assert from 'node:assert/strict';
import {loadAdminOverview} from './public/flat-20260925/admin-client.js';
const ok=value=>({ok:true,json:async()=>value});
test('匿名与普通用户止于身份检查，不请求管理数据',async()=>{
  for(const authenticated of [false,true]){let calls=0;await assert.rejects(loadAdminOverview(async()=>{calls++;return ok({authenticated,user:{role:'USER'}});}),{status:authenticated?403:401});assert.equal(calls,1);}
});
test('管理员仅调用三个只读接口，不把授权量改称下载完成',async()=>{
  const urls=[];
  const result=await loadAdminOverview(async(url,options)=>{
    urls.push(url);assert.equal(options.method,'GET');assert.equal(options.cache,'no-store');assert.equal(options.credentials,'same-origin');
    if(url.endsWith('/session'))return ok({authenticated:true,user:{role:'ADMIN'}});
    return ok(url.endsWith('/dashboard')?{downloads:{authorizedRequests:0},latestScan:null}:{items:[]});
  });
  assert.deepEqual(urls,['/api/v1/session','/api/v1/admin/dashboard','/api/v1/admin/product-health']);
  assert.equal(result.dashboard.downloads.authorizedRequests,0);assert.deepEqual(result.health,[]);
});
test('管理端点二次鉴权拒绝不能被前端 ADMIN 标记绕过',async()=>{
  await assert.rejects(loadAdminOverview(async url=>url.endsWith('/session')?ok({authenticated:true,user:{role:'ADMIN'}}):{ok:false,status:403}),{status:403});
});
test('网络/服务/格式错误明确报错，不展示伪造的零值概览',async()=>{
  await assert.rejects(loadAdminOverview(async()=>{throw Error('private');}),/连接失败/);
  await assert.rejects(loadAdminOverview(async()=>({ok:false,status:500})),/读取失败/);
  await assert.rejects(loadAdminOverview(async url=>url.endsWith('/session')?ok({authenticated:true,user:{role:'ADMIN'}}):ok({})),/格式异常/);
});
