import {geometryFor} from './product-geometry.js';

/** Reuse up to three recently displayed layers. Texture identity changes when a file version changes. */
export function createFrameLayers(viewer){
  const ready=new Map();let active=null,cancelPending=null;
  const maxBytes=96*1024*1024;
  function trim(){
    let bytes=[...ready.values()].reduce((sum,r)=>sum+r.bytes,0);
    for(const [texture,record] of ready){
      if(ready.size<=3&&bytes<=maxBytes)break;
      if(record===active)continue;
      ready.delete(texture);bytes-=record.bytes;viewer.imageryLayers.remove(record.layer,true);
    }
  }
  async function present(texture,product,isCurrent,opacity){
    cancelPending?.();
    if(!isCurrent())return null;
    if(active?.texture===texture){active.layer.alpha=opacity;return active.layer;}
    let record=ready.get(texture);
    const reused=!!record;
    if(!record){
      const bounds=[...geometryFor(product.id).bounds];if(bounds[2]>180)bounds[2]-=360;
      const rectangle=Cesium.Rectangle.fromDegrees(...bounds);
      const provider=new Cesium.UrlTemplateImageryProvider({url:'prepared-frame',rectangle,tilingScheme:new Cesium.GeographicTilingScheme({rectangle,numberOfLevelZeroTilesX:1,numberOfLevelZeroTilesY:1}),tileWidth:texture.width,tileHeight:texture.height,minimumLevel:0,maximumLevel:0,enablePickFeatures:false});
      provider.requestImage=()=>Promise.resolve(texture);
      record={texture,layer:viewer.imageryLayers.addImageryProvider(provider),bytes:texture.width*texture.height*4};
    }
    const owner=Symbol();record.owner=owner;
    record.layer.alpha=0.001;viewer.imageryLayers.raiseToTop(record.layer);viewer.scene.requestRender();
    try{
      const done=await new Promise((resolve,reject)=>{
        let remove;
        const finish=(error,value)=>{clearTimeout(timeout);remove?.();cancelPending=null;error?reject(error):resolve(value);};
        const timeout=setTimeout(()=>finish(Error('地图图层准备超时，请重试')),15000);
        cancelPending=()=>finish(null,false);
        remove=viewer.scene.postRender.addEventListener(()=>{
          if(!isCurrent())finish(null,false);
          else if(viewer.scene.globe.tilesLoaded)finish(null,true);
          else viewer.scene.requestRender();
        });
      });
      if(!done||!isCurrent()){
        // A newer request may already be using the same retained record.
        if(record!==active&&record.owner===owner){if(!reused)viewer.imageryLayers.remove(record.layer,true);else record.layer.alpha=0;}
        return null;
      }
      if(active)active.layer.alpha=0;
      active=record;record.layer.alpha=opacity;
      ready.delete(texture);ready.set(texture,record);trim();viewer.scene.requestRender();
      return record.layer;
    }catch(error){if(!reused)viewer.imageryLayers.remove(record.layer,true);else record.layer.alpha=0;throw error;}
  }
  return {present};
}
