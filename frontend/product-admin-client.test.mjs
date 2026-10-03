import test from 'node:test';
import assert from 'node:assert/strict';
import {createProductAdminClient,productPayload,productForm,modePayload,lifecycleAction} from './public/flat-20260925/product-admin-client.js';
const original={productId:1,code:'CTH',updatedAt:'v1',nameZh:'云顶高度',nameEn:'Cloud Top Height',producer:'Lab',colorbarRequired:false,sortOrder:1,colorbarUrl:'/CPP_Colorbar/horizontal/CTH_Colorbar.webp'};
const form=productForm(original),ok=data=>({ok:true,json:async()=>data});
const dual={...original,status:'PUBLISHED',modes:[{dataMode:'REALTIME',enabled:true,staleAfterMinutes:90},{dataMode:'FORECAST',enabled:true,staleAfterMinutes:360}]};
test('生命周期合法转换与发布前模式校验',()=>{
  assert.equal(lifecycleAction(dual,'disable'),'disable');
  for(const status of ['DRAFT','DISABLED'])assert.equal(lifecycleAction({...dual,status},'publish'),'publish');
  assert.throws(()=>lifecycleAction(dual,'publish'));assert.throws(()=>lifecycleAction({...dual,status:'DRAFT'},'disable'));
  assert.throws(()=>lifecycleAction({...dual,status:'DRAFT',modes:[]},'publish'),/启用/);assert.throws(()=>lifecycleAction(dual,'delete'));
});
test('创建仅发送资料与编码分类，不连带发布或模式',async()=>{
  let writes=0;const api=createProductAdminClient(async(url,options)=>{
    if(url.endsWith('/session'))return ok({authenticated:true,user:{role:'ADMIN'},csrfToken:'x'});
    writes++;assert.equal(url,'/api/v1/admin/products');assert.equal(options.method,'POST');const body=JSON.parse(options.body);
    assert.equal(body.code,'NEW_PRODUCT');assert.equal(body.family,'BT');assert.equal(body.status,undefined);assert.equal(body.modes,undefined);return ok({status:'DRAFT'});
  });assert.equal((await api.create({...form,code:'NEW_PRODUCT',family:'BT',status:'PUBLISHED',modes:dual.modes})).status,'DRAFT');assert.equal(writes,1);
  await assert.rejects(api.create({...form,code:'bad',family:'BT'}),/编码/);
});
test('独立生命周期 POST 带CSRF，不发送物理路径或状态体',async()=>{
  for(const [status,action] of [['PUBLISHED','disable'],['DRAFT','publish'],['DISABLED','publish']]){
    const p={...dual,status};let writes=0;const api=createProductAdminClient(async(url,options)=>{
      if(url.endsWith('/session'))return ok({authenticated:true,user:{role:'ADMIN'},csrfToken:'x'});if(options.method==='GET')return ok([p]);
      writes++;assert.equal(url,'/api/v1/admin/products/1/'+action);assert.equal(options.method,'POST');assert.equal(options.body,undefined);assert.equal(options.headers['X-CSRF-TOKEN'],'x');return ok({status:action==='publish'?'PUBLISHED':'DISABLED'});
    });await api.transition(p,action);assert.equal(writes,1);
  }
});
test('创建请求超时不重放并提示先核对',async()=>{
  let writes=0;const api=createProductAdminClient(async(url)=>{if(url.endsWith('/session'))return ok({authenticated:true,user:{role:'ADMIN'},csrfToken:'x'});writes++;throw Error();});
  await assert.rejects(api.create({...form,code:'NEW_PRODUCT',family:'BT'}),/未确认/);assert.equal(writes,1);
});
test('模式阈值边界及最后一个启用模式保护',()=>{
  assert.deepEqual(modePayload(dual,'REALTIME',false,10),{enabled:false,staleAfterMinutes:10});
  assert.equal(modePayload(dual,'FORECAST',true,10080).staleAfterMinutes,10080);
  for(const minutes of [9,10081,1.5,''])assert.throws(()=>modePayload(dual,'REALTIME',true,minutes));
  assert.throws(()=>modePayload({...dual,modes:[dual.modes[0]]},'REALTIME',false,90),/至少一种/);
  assert.doesNotThrow(()=>modePayload({...dual,status:'DRAFT'},'REALTIME',false,90));
});
test('模式 PUT 只发送 enabled 和 staleAfterMinutes，带最新CSRF',async()=>{
  let writes=0;const api=createProductAdminClient(async(url,options)=>{
    if(url.endsWith('/session'))return ok({authenticated:true,user:{role:'ADMIN'},csrfToken:'fresh'});
    if(options.method==='GET')return ok([dual]);writes++;
    assert.equal(url,'/api/v1/admin/products/1/modes/FORECAST');assert.equal(options.headers['X-CSRF-TOKEN'],'fresh');
    assert.deepEqual(JSON.parse(options.body),{enabled:true,staleAfterMinutes:120});return ok({dataMode:'FORECAST',enabled:true,staleAfterMinutes:120});
  });assert.equal((await api.saveMode(dual,'FORECAST',true,120)).staleAfterMinutes,120);assert.equal(writes,1);
});
test('模式保存发现版本变化时不写入',async()=>{
  let writes=0;const api=createProductAdminClient(async(url,options)=>{if(options.method==='PUT')writes++;return ok(url.endsWith('/session')?{authenticated:true,user:{role:'ADMIN'},csrfToken:'x'}:[{...dual,updatedAt:'v2'}]);});
  await assert.rejects(api.saveMode(dual,'FORECAST',true,120),/已被修改/);assert.equal(writes,0);
});
test('服务端409仍拒绝模式保存，客户端不重放',async()=>{
  let writes=0;const api=createProductAdminClient(async(url,options)=>{if(url.endsWith('/session'))return ok({authenticated:true,user:{role:'ADMIN'},csrfToken:'x'});if(options.method==='GET')return ok([dual]);writes++;return {ok:false,status:409};});
  await assert.rejects(api.saveMode(dual,'REALTIME',false,90),{status:409});assert.equal(writes,1);
});
test('编辑映射保留色标路径，白名单不发送状态模式和编码',()=>{
  assert.equal(form.colorbarPath,'CPP_Colorbar/horizontal/CTH_Colorbar.webp');
  const body=productPayload({...form,status:'DISABLED',modes:[],code:'OTHER'});
  assert.equal(body.status,undefined);assert.equal(body.modes,undefined);assert.equal(body.code,undefined);assert.equal(body.unit,null);
});
test('名称排序路径和来源 URL 提交前校验',()=>{
  for(const change of [{nameZh:'一'},{sortOrder:1.5},{colorbarPath:'../x'},{colorbarPath:'/x'},{officialSourceUrl:'javascript:alert(1)'},{producer:''}])assert.throws(()=>productPayload({...form,...change}));
});
test('保存核对最新版本并携带 CSRF，成功只发一次 PUT',async()=>{
  const calls=[];const api=createProductAdminClient(async(url,options)=>{calls.push({url,options});if(url.endsWith('/session'))return ok({authenticated:true,user:{role:'ADMIN'},csrfToken:'fresh'});if(options.method==='GET')return ok([original]);assert.equal(options.headers['X-CSRF-TOKEN'],'fresh');return ok({...original,updatedAt:'v2'});});
  assert.equal((await api.save(original,form)).updatedAt,'v2');assert.equal(calls.length,3);assert.equal(calls[2].options.method,'PUT');assert.equal(calls[2].url,'/api/v1/admin/products/1');
});
test('他人已修改或无管理员身份时不 PUT',async()=>{
  let puts=0;const api=createProductAdminClient(async(url,options)=>{if(options.method==='PUT')puts++;return ok(url.endsWith('/session')?{authenticated:true,user:{role:'ADMIN'},csrfToken:'x'}:[{...original,updatedAt:'v2'}]);});
  await assert.rejects(api.save(original,form),/已被修改/);assert.equal(puts,0);
  await assert.rejects(createProductAdminClient(async()=>ok({authenticated:true,user:{role:'USER'}})).save(original,form),{status:403});
});
test('PUT 超时不重放，422 提示不泄漏原始响应',async()=>{
  for(const fail of ['network','invalid']){let puts=0;const api=createProductAdminClient(async(url,options)=>{
    if(url.endsWith('/session'))return ok({authenticated:true,user:{role:'ADMIN'},csrfToken:'x'});if(options.method==='GET')return ok([original]);puts++;if(fail==='network')throw Error('private');return {ok:false,status:422};
  });await assert.rejects(api.save(original,form),fail==='network'?/未确认/:/不符合/);assert.equal(puts,1);}
});
