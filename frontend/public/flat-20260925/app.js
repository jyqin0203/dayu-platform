import {createWorldBasemap} from './world-map.js';
import {expandCatalog,loadColorbar} from './catalog-adapter.js';
import {dateOf,stamp,leadHours,loadTimeline} from './timeline-data.js';
import {geometryFor,containsPoint,unpad} from './product-geometry.js';
const $=id=>document.getElementById(id);
const products=[];
let pointRequest=0;
let pickedPoint=null,pickMarker=null,forecastCycle='';
let viewer,base,vectorBase,naturalBase,worldLines,currentLayer,currentURL,product,frames=[],index=0,generation=0,frameRequest=0,playing=false,timer,toastTimer;
let currentRegion='china',catalogMode='realtime';
const root='WebP/WebP_V2_Dpi500_4KM';
const read=resource=>'/'+resource;
const displayDate=file=>dateOf(stamp(file)).toISOString().slice(0,16).replace('T',' ')+' UTC';
const shortDate=file=>displayDate(file).slice(5,16);
function notify(message){$('toast').textContent=message;$('toast').hidden=false;clearTimeout(toastTimer);toastTimer=setTimeout(()=>$('toast').hidden=true,4000);}
async function json(resource){const response=await fetch(read(resource));if(!response.ok)throw Error('数据接口不可用');return response.json();}
function stop(){playing=false;clearTimeout(timer);$('play').textContent='▶';$('play').setAttribute('aria-label','播放');}
function updateProductUI(){
  $('product-title').textContent=product.name;$('mode-label').textContent=product.mode==='forecast'?'预报产品':'卫星实况';
  $('legend-title').textContent=product.name;$('legend-unit').textContent=product.unit;
  const isColor=['RGB','FRGB'].includes(product.id), rain=product.id.includes('PRECIP');
  $('legend-image').hidden=isColor;
  if(isColor)$('legend-image').removeAttribute('src');
  else {
    const selected=product;
    $('legend-image').hidden=true;$('legend-image').removeAttribute('src');
    loadColorbar(product.pathId||product.id).then(url=>{
      if(product!==selected)return;
      if(url){$('legend-image').hidden=false;$('legend-image').src=url;}
      else $('legend-note').textContent='该产品未配置色标';
    }).catch(()=>{if(product===selected)$('legend-note').textContent='产品色标配置暂时不可用';});
  }

  $('legend-note').textContent=isColor?'原始彩色合成影像 · 不使用数值色阶':rain?'雨 / 雪双配色 · 原始色标，未改变数值':'原始产品色标 · 未修改数值';
  document.querySelectorAll('[data-id]').forEach(b=>{b.classList.toggle('active',b.dataset.id===product.id);b.setAttribute('aria-pressed',String(b.dataset.id===product.id));});
  $('realtime-tab').classList.toggle('active',product.mode==='realtime');$('forecast-tab').classList.toggle('active',product.mode==='forecast');

}
function buildCatalog(){
  $('product-list').replaceChildren();
  for(const p of products.filter(p=>p.mode===catalogMode)){
    const b=document.createElement('button');b.dataset.id=p.id;b.textContent=p.name;
    if(p.draft){b.disabled=true;b.textContent+='（准备中）';}const small=document.createElement('small');small.textContent=p.en;b.append(small);b.onclick=()=>{select(p);$('catalog').hidden=true;};$('product-list').append(b);
  }

  document.querySelectorAll('[data-tab]').forEach(b=>b.classList.toggle('active',b.dataset.tab===catalogMode));updateProductUI();
}
function openCatalog(mode){catalogMode=mode;buildCatalog();$('catalog').hidden=false;$('catalog-close').focus();}
function clearLayer(){if(currentLayer){viewer.imageryLayers.remove(currentLayer,true);currentLayer=null;}if(currentURL){URL.revokeObjectURL(currentURL);currentURL=null;}viewer.scene.requestRender();}
async function select(p){

  window.mapLoading.stage(1,'正在读取'+p.name+'…');
  stop();const token=++generation;++frameRequest;product=p;frames=[];clearLayer();updateProductUI();
  $('forecast-leads').replaceChildren();$('forecast-leads').hidden=true;updatePoint();
  $('window-label').textContent='正在读取时间范围…';
  $('play').disabled=true;$('timeline').disabled=true;$('data-time').textContent='正在读取数据时间…';$('data-status').textContent='读取公开图像列表…';$('frame-count').textContent='0 帧';$('selected-time').textContent='等待图像';$('start-time').textContent='—';$('end-time').textContent='—';
  try{
    forecastCycle='';
    const snapshot=await loadTimeline(p,json,root);
    if(token!==generation)return;
    const {all}=snapshot;forecastCycle=snapshot.cycle;frames=snapshot.frames;
    if(!frames.length)throw Error('该产品当前没有可用图像');
    index=frames.length-1;$('timeline').max=frames.length-1;$('timeline').disabled=false;$('play').disabled=frames.length<2;
    const age=Math.round((Date.now()-dateOf(p.mode==='forecast'?forecastCycle:stamp(all.at(-1))).getTime())/60000);
    $('data-status').textContent=`${p.mode==='forecast'?'起报':'网站最新文件'}距今 ${Math.max(0,age)} 分钟${p.mode==='realtime'&&frames.length<48?` · 24小时窗口缺 ${48-frames.length} 帧`:''} · 手动刷新更新列表`;

    $('window-label').textContent=p.mode==='realtime'?'最近24小时 · 每30分钟':`起报 ${dateOf(forecastCycle).toISOString().slice(5,16).replace('T',' ')} UTC · ${frames.length} 个时效`;
    $('forecast-leads').replaceChildren();$('forecast-leads').hidden=p.mode!=='forecast';
    if(p.mode==='forecast')frames.forEach((f,i)=>{const b=document.createElement('button');b.textContent='+'+leadHours(f,forecastCycle)+'h';b.onclick=()=>{stop();showFrame(i).catch(e=>notify(e.message));};$('forecast-leads').append(b);});
    $('start-time').textContent=shortDate(frames[0]);$('end-time').textContent=shortDate(frames.at(-1));
    document.querySelector('.ticks').hidden=p.mode==='forecast';
    window.mapLoading.stage(2,'正在渲染地图与首帧数据…');
    await showFrame(index,token);
    if(token===generation)window.mapLoading.ready();

    if(token===generation && $('autoplay').checked && frames.length>1)start();
  }catch(error){if(token!==generation)return;window.mapLoading.ready();$('data-time').textContent='暂无可展示数据';$('data-status').textContent=error.message;$('selected-time').textContent='数据不可用';$('window-label').textContent='暂无可用时间范围';notify(error.message);}
}
async function showFrame(i,token=generation){
  if(!frames[i])return false;const request=++frameRequest;
  const response=await fetch(read(frames[i]));if(!response.ok)throw Error('图像加载失败，请刷新后重试');
  const blob=await response.blob();const url=URL.createObjectURL(blob);
  try{
    const image=new Image();image.src=url;await image.decode();
    if(token!==generation||request!==frameRequest){URL.revokeObjectURL(url);return false;}
    const geometry=geometryFor(product.id),bounds=[...geometry.bounds];if(bounds[2]>180)bounds[2]-=360;
    const texture=unpad(image,product.id),textureUrl=texture===image?url:texture.toDataURL('image/png');
    const rectangle=Cesium.Rectangle.fromDegrees(...bounds);
    const provider=new Cesium.UrlTemplateImageryProvider({url:textureUrl,rectangle,tilingScheme:new Cesium.GeographicTilingScheme({rectangle,numberOfLevelZeroTilesX:1,numberOfLevelZeroTilesY:1}),tileWidth:texture.width,tileHeight:texture.height,minimumLevel:0,maximumLevel:0,enablePickFeatures:false});
    const previous=currentLayer,previousURL=currentURL;
    currentLayer=viewer.imageryLayers.addImageryProvider(provider);currentLayer.alpha=Number($('opacity').value)/100;currentURL=url;
    index=i;$('timeline').value=i;$('data-time').textContent=displayDate(frames[i]);$('selected-time').textContent=shortDate(frames[i])+' UTC';$('frame-count').textContent=`${i+1} / ${frames.length} 帧`;
    [...$('forecast-leads').children].forEach((b,j)=>b.classList.toggle('active',j===i));updatePoint();
    viewer.scene.requestRender();
    // Keep the previous frame until Cesium has uploaded the replacement texture.
    // Image.decode alone does not mean that the map tile is ready for display.

    const retire=()=>{if(previous&&viewer.imageryLayers.contains(previous))viewer.imageryLayers.remove(previous,true);if(previousURL)URL.revokeObjectURL(previousURL);viewer.scene.requestRender();};setTimeout(retire,250);
    return true;
  }catch(error){URL.revokeObjectURL(url);throw error;}
}
function start(){if(frames.length<2)return;playing=true;$('play').textContent='Ⅱ';$('play').setAttribute('aria-label','暂停');schedule();}
function schedule(){clearTimeout(timer);if(!playing)return;timer=setTimeout(async()=>{try{await showFrame((index+1)%frames.length);if(playing)schedule();}catch(error){stop();notify(error.message);}},1600/Number($('speed').value));}
function locate(region){currentRegion=region;const positions={china:[105,30,11500000],disk:[105,0,21500000],mozambique:[35,-19,6000000]};viewer.camera.flyTo({destination:Cesium.Cartesian3.fromDegrees(...positions[region]),duration:1.2});document.querySelectorAll('[data-region]').forEach(b=>b.classList.toggle('active',b.dataset.region===region));}
async function init(){
  if(!globalThis.Cesium){window.mapLoading.fail('地图组件加载失败，请检查网络后重试。');$('data-status').textContent='地图组件加载失败，请检查网络并刷新页面';return;}
  window.mapLoading.stage(0,'正在准备世界底图…');
  const response=await fetch('/api/v1/products');
  if(!response.ok)throw Error('产品目录加载失败');
  const catalog=await response.json();
  products.splice(0,products.length,...expandCatalog(catalog));
  product=products.find(p=>p.id==='CTH'&&!p.draft)||products.find(p=>!p.draft);
  if(!product)throw Error('暂无已上架产品');
  const world=await createWorldBasemap();
  viewer=new Cesium.Viewer('map',{baseLayer:new Cesium.ImageryLayer(world.provider),sceneMode:Cesium.SceneMode.SCENE2D,mapProjection:new Cesium.WebMercatorProjection(),mapMode2D:Cesium.MapMode2D.INFINITE_SCROLL,baseLayerPicker:false,geocoder:false,homeButton:false,sceneModePicker:false,navigationHelpButton:false,animation:false,timeline:false,fullscreenButton:false,infoBox:false,selectionIndicator:false,requestRenderMode:true,terrainProvider:new Cesium.EllipsoidTerrainProvider()});
  base=vectorBase=viewer.imageryLayers.get(0);
  viewer.scene.backgroundColor=Cesium.Color.fromCssColorString('#08121b');viewer.scene.skyBox.show=false;viewer.scene.sun.show=false;viewer.scene.moon.show=false;viewer.scene.globe.baseColor=Cesium.Color.fromCssColorString('#172d3c');viewer.scene.globe.enableLighting=false;viewer.scene.globe.showGroundAtmosphere=false;viewer.resolutionScale=Math.min(window.devicePixelRatio,1.5);
  viewer.camera.setView({destination:Cesium.Cartesian3.fromDegrees(105,30,11500000)});
  Cesium.GeoJsonDataSource.load(world.geojson,{stroke:Cesium.Color.fromCssColorString('#a4bdc9').withAlpha(.6),strokeWidth:1,clampToGround:true}).then(ds=>{worldLines=ds;viewer.dataSources.add(ds);viewer.scene.requestRender();}).catch(()=>notify('国家边界线加载失败，底图仍可使用'));
  for(const id of ['CTH','CLP','PRECIP','BT108','RGB']){const p=products.find(x=>x.id===id&&!x.draft);if(!p)continue;const b=document.createElement('button');b.className='quick';b.dataset.id=id;const swatch=document.createElement('span');swatch.className='swatch';swatch.setAttribute('aria-hidden','true');const label=document.createElement('span'),small=document.createElement('small');label.textContent=p.name;small.textContent=p.en;label.append(small);b.append(swatch,label);b.onclick=()=>select(p);$('quick-products').append(b);}
  document.querySelectorAll('[data-region]').forEach(b=>b.onclick=()=>locate(b.dataset.region));
  document.querySelectorAll('[data-tab]').forEach(b=>b.onclick=()=>{catalogMode=b.dataset.tab;buildCatalog();});
  $('catalog-open').onclick=()=>openCatalog(product.mode);$('catalog-close').onclick=()=>{$('catalog').hidden=true;$('catalog-open').focus();};
  $('realtime-tab').onclick=()=>openCatalog('realtime');$('forecast-tab').onclick=()=>openCatalog('forecast');
  for(const name of ['settings','about'])$(name+'-open').onclick=()=>$(name).showModal();
  document.querySelectorAll('[data-close]').forEach(b=>b.onclick=()=>b.closest('dialog').close());
  $('play').onclick=()=>playing?stop():start();$('speed').onchange=()=>{if(playing)schedule();};$('autoplay').onchange=()=>{$('autoplay').checked?start():stop();};
  $('timeline').oninput=()=>{stop();$('selected-time').textContent=shortDate(frames[Number($('timeline').value)])+' UTC · 松开加载';};
  $('timeline').onchange=()=>showFrame(Number($('timeline').value)).catch(e=>notify(e.message));
  $('latest').onclick=()=>{stop();showFrame(frames.length-1).catch(e=>notify(e.message));};$('refresh').onclick=()=>select(product);
  $('opacity').oninput=()=>{$('opacity-value').textContent=$('opacity').value+'%';if(currentLayer)currentLayer.alpha=Number($('opacity').value)/100;viewer.scene.requestRender();};
  $('basemap').onchange=async()=>{
    const choice=$('basemap').value;
    try{
      if(['natural','muted'].includes(choice)&&!naturalBase){
        const provider=await Cesium.TileMapServiceImageryProvider.fromUrl(Cesium.buildModuleUrl('Assets/Textures/NaturalEarthII'),{maximumLevel:2});
        naturalBase=viewer.imageryLayers.addImageryProvider(provider,0);
      }
      const selected=$('basemap').value;
      vectorBase.show=selected==='vector';
      if(naturalBase){naturalBase.show=['natural','muted'].includes(selected);naturalBase.saturation=selected==='natural'?1:.35;naturalBase.brightness=selected==='natural'?1:.62;}
      viewer.scene.requestRender();
    }catch{notify('地形底图加载失败，保留现有底图');}
  };
  $('zoom-in').onclick=()=>{viewer.camera.zoomIn(viewer.camera.positionCartographic.height*.3);viewer.scene.requestRender();};$('zoom-out').onclick=()=>{viewer.camera.zoomOut(viewer.camera.positionCartographic.height*.3);viewer.scene.requestRender();};$('home').onclick=()=>locate('china');
  $('dimension').onclick=()=>{const to2d=viewer.scene.mode===Cesium.SceneMode.SCENE3D;if(to2d)viewer.scene.morphTo2D(1);else viewer.scene.morphTo3D(1);$('dimension').textContent=to2d?'3D':'2D';$('dimension').setAttribute('aria-label',to2d?'切换三维地球':'切换二维地图');};
  $('legend-image').onerror=()=>{if(['RGB','FRGB'].includes(product.id))return;$('legend-image').hidden=true;$('legend-note').textContent='原始色标暂时不可用';};
  document.addEventListener('keydown',e=>{if(e.key==='Escape')$('catalog').hidden=true;if(e.target.matches('input,select,button')||document.querySelector('dialog[open]'))return;if(e.code==='Space'){e.preventDefault();playing?stop():start();}if(e.key==='ArrowRight'||e.key==='ArrowLeft'){stop();showFrame(Math.max(0,Math.min(frames.length-1,index+(e.key==='ArrowRight'?1:-1)))).catch(err=>notify(err.message));}});
  document.addEventListener('visibilitychange',()=>{if(document.hidden)stop();});
  const picker=new Cesium.ScreenSpaceEventHandler(viewer.scene.canvas);
  picker.setInputAction(click=>{const world=viewer.camera.pickEllipsoid(click.position,viewer.scene.globe.ellipsoid);if(!world)return;const c=Cesium.Cartographic.fromCartesian(world);stop();pickAt(Cesium.Math.toDegrees(c.longitude),Cesium.Math.toDegrees(c.latitude));},Cesium.ScreenSpaceEventType.LEFT_CLICK);
  $('point-close').onclick=()=>{$('point-info').hidden=true;pickedPoint=null;if(pickMarker)viewer.entities.remove(pickMarker);viewer.scene.requestRender();};
  $('point-forecast').onclick=()=>{const next=products.find(p=>p.pathId===product.pathId&&p.mode==='forecast');if(next)select(next);else notify('该产品暂无可用预报');};
  select(product);
}
function pickAt(lon,lat){pickedPoint={lon,lat};if(pickMarker)viewer.entities.remove(pickMarker);pickMarker=viewer.entities.add({position:Cesium.Cartesian3.fromDegrees(lon,lat),point:{pixelSize:10,color:Cesium.Color.CYAN,outlineColor:Cesium.Color.WHITE,outlineWidth:2,disableDepthTestDistance:Number.POSITIVE_INFINITY}});$('point-info').hidden=false;updatePoint();viewer.scene.requestRender();}
async function updatePoint(){
 const request=++pointRequest;
 if(!pickedPoint)return;
 const {lon,lat}=pickedPoint,g=geometryFor(product.id);
 $('point-coordinate').textContent=`${Math.abs(lat).toFixed(3)}°${lat<0?'S':'N'}, ${Math.abs(lon).toFixed(3)}°${lon<0?'W':'E'}`;
 $('point-product').textContent=product.name;
 $('point-time').textContent=frames[index]?displayDate(frames[index]):'暂无图像';
 $('point-series').replaceChildren();
 $('point-forecast').hidden=!products.some(p=>p.pathId===product.pathId&&p.mode==='forecast');
 $('point-forecast').textContent='查看该产品完整预报 →';
 $('point-value').textContent=containsPoint(g.bounds,lon,lat)?'精确数值暂不可用':'该位置不在产品覆盖范围内';
 $('point-note').textContent=product.mode==='realtime'&&['CLP','CTH','COT','CER','CWP','CBH'].includes(product.id)?'该产品暂未接入数值查询；只显示位置与图像时次，不从颜色反推数值。':'该产品暂未接入数值查询；不从图像颜色反推，也不将缺测视为0。';
}
window.addEventListener('DOMContentLoaded',()=>{init().catch(error=>{window.mapLoading.fail('地图加载失败：'+error.message);$('data-status').textContent='地图初始化失败：'+error.message;});});
