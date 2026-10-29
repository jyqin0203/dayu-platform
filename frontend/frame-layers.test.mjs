import test from 'node:test';
import assert from 'node:assert/strict';
import {createFrameLayers} from './public/flat-20260925/frame-layers.js';
function setup(t){
  const previous=globalThis.Cesium;
  globalThis.Cesium={Rectangle:{fromDegrees:(...bounds)=>bounds},GeographicTilingScheme:class{},UrlTemplateImageryProvider:class{}};
  t.after(()=>globalThis.Cesium=previous);
  const layers=[],listeners=new Set();
  const viewer={imageryLayers:{addImageryProvider:provider=>{const layer={provider};layers.push(layer);return layer;},raiseToTop(){},remove(layer){const i=layers.indexOf(layer);assert.ok(i>=0);layers.splice(i,1);}},scene:{requestRender(){},globe:{tilesLoaded:true},postRender:{addEventListener(fn){listeners.add(fn);return()=>listeners.delete(fn);}}}};
  return {pool:createFrameLayers(viewer),layers,render(){for(const fn of [...listeners])fn();}};
}
test('same texture and recent frames reuse layers, while the layer pool remains bounded',async t=>{
  const {pool,layers,render}=setup(t);const textures=Array.from({length:4},()=>({width:100,height:100}));
  async function show(texture){const promise=pool.present(texture,{id:'RGB'},()=>true,.85);render();return promise;}
  const first=await show(textures[0]);assert.equal(await show(textures[0]),first);assert.equal(layers.length,1);
  await show(textures[1]);assert.equal(await show(textures[0]),first);assert.equal(layers.length,2);
  await show(textures[2]);await show(textures[3]);assert.equal(layers.length,3);
  assert.equal(layers.filter(l=>l.alpha===.85).length,1);
});
test('superseded pending layers cannot replace the latest frame',async t=>{
  const {pool,layers,render}=setup(t);
  const old=pool.present({width:100,height:100},{id:'RGB'},()=>true,.85);
  const next=pool.present({width:100,height:100},{id:'RGB'},()=>true,.85);
  render();assert.equal(await old,null);assert.ok(await next);assert.equal(layers.length,1);
});
