import {createWorldBasemap} from './world-map.js';
import {expandCatalog,createColorbarCache} from './catalog-adapter.js';
import {dateOf,stamp,leadHours,loadTimeline} from './timeline-data.js';
import {geometryFor,containsPoint} from './product-geometry.js';
import {prepareFrame,cachedFrame,prefetchFrames,cancelPendingFrames,clearFrameCache,refreshFrameVersions} from './frame-cache.js';
import {reconcileTimeline} from './timeline-refresh.js';
import {configureMapInteraction} from './map-interaction.js';
import {createFrameLayers} from './frame-layers.js';
const $=id=>document.getElementById(id);
const products=[];
let pointRequest=0;
let pickedPoint=null,pickMarker=null,forecastCycle='';
let viewer,base,vectorBase,naturalBase,worldLines,currentLayer,displayedTexture,product,frames=[],index=0,generation=0,frameRequest=0,playing=false,timer,toastTimer;
let currentRegion='china',catalogMode='realtime',initialLoad=true,switching=false,prefetchGeneration=0;
const productSnapshots=new Map();
let refreshTimer,refreshController,latestCycleAvailable=false;
let selectionController,frameLayers,scrubTimer,scrubbing=false,scrubTarget=0,prefetchDirection=1,legendGeneration=0,playbackRun=0;
const loadColorbar=createColorbarCache();
const REFRESH_INTERVAL=3*60*1000;
const root='WebP/WebP_V2_Dpi500_4KM';
const read=resource=>'/'+resource;
const displayDate=file=>dateOf(stamp(file)).toISOString().slice(0,16).replace('T',' ')+' UTC';
const shortDate=file=>displayDate(file).slice(5,16);
function notify(message){$('toast').textContent=message;$('toast').hidden=false;clearTimeout(toastTimer);toastTimer=setTimeout(()=>$('toast').hidden=true,4000);}
async function json(resource,signal){const response=await fetch(read(resource),{cache:'no-store',signal});if(!response.ok)throw Error('数据接口不可用');return response.json();}
function stop(){playing=false;++playbackRun;++frameRequest;clearTimeout(timer);$('play').textContent='▶';$('play').setAttribute('aria-label','播放');}
function updateProductUI(refresh=false){
  const legendToken=++legendGeneration;
  $('product-title').textContent=product.name;$('mode-label').textContent=product.mode==='forecast'?'预报产品':'卫星实况';
  $('legend-title').textContent=product.name;$('legend-unit').textContent=product.unit;
  const isColor=['RGB','FRGB'].includes(product.id), rain=product.id.includes('PRECIP');
  $('legend-image').hidden=isColor;
  if(isColor)$('legend-image').removeAttribute('src');
  else {
    const selected=product;
    $('legend-image').hidden=true;$('legend-image').removeAttribute('src');
    loadColorbar(product.pathId||product.id,refresh).then(url=>{
      if(product!==selected||legendToken!==legendGeneration)return;
      if(url){$('legend-image').hidden=false;$('legend-image').src=url;}
      else $('legend-note').textContent='该产品未配置色标';
    }).catch(()=>{if(product===selected&&legendToken===legendGeneration)$('legend-note').textContent='产品色标配置暂时不可用';});
  }

  $('legend-note').textContent=isColor?'原始彩色合成影像 · 不使用数值色阶':rain?'雨 / 雪双配色 · 原始色标，未改变数值':'';
  updateProductSelection();
}
function updateProductSelection(){
  document.querySelectorAll('[data-id]').forEach(b=>{b.classList.toggle('active',b.dataset.id===product.id);b.setAttribute('aria-pressed',String(b.dataset.id===product.id));});
  $('realtime-tab').classList.toggle('active',product.mode==='realtime');$('forecast-tab').classList.toggle('active',product.mode==='forecast');

}
function buildCatalog(){
  $('product-list').replaceChildren();
  for(const p of products.filter(p=>p.mode===catalogMode)){
    const b=document.createElement('button');b.dataset.id=p.id;b.textContent=p.name;
    if(p.draft){b.disabled=true;b.textContent+='（准备中）';}const small=document.createElement('small');small.textContent=p.en;b.append(small);b.onclick=()=>{select(p);$('catalog').hidden=true;};$('product-list').append(b);
  }

  document.querySelectorAll('[data-tab]').forEach(b=>b.classList.toggle('active',b.dataset.tab===catalogMode));updateProductSelection();
}
function openCatalog(mode){catalogMode=mode;buildCatalog();$('catalog').hidden=false;$('catalog-close').focus();}
async function select(p,refresh=false){
  if(!refresh&&!switching&&currentLayer&&p.id===product.id)return;
  selectionController?.abort();
  const controller=new AbortController();selectionController=controller;
  const timeout=setTimeout(()=>controller.abort(),15000);
  clearTimeout(scrubTimer);scrubbing=false;
  stop();cancelAutoRefresh();latestCycleAvailable=false;switching=true;const token=++generation;++frameRequest;++prefetchGeneration;cancelPendingFrames();
  if(refresh){clearFrameCache();productSnapshots.delete(p.id);}
  if(initialLoad)window.mapLoading.stage(1,'正在读取'+p.name+'…');
  $('data-status').textContent=`正在加载 ${p.name}…${currentLayer?' · 当前仍显示 '+product.name:''}`;
  $('play').disabled=true;$('timeline').disabled=true;
  try{
    const cached=productSnapshots.get(p.id);
    // Revalidate lists on each visit, while decoded frames remain reusable. Manual refresh also evicts pixels.
    const snapshot=cached&&!refresh&&Date.now()-cached.loadedAt<30000?cached:await loadTimeline(p,resource=>json(resource,controller.signal),root);
    clearTimeout(timeout);
    if(token!==generation)return;
    if(!snapshot.frames.length)throw Error('该产品当前没有可用图像');
    const target=snapshot.frames.length-1;
    const texture=await prepareFrame(snapshot.frames[target],p.id);
    if(token!==generation)return;
    const ready=await presentTexture(texture,p,()=>token===generation);
    if(!ready)return;
    product=p;frames=snapshot.frames;forecastCycle=snapshot.cycle;index=target;
    productSnapshots.set(p.id,{...snapshot,loadedAt:snapshot.loadedAt||Date.now()});
    updateProductUI(refresh);
    updateTimelineUI(snapshot);
    switching=false;updateFrameUI();warmFrames();
    if(initialLoad){initialLoad=false;window.mapLoading.ready();}
    if($('autoplay').checked&&frames.length>1)start();
    scheduleAutoRefresh();
  }catch(error){
    if(token!==generation)return;
    switching=false;
    if(initialLoad){initialLoad=false;window.mapLoading.ready();}
    $('play').disabled=frames.length<2;$('timeline').disabled=!frames.length;
    $('data-status').textContent=`${p.name} 加载失败${currentLayer?'，保留当前画面':''}：${error.message}`;notify(error.message);
    if(frames.length)scheduleAutoRefresh();
  }finally{clearTimeout(timeout);if(selectionController===controller)selectionController=null;}
}
function updateTimelineUI(snapshot){
  const p=product;
  $('timeline').max=frames.length-1;$('timeline').disabled=false;$('play').disabled=frames.length<2;
  $('data-status').textContent='';
  $('window-label').textContent=p.mode==='realtime'?'最近24小时 · 每30分钟':`起报 ${dateOf(forecastCycle).toISOString().slice(5,16).replace('T',' ')} UTC · ${frames.length} 个时效`;
  $('forecast-leads').replaceChildren();$('forecast-leads').hidden=p.mode!=='forecast';
  if(p.mode==='forecast')frames.forEach((f,i)=>{const b=document.createElement('button');b.textContent='+'+leadHours(f,forecastCycle)+'h';b.onclick=()=>{stop();showFrame(i).catch(e=>notify(e.message));};$('forecast-leads').append(b);});
  $('start-time').textContent=shortDate(frames[0]);$('end-time').textContent=shortDate(frames.at(-1));
  document.querySelector('.ticks').hidden=p.mode==='forecast';
}
function cancelAutoRefresh(){clearTimeout(refreshTimer);const pending=refreshController;refreshController=null;pending?.abort();}
function scheduleAutoRefresh(delay=REFRESH_INTERVAL){
  clearTimeout(refreshTimer);
  if(!document.hidden)refreshTimer=setTimeout(refreshTimeline,delay);
}
async function refreshTimeline(){
  if(document.hidden||switching||scrubbing||!frames.length){scheduleAutoRefresh();return;}
  const token=generation,p=product,controller=new AbortController();
  refreshController=controller;
  const timeout=setTimeout(()=>controller.abort(),15000);
  try{
    const snapshot=await loadTimeline(p,resource=>json(resource,controller.signal),root);
    if(token!==generation||controller.signal.aborted)return;
    if(!snapshot.frames.length)throw Error('最新列表暂时为空');
    const planCycle=reconcileTimeline(frames[index],snapshot,forecastCycle);
    // Never silently exchange forecast cycles while the user is comparing lead times.
    const versionFiles=planCycle.newCycle?frames:snapshot.frames;
    const changed=await refreshFrameVersions(p.id,versionFiles,controller.signal);
    if(token!==generation||controller.signal.aborted)return;
    if(planCycle.newCycle){
      latestCycleAvailable=true;
      $('data-status').textContent='发现新预报批次 · 当前批次保持不变，点击“最新”切换';
      if(changed.has(frames[index])&&!playing)await showFrame(index,generation,true);
      return;
    }
    const previous=frames[index],request=++frameRequest;
    const plan=reconcileTimeline(previous,snapshot,forecastCycle),file=snapshot.frames[plan.index];
    const texture=await prepareFrame(file,p.id);
    if(file!==previous||texture!==displayedTexture){
      const current=()=>token===generation&&request===frameRequest&&!controller.signal.aborted;
      if(!current()||!await presentTexture(texture,p,current))return;
    }
    if(token!==generation||request!==frameRequest||controller.signal.aborted)return;
    frames=snapshot.frames;index=plan.index;forecastCycle=snapshot.cycle;
    productSnapshots.set(p.id,{...snapshot,loadedAt:Date.now()});
    updateTimelineUI(snapshot);updateFrameUI();warmFrames();
    if(file!==previous)notify('原时次已移出可用窗口，已显示最近的可用时次');
  }catch(error){
    if(token===generation&&!document.hidden){
      $('data-status').textContent='自动更新暂未完成，保留当前画面 · 3分钟后重试';
    }
  }finally{
    clearTimeout(timeout);
    if(refreshController===controller){refreshController=null;if(token===generation)scheduleAutoRefresh();}
  }
}
function updateFrameUI(){
  // Notify Copilot only of the frame actually displayed, never a pending scrub target.
  window.dispatchEvent(new CustomEvent('dayu:visible-time',{detail:dateOf(stamp(frames[index])).toISOString()}));
  if(!scrubbing)$('timeline').value=index;
  $('data-time').textContent=displayDate(frames[index]);
  $('selected-time').textContent=shortDate(frames[index])+' UTC';$('frame-count').textContent=`${index+1} / ${frames.length} 帧`;
  [...$('forecast-leads').children].forEach((b,j)=>b.classList.toggle('active',j===index));updatePoint();
}
function warmFrames(){
  const token=++prefetchGeneration;
  const upcoming=[1,2,3].map(offset=>frames[(index+prefetchDirection*offset+frames.length)%frames.length]);
  void prefetchFrames([...new Set(upcoming)],product.id,()=>token===prefetchGeneration);
}
async function presentTexture(texture,p,isCurrent){
  const layer=await frameLayers.present(texture,p,isCurrent,Number($('opacity').value)/100);
  if(!layer)return false;
  currentLayer=layer;displayedTexture=texture;return true;
}
async function showFrame(i,token=generation,force=false){
  if(switching||!frames[i])return false;
  const request=++frameRequest,p=product,file=frames[i];
  if(!force&&i===index&&currentLayer){updateFrameUI();return true;}
  try{
    const texture=await prepareFrame(file,p.id);
    if(token!==generation||request!==frameRequest)return false;
    if(!await presentTexture(texture,p,()=>token===generation&&request===frameRequest))return false;
    index=frames.indexOf(file);updateFrameUI();warmFrames();return true;
  }catch(error){
    if(token!==generation||request!==frameRequest||error.name==='AbortError')return false;
    throw error;
  }
}
function scrub(final=false){
  if(switching||!frames.length)return;
  const target=Number($('timeline').value);
  prefetchDirection=target<(scrubbing?scrubTarget:index)?-1:1;scrubTarget=target;
  stop();clearTimeout(scrubTimer);++prefetchGeneration;++frameRequest;cancelPendingFrames();
  scrubbing=!final;
  $('selected-time').textContent=shortDate(frames[target])+' UTC · 正在预览';
  const display=()=>showFrame(target).catch(error=>notify(error.message));
  if(final||cachedFrame(frames[target],product.id))void display();
  else scrubTimer=setTimeout(display,120);
}
function start(){if(playing||switching||frames.length<2)return;++playbackRun;clearTimeout(scrubTimer);scrubbing=false;prefetchDirection=1;warmFrames();playing=true;$('play').textContent='Ⅱ';$('play').setAttribute('aria-label','暂停');schedule();}
function schedule(delay=1600/Number($('speed').value)){clearTimeout(timer);if(!playing)return;const token=generation,run=playbackRun;timer=setTimeout(async()=>{const started=performance.now();try{await showFrame((index+1)%frames.length);if(playing&&token===generation&&run===playbackRun)schedule(Math.max(0,1600/Number($('speed').value)-(performance.now()-started)));}catch(error){if(token!==generation||error.name==='AbortError')return;stop();notify(error.message);}},delay);}
function locate(region){currentRegion=region;const positions={china:[105,30,11500000],disk:[105,0,21500000],mozambique:[35,-19,6000000]};viewer.camera.flyTo({destination:Cesium.Cartesian3.fromDegrees(...positions[region]),duration:1.2});document.querySelectorAll('[data-region]').forEach(b=>b.classList.toggle('active',b.dataset.region===region));}
async function init(){
  if(!globalThis.Cesium){window.mapLoading.fail('地图组件加载失败，请检查网络后重试。');$('data-status').textContent='地图组件加载失败，请检查网络并刷新页面';return;}
  window.mapLoading.stage(0,'正在准备世界底图…');
  const [response,world]=await Promise.all([fetch('/api/v1/products'),createWorldBasemap()]);
  if(!response.ok)throw Error('产品目录加载失败');
  const catalog=await response.json();
  products.splice(0,products.length,...expandCatalog(catalog));
  product=products.find(p=>p.id==='CTH'&&!p.draft)||products.find(p=>!p.draft);
  if(!product)throw Error('暂无已上架产品');
  viewer=new Cesium.Viewer('map',{baseLayer:new Cesium.ImageryLayer(world.provider),sceneMode:Cesium.SceneMode.SCENE2D,mapProjection:new Cesium.WebMercatorProjection(),mapMode2D:Cesium.MapMode2D.INFINITE_SCROLL,baseLayerPicker:false,geocoder:false,homeButton:false,sceneModePicker:false,navigationHelpButton:false,animation:false,timeline:false,fullscreenButton:false,infoBox:false,selectionIndicator:false,requestRenderMode:true,terrainProvider:new Cesium.EllipsoidTerrainProvider()});
  base=vectorBase=viewer.imageryLayers.get(0);frameLayers=createFrameLayers(viewer);
  viewer.scene.backgroundColor=Cesium.Color.fromCssColorString('#08121b');viewer.scene.skyBox.show=false;viewer.scene.sun.show=false;viewer.scene.moon.show=false;viewer.scene.globe.baseColor=Cesium.Color.fromCssColorString('#172d3c');viewer.scene.globe.enableLighting=false;viewer.scene.globe.showGroundAtmosphere=false;const configureBoundaries=configureMapInteraction(viewer);
  viewer.camera.setView({destination:Cesium.Cartesian3.fromDegrees(105,30,11500000)});
  world.loadBoundaries().then(geojson=>Cesium.GeoJsonDataSource.load(geojson,{stroke:Cesium.Color.fromCssColorString('#a4bdc9').withAlpha(.6),strokeWidth:1,clampToGround:false})).then(ds=>{worldLines=ds;configureBoundaries(ds);viewer.dataSources.add(ds);viewer.scene.requestRender();}).catch(()=>notify('国家边界线加载失败，底图仍可使用'));
  for(const id of ['CTH','CLP','PRECIP','BT108','RGB']){const p=products.find(x=>x.id===id&&!x.draft);if(!p)continue;const b=document.createElement('button');b.className='quick';b.dataset.id=id;const swatch=document.createElement('span');swatch.className='swatch';swatch.setAttribute('aria-hidden','true');const label=document.createElement('span'),small=document.createElement('small');label.textContent=p.name;small.textContent=p.en;label.append(small);b.append(swatch,label);b.onclick=()=>select(p);$('quick-products').append(b);}
  document.querySelectorAll('[data-region]').forEach(b=>b.onclick=()=>locate(b.dataset.region));
  document.querySelectorAll('[data-tab]').forEach(b=>b.onclick=()=>{catalogMode=b.dataset.tab;buildCatalog();});
  $('catalog-open').onclick=()=>openCatalog(product.mode);$('catalog-close').onclick=()=>{$('catalog').hidden=true;$('catalog-open').focus();};
  $('realtime-tab').onclick=()=>openCatalog('realtime');$('forecast-tab').onclick=()=>openCatalog('forecast');
  for(const name of ['settings','about'])$(name+'-open').onclick=()=>$(name).showModal();
  document.querySelectorAll('[data-close]').forEach(b=>b.onclick=()=>b.closest('dialog').close());
  $('play').onclick=()=>playing?stop():start();$('speed').onchange=()=>{if(playing){++playbackRun;schedule();}};$('autoplay').onchange=()=>{$('autoplay').checked?start():stop();};
  $('timeline').oninput=()=>scrub();
  $('timeline').onchange=()=>scrub(true);
  $('latest').onclick=()=>{if(latestCycleAvailable){select(product,true);return;}stop();showFrame(frames.length-1).catch(e=>notify(e.message));};$('refresh').onclick=()=>select(product,true);
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
  document.addEventListener('keydown',e=>{if(e.key==='Escape')$('catalog').hidden=true;if(e.target.matches('input,select,button')||document.querySelector('dialog[open]'))return;if(e.code==='Space'){e.preventDefault();playing?stop():start();}if(e.key==='ArrowRight'||e.key==='ArrowLeft'){stop();prefetchDirection=e.key==='ArrowRight'?1:-1;showFrame(Math.max(0,Math.min(frames.length-1,index+prefetchDirection))).catch(err=>notify(err.message));}});
  document.addEventListener('visibilitychange',()=>{if(document.hidden){stop();clearTimeout(scrubTimer);scrubbing=false;cancelAutoRefresh();}else scheduleAutoRefresh(0);});
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
 $('point-note').textContent=product.mode==='realtime'&&['CLP','CTH','COT','CER','CWP','CBH'].includes(product.id)?'':'该产品暂未接入数值查询；不从图像颜色反推，也不将缺测视为0。';
}
window.addEventListener('DOMContentLoaded',()=>{init().catch(error=>{window.mapLoading.fail('地图加载失败：'+error.message);$('data-status').textContent='地图初始化失败：'+error.message;});});
