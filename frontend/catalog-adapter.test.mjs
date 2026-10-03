import test from 'node:test';
import assert from 'node:assert/strict';
import {expandCatalog,loadColorbar} from './public/flat-20260925/catalog-adapter.js';
import {geometryFor,unpad} from './public/flat-20260925/product-geometry.js';

test('公开目录按后端模式展开，云预报继承真实单位，不补未启用产品',()=>{
  const rows=expandCatalog([{code:'CTH',nameZh:'云顶高度',unit:'km',modes:[
    {dataMode:'REALTIME',staleAfterMinutes:60},{dataMode:'FORECAST',staleAfterMinutes:120}
  ]},{code:'RGB',nameZh:'真彩色',modes:[]}]);
  assert.deepEqual(rows.map(p=>p.id),['CTH','FCST_CTH']);
  assert.equal(rows[1].unit,'km');assert.equal(rows[1].pathId,'CTH');
  assert.equal(rows[1].staleAfterMinutes,120);
  assert.deepEqual(expandCatalog([]),[]);
  assert.throws(()=>expandCatalog({}),/目录/);
});
test('降水完整预报仍由三个 Legacy 时效列表组成',()=>{
  const [p]=expandCatalog([{code:'PRECIP',modes:[{dataMode:'FORECAST'}]}]);
  assert.equal(p.id,'FCST_PRECIP');assert.equal(p.combined,true);
});
test('色标使用服务端公开地址，包括缺省和失败场景',async()=>{
  let requested;
  assert.equal(await loadColorbar('CTH',async url=>{
    requested=url;return {ok:true,json:async()=>({colorbarUrl:'/colorbars/cth.webp'})};
  }),'/colorbars/cth.webp');
  assert.equal(requested,'/api/v1/products/CTH');
  assert.equal(await loadColorbar('RGB',async()=>({ok:true,json:async()=>({colorbarUrl:null})})),null);
  await assert.rejects(loadColorbar('CTH',async()=>({ok:false})),/读取失败/);
  await assert.rejects(loadColorbar('CTH',async()=>({ok:true,json:async()=>({colorbarUrl:'//foreign/x'})})),/地址/);
});
test('云预报与实况定位和裁边一致，拒绝未知图像尺寸',()=>{
  for(const id of ['CLP','CTH','COT','CER','CWP','BT108','PRECIP']) {
    assert.deepEqual(geometryFor('FCST_'+id),geometryFor(id));
  }
  assert.throws(()=>unpad({width:1},'FCST_CTH'),/尺寸改变/);
  const image={width:100};assert.equal(unpad(image,'FCST_BT108'),image);
});
