import test from 'node:test';
import assert from 'node:assert/strict';
import {prepareFrame,clearFrameCache,cancelPendingFrames,cachedFrame,refreshFrameVersions} from './public/flat-20260925/frame-cache.js';
function setup(t){
  clearFrameCache();let gets=0,heads=0,version='v1';
  t.mock.method(globalThis,'fetch',async(url,options={})=>{
    options.method==='HEAD'?heads++:gets++;
    return {ok:true,headers:new Headers({etag:version}),blob:async()=>new Blob(['compressed'])};
  });
  const original=globalThis.Image;
  globalThis.Image=class{width=100;height=100;async decode(){}};
  t.after(()=>{clearFrameCache();globalThis.Image=original;});
  return {get gets(){return gets;},get heads(){return heads;},setVersion(v){version=v;}};
}
test('returning to a decoded-evicted frame reuses compressed bytes and concurrent reads deduplicate',async t=>{
  const counter=setup(t);
  const [a,b]=await Promise.all([prepareFrame('frame0.webp','RGB'),prepareFrame('frame0.webp','RGB')]);
  assert.equal(a,b);assert.equal(counter.gets,1);
  for(let i=1;i<12;i++)await prepareFrame(`frame${i}.webp`,'RGB');
  assert.equal(cachedFrame('frame0.webp','RGB'),null);
  assert.notEqual(await prepareFrame('frame0.webp','RGB'),a);
  assert.equal(counter.gets,12);
});
test('compressed-only frames are validated after TTL, and a changed version replaces their bytes',async t=>{
  const counter=setup(t);const now=Date.now();t.mock.method(Date,'now',()=>now);
  for(let i=0;i<12;i++)await prepareFrame(`frame${i}.webp`,'RGB');
  t.mock.method(Date,'now',()=>now+180001);counter.setVersion('v2');
  await prepareFrame('frame0.webp','RGB');assert.equal(counter.heads,1);assert.equal(counter.gets,13);
});
test('version invalidation evicts both caches; manual clear cannot resurrect old bytes',async t=>{
  const counter=setup(t);const a=await prepareFrame('frame.webp','RGB');counter.setVersion('v2');
  assert.deepEqual([...await refreshFrameVersions('RGB',['frame.webp'])],['frame.webp']);
  assert.notEqual(await prepareFrame('frame.webp','RGB'),a);assert.equal(counter.gets,2);
  clearFrameCache();await prepareFrame('frame.webp','RGB');assert.equal(counter.gets,3);
});
test('cancelled decode cannot overwrite the replacement request',async t=>{
  setup(t);let release;let count=0;
  globalThis.Image=class{width=100;height=100;async decode(){if(count++===0)await new Promise(r=>release=r);}};
  const old=prepareFrame('same.webp','RGB');
  while(!release)await new Promise(r=>setImmediate(r));
  const rejection=assert.rejects(old,{name:'AbortError'});cancelPendingFrames();
  const replacement=await prepareFrame('same.webp','RGB');release();await rejection;
  assert.equal(await prepareFrame('same.webp','RGB'),replacement);
});
