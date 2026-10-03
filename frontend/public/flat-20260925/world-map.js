// Display-only derivative of Natural Earth polygon rings, read from a local SHP export.
export async function createWorldBasemap(){
  const response=await fetch('assets/world-boundaries.geojson');
  if(!response.ok)throw Error('世界边界底图加载失败');
  const geojson=await response.json();
  const canvas=document.createElement('canvas');canvas.width=4096;canvas.height=2048;
  const ctx=canvas.getContext('2d');ctx.fillStyle='#102332';ctx.fillRect(0,0,canvas.width,canvas.height);
  const x=lon=>(lon+180)/360*canvas.width,y=lat=>(90-lat)/180*canvas.height;
  for(const feature of geojson.features){
    ctx.beginPath();
    for(const ring of feature.geometry.coordinates){
      ring.forEach(([lon,lat],i)=>{if(i===0)ctx.moveTo(x(lon),y(lat));else ctx.lineTo(x(lon),y(lat));});ctx.closePath();
    }
    ctx.fillStyle='#2b414d';ctx.fill('evenodd');
  }
  const provider=await Cesium.SingleTileImageryProvider.fromUrl(canvas.toDataURL('image/png'),{rectangle:Cesium.Rectangle.MAX_VALUE,credit:'Made with Natural Earth · 1:50m · Local preview'});
  return {provider,geojson};
}
