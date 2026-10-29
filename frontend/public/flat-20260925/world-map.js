import {BASEMAP_URL} from './basemap-resource.js';

// The land fill is generated once during maintenance, with the original coordinates/colors.
export async function createWorldBasemap(){
  const provider=await Cesium.SingleTileImageryProvider.fromUrl(BASEMAP_URL,{
    rectangle:Cesium.Rectangle.MAX_VALUE,credit:'Made with Natural Earth · 1:50m · Local preview'
  });
  return {provider,loadBoundaries:async()=>{
    const response=await fetch('assets/world-boundaries.geojson');
    if(!response.ok)throw Error('世界边界线加载失败');
    return response.json();
  }};
}
