import test from 'node:test';
import assert from 'node:assert/strict';
import {reconcileTimeline} from './public/flat-20260925/timeline-refresh.js';
import {imageVersion,prepareFrame,refreshFrameVersions,clearFrameCache} from './public/flat-20260925/frame-cache.js';
const frame=t=>`WebP/CTH_${t}.webp`;
test('new frames and expired older frames preserve the selected file rather than numeric index',()=>{
  const a=frame('202610290900'),b=frame('202610290930'),c=frame('202610291000');
  assert.equal(reconcileTimeline(b,{cycle:'',frames:[b,c]}).index,0);
  assert.equal(reconcileTimeline(b,{cycle:'',frames:[a,b,c]}).index,1);
});
test('expired selection chooses nearest actual UTC time across month boundaries',()=>{
  const previous=frame('202609302330');
  assert.equal(reconcileTimeline(previous,{cycle:'',frames:[frame('202609302200'),frame('202610010000')]}).index,1);
  assert.throws(()=>reconcileTimeline(previous,{frames:[]}),/为空/);
});
test('new forecast cycles require explicit selection',()=>{
  assert.equal(reconcileTimeline(frame('202610290900'),{cycle:'202610290900',frames:[frame('202610291000')]},'202610290800').newCycle,true);
});
test('versions prefer ETag, fall back to modification time plus size, and reject incomplete metadata',()=>{
  assert.equal(imageVersion(new Headers({etag:'"abc"'})),'etag:"abc"');
  assert.notEqual(imageVersion(new Headers({'last-modified':'a','content-length':'1'})),imageVersion(new Headers({'last-modified':'a','content-length':'2'})));
  assert.equal(imageVersion(new Headers({'content-length':'2'})),null);
});
test('unchanged HEAD reuses pixels, changed same URL reloads once, failed HEAD preserves cached pixels',async t=>{
  let version='v1',gets=0,heads=0,fail=false;
  t.mock.method(globalThis,'fetch',async(url,options={})=>{
    const head=options.method==='HEAD';head?heads++:gets++;
    return {ok:!(head&&fail),headers:new Headers({etag:version}),blob:async()=>new Blob(['image'])};
  });
  const oldImage=globalThis.Image;
  globalThis.Image=class{width=100;height=100;async decode(){}};
  try{
    clearFrameCache();const a=await prepareFrame('one.webp','RGB');
    assert.equal((await refreshFrameVersions('RGB',['one.webp'])).size,0);
    assert.equal(await prepareFrame('one.webp','RGB'),a);assert.equal(gets,1);assert.equal(heads,1);
    version='v2';assert.deepEqual([...await refreshFrameVersions('RGB',['one.webp'])],['one.webp']);
    const b=await prepareFrame('one.webp','RGB');assert.notEqual(a,b);assert.equal(gets,2);
    fail=true;await assert.rejects(refreshFrameVersions('RGB',['one.webp']));
    assert.equal(await prepareFrame('one.webp','RGB'),b);assert.equal(gets,2);
  }finally{clearFrameCache();globalThis.Image=oldImage;}
});
