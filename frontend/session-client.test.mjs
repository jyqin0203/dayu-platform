import test from 'node:test';
import assert from 'node:assert/strict';
import {createSessionClient} from './public/flat-20260925/session-client.js';
const ok=data=>({ok:true,status:200,json:async()=>data});
test('查询使用同源 Cookie、no-store，不持久化令牌',async()=>{
  const client=createSessionClient(async(url,options)=>{assert.equal(url,'/api/v1/session');assert.equal(options.credentials,'same-origin');assert.equal(options.cache,'no-store');return ok({authenticated:false,csrfToken:'a'});});
  assert.equal((await client.current()).authenticated,false);
});
test('登录与注册均先读当前 CSRF；返回服务端身份',async()=>{
  for(const method of ['login','register']){
    const calls=[];
    const client=createSessionClient(async(url,options)=>{calls.push({url,...options});return ok(options.method==='GET'?{csrfToken:'fresh'}:{authenticated:true,user:{email:'test@example.test'},csrfToken:'rotated'});});
    const state=await client[method]({email:'test@example.test',password:'test-only-password',organization:'Lab'});
    assert.equal(state.authenticated,true);assert.equal(calls.length,2);
    assert.equal(calls[1].headers['X-CSRF-TOKEN'],'fresh');assert.equal(calls[1].method,'POST');
    assert.equal(calls[1].url,method==='login'?'/api/v1/session':'/api/v1/users');
    assert.equal(JSON.parse(calls[1].body).organization,method==='register'?'Lab':undefined);
  }
});
test('退出接受 204，不解析空响应；下一次写入重新取令牌',async()=>{
  let gets=0;
  const client=createSessionClient(async(url,options)=>{
    if(options.method==='GET')return ok({csrfToken:String(++gets)});
    assert.equal(options.headers['X-CSRF-TOKEN'],String(gets));
    return {ok:true,status:204,json:()=>{throw Error('empty body');}};
  });
  assert.deepEqual(await client.logout(),{authenticated:false,user:null});
  await client.logout();assert.equal(gets,2);
});
test('401/403/409/422/429/500 显示安全错误，写入失败不重试',async()=>{
  for(const status of [401,403,409,422,429,500]){
    let calls=0;
    const client=createSessionClient(async(url,options)=>{calls++;return options.method==='GET'?ok({csrfToken:'a'}):{ok:false,status,json:()=>{throw Error('must not show raw response');}};});
    await assert.rejects(client.login({email:'x',password:'y'}));assert.equal(calls,2);
  }
});
test('网络错误和缺少 CSRF 时拒绝继续提交',async()=>{
  await assert.rejects(createSessionClient(async()=>{throw Error('private detail');}).current(),/网络异常/);
  let calls=0;
  await assert.rejects(createSessionClient(async()=>{calls++;return ok({});}).logout(),/校验失败/);
  assert.equal(calls,1);
});
