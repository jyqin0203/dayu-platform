import {createWorldBasemap} from './world-map.js';
import {geometryFor,containsPoint,unpad} from './product-geometry.js';
const $=id=>document.getElementById(id);
const products=[
  ['RGB','真彩色','Natural color',''],['FRGB','假彩色','False color',''],['CLP','云相态','Cloud phase',''],
  ['CTH','云顶高度','Cloud top height','km'],['CBH','云底高度','Cloud base height','km'],['COT','云光学厚度','Optical thickness',''],
  ['CER','云粒子有效半径','Effective radius','μm'],['CWP','云水路径','Cloud water path','kg/m²'],
  ['BT625','6.25 μm 亮温','Brightness temperature','K'],['BT695','6.95 μm 亮温','Brightness temperature','K'],['BT742','7.42 μm 亮温','Brightness temperature','K'],
  ['BT855','8.55 μm 亮温','Brightness temperature','K'],['BT108','10.8 μm 亮温','Brightness temperature','K'],['BT120','12.0 μm 亮温','Brightness temperature','K'],['BT133','13.3 μm 亮温','Brightness temperature','K'],
  ['PRECIP','雨雪降水','RePPIC-Net','mm/h'],
  ['PRECIP_1H','雨雪降水 · +1h','RePPIC-Net','mm/h','forecast'],['PRECIP_2H','雨雪降水 · +2h','RePPIC-Net','mm/h','forecast'],['PRECIP_3H','雨雪降水 · +3h','RePPIC-Net','mm/h','forecast']
].map(([id,name,en,unit,mode='realtime'])=>({id,name,en,unit,mode}));
for(const channel of ['625','695','742','855','108','120','133'])products.push({id:'FCST_BT'+channel,pathId:'BT'+channel,name:products.find(p=>p.id==='BT'+channel).name+' · 完整预报',en:'全部可用预报时效',unit:'K',mode:'forecast'});
products.push({id:'FCST_PRECIP',name:'雨雪降水 · 完整预报',en:'+1h / +2h / +3h',unit:'mm/h',mode:'forecast',combined:true});
products.push({id:'SAMPLE_PRECIP',name:'雨雪降水 · 历史样例',en:'2026-09-02 · 点击取值',unit:'mm/h',mode:'forecast',sample:true});
for(const [field,name] of [['CLP','云相态'],['CTH','云顶高度'],['CER','有效半径'],['COT','云光学厚度']])products.push({id:'GLOBAL_'+field,field,name:'全球'+name+' · 测试',en:'09-11 历史样例 · 原始值',unit:'原始值',mode:'realtime',globalSample:true});
for(let i=products.length-1;i>=0;i--)if(products[i].sample||products[i].globalSample)products.splice(i,1);
let globalManifest=null;
let sampleManifest=null,pointRequest=0;const pointCache=new Map();
let pickedPoint=null,pickMarker=null,forecastCycle='';
let viewer,base,vectorBase,naturalBase,worldLines,currentLayer,currentURL,product=products.find(p=>p.id==='CTH'),frames=[],index=0,generation=0,frameRequest=0,playing=false,timer,toastTimer;
let currentRegion='china',catalogMode='realtime';
const root='WebP/WebP_V2_Dpi500_4KM';
const read=resource=>'/'+resource;
const stamp=file=>(file.match(/\d{12}/g)||[]).at(-1);
const dateOf=s=>new Date(`${s.slice(0,4)}-${s.slice(4,6)}-${s.slice(6,8)}T${s.slice(8,10)}:${s.slice(10,12)}:00Z`);
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
  else $('legend-image').src=read(`CPP_Colorbar/horizontal/${product.id.includes('PRECIP')?'PRECIP_Colorbar_rainsnow_v2':(product.pathId||product.id)+'_Colorbar'}.webp`);
  if(product.sample)$('legend-image').src='samples/legend.webp';
  $('legend-note').textContent=isColor?'原始彩色合成影像 · 不使用数值色阶':rain?'雨 / 雪双配色 · 原始色标，未改变数值':'原始产品色标 · 未修改数值';
  document.querySelectorAll('[data-id]').forEach(b=>{b.classList.toggle('active',b.dataset.id===product.id);b.setAttribute('aria-pressed',String(b.dataset.id===product.id));});
  $('realtime-tab').classList.toggle('active',product.mode==='realtime');$('forecast-tab').classList.toggle('active',product.mode==='forecast');
  if(product.globalSample){$('legend-image').hidden=true;$('legend-image').removeAttribute('src');$('mode-label').textContent='全球多卫星 · 历史测试';$('legend-note').textContent=product.field==='CLP'?'原始类别码 0 / 1 / 2 · 类别定义待确认':'原始值色阶：紫 → 蓝 → 黄 → 红 · 单位待确认';}
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
  if(p.globalSample)return selectGlobal(p);
  window.mapLoading.stage(1,'正在读取'+p.name+'…');
  stop();const token=++generation;++frameRequest;product=p;frames=[];clearLayer();updateProductUI();
  $('forecast-leads').replaceChildren();$('forecast-leads').hidden=true;updatePoint();
  $('play').disabled=true;$('timeline').disabled=true;$('data-time').textContent='正在读取数据时间…';$('data-status').textContent='读取公开图像列表…';$('frame-count').textContent='0 帧';$('selected-time').textContent='等待图像';$('start-time').textContent='—';$('end-time').textContent='—';
  try{
    let directory=`${root}/realtime/${p.pathId||p.id}`;forecastCycle='';
    if(p.sample){sampleManifest=await (await fetch('samples/reppic.json')).json();forecastCycle=sampleManifest.initialization;directory='samples';}
    else if(p.mode==='forecast'){
      const response=await json(`api/fcst_latest.php?path=${root}/forecast&product=${p.combined?'PRECIP_1H':p.pathId||p.id}`);
      if(token!==generation)return;
      if(!/^\d{12}$/.test(response.latest||''))throw Error('该预报产品暂无可用图像');
      forecastCycle=response.latest;directory=`${root}/forecast/${response.latest}/${p.pathId||p.id}`;
    }
    const response=p.sample?{files:sampleManifest.frames.map(f=>f.image)}:p.combined?{files:(await Promise.all([1,2,3].map(h=>json(`api/files.php?path=${root}/forecast/${forecastCycle}/PRECIP_${h}H&number=200`)))).flatMap(x=>x.files||[])}:await json(`api/files.php?path=${directory}&number=200`);
    if(token!==generation)return;
    const allowed=p.combined?`${root}/forecast/${forecastCycle}/PRECIP_`:directory+'/';
    const all=(response.files||[]).filter(f=>typeof f==='string'&&f.startsWith(allowed)&&stamp(f)).sort((a,b)=>stamp(a).localeCompare(stamp(b)));
    if(p.mode==='realtime' && all.length){
      const end=Math.floor(dateOf(stamp(all.at(-1))).getTime()/1800000)*1800000;
      frames=all.filter(f=>{const t=dateOf(stamp(f)).getTime();return t<=end&&t>end-86400000&&t%1800000===0;}).slice(-48);
    }else frames=all;
    if(!frames.length)throw Error('该产品当前没有可用图像');
    index=frames.length-1;$('timeline').max=frames.length-1;$('timeline').disabled=false;$('play').disabled=frames.length<2;
    const age=Math.round((Date.now()-dateOf(p.mode==='forecast'?forecastCycle:stamp(all.at(-1))).getTime())/60000);
    $('data-status').textContent=`${p.mode==='forecast'?'起报':'网站最新文件'}距今 ${Math.max(0,age)} 分钟${p.mode==='realtime'&&frames.length<48?` · 24小时窗口缺 ${48-frames.length} 帧`:''} · 手动刷新更新列表`;
    if(p.sample)$('data-status').textContent='历史调试样例 · 2026-09-02起报 · 非实时 · 点击地图读取原始数值';
    $('window-label').textContent=p.mode==='realtime'?'最近24小时 · 每30分钟':`起报 ${dateOf(forecastCycle).toISOString().slice(5,16).replace('T',' ')} UTC · ${frames.length} 个时效`;
    $('forecast-leads').replaceChildren();$('forecast-leads').hidden=p.mode!=='forecast';
    if(p.mode==='forecast')frames.forEach((f,i)=>{const b=document.createElement('button');b.textContent='+'+((dateOf(stamp(f))-dateOf(forecastCycle))/3600000)+'h';b.onclick=()=>{stop();showFrame(i).catch(e=>notify(e.message));};$('forecast-leads').append(b);});
    $('start-time').textContent=shortDate(frames[0]);$('end-time').textContent=shortDate(frames.at(-1));
    document.querySelector('.ticks').hidden=p.mode==='forecast';
    window.mapLoading.stage(2,'正在渲染地图与首帧数据…');
    await showFrame(index,token);
    if(token===generation)window.mapLoading.ready();
    if(p.sample && token===generation && !pickedPoint)pickAt(sampleManifest.example.lon,sampleManifest.example.lat);
    if(token===generation && $('autoplay').checked && frames.length>1)start();
  }catch(error){if(token!==generation)return;window.mapLoading.ready();$('data-time').textContent='暂无可展示数据';$('data-status').textContent=error.message;$('selected-time').textContent='数据不可用';notify(error.message);}
}
async function selectGlobal(p){
  stop();const token=++generation;++frameRequest;product=p;frames=[];clearLayer();updateProductUI();
  window.mapLoading.stage(1,'正在读取全球云产品样例…');
  $('forecast-leads').replaceChildren();$('forecast-leads').hidden=true;$('play').disabled=true;$('timeline').disabled=true;
  try{
    if(!globalManifest){const r=await fetch('global-samples/manifest.json');if(!r.ok)throw Error('全球样例尚未准备好');globalManifest=await r.json();}
    if(token!==generation)return;
    frames=[globalManifest.products[p.field].image];index=0;$('timeline').max=0;
    $('data-status').textContent='历史测试 · 2026-09-11 04:15 UTC · 非实时 · 原始值未缩放';
    $('window-label').textContent='全球多卫星 · 单时次样例（无预报）';$('start-time').textContent=shortDate(frames[0]);$('end-time').textContent=shortDate(frames[0]);document.querySelector('.ticks').hidden=true;
    const [lo,hi]=globalManifest.products[p.field].limits;
    $('legend-note').textContent=p.field==='CLP'?'蓝灰=0 / 青=1 / 橙=2 · 类别定义待确认':`显示范围 ${lo}–${hi}（超界饱和）；紫 → 蓝 → 黄 → 红；查询保留完整原始值，单位待确认`;
    window.mapLoading.stage(2,'正在渲染全球云产品…');await showFrame(0,token);
    if(token!==generation)return;window.mapLoading.ready();pickAt(pickedPoint?.lon??121.48,pickedPoint?.lat??31.23);
  }catch(e){if(token!==generation)return;window.mapLoading.ready();$('data-status').textContent=e.message;$('data-time').textContent='暂无数据';notify(e.message);}
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
    if(product.sample||product.globalSample)await new Promise(resolve=>{const started=performance.now();const poll=()=>{viewer.scene.requestRender();if((performance.now()-started>150&&viewer.scene.globe.tilesLoaded)||performance.now()-started>8000)resolve();else setTimeout(poll,80);};setTimeout(poll,80);});
    const retire=()=>{if(previous&&viewer.imageryLayers.contains(previous))viewer.imageryLayers.remove(previous,true);if(previousURL)URL.revokeObjectURL(previousURL);viewer.scene.requestRender();};if(product.sample)retire();else setTimeout(retire,250);
    return true;
  }catch(error){URL.revokeObjectURL(url);throw error;}
}
function start(){if(frames.length<2)return;playing=true;$('play').textContent='Ⅱ';$('play').setAttribute('aria-label','暂停');schedule();}
function schedule(){clearTimeout(timer);if(!playing)return;timer=setTimeout(async()=>{try{await showFrame((index+1)%frames.length);if(playing)schedule();}catch(error){stop();notify(error.message);}},1600/Number($('speed').value));}
function locate(region){currentRegion=region;const positions={china:[105,30,11500000],disk:[105,0,21500000],mozambique:[35,-19,6000000]};viewer.camera.flyTo({destination:Cesium.Cartesian3.fromDegrees(...positions[region]),duration:1.2});document.querySelectorAll('[data-region]').forEach(b=>b.classList.toggle('active',b.dataset.region===region));}
async function init(){
  if(!globalThis.Cesium){window.mapLoading.fail('地图组件加载失败，请检查网络后重试。');$('data-status').textContent='地图组件加载失败，请检查网络并刷新页面';return;}
  window.mapLoading.stage(0,'正在准备世界底图…');
  const response=await fetch('/api/products.php');
  if(!response.ok)throw Error('产品目录加载失败');
  const catalog=await response.json();if(!catalog.ok||!Array.isArray(catalog.products))throw Error('产品目录不可用');
  const defaults=new Map(products.map(p=>[p.id,p]));
  products.splice(0,products.length,...catalog.products.filter(p=>p.status==='active'||p.status==='draft').map(p=>({...defaults.get(p.product_id),id:p.product_id,pathId:p.path_id,name:p.name_zh,en:p.name_en,mode:p.category,unit:defaults.get(p.product_id)?.unit||'',draft:p.status!=='active'})));
  product=products.find(p=>p.id==='CTH'&&!p.draft)||products.find(p=>!p.draft);
  if(!product)throw Error('暂无已上架产品');
  const world=await createWorldBasemap();
  viewer=new Cesium.Viewer('map',{baseLayer:new Cesium.ImageryLayer(world.provider),sceneMode:Cesium.SceneMode.SCENE2D,mapProjection:new Cesium.WebMercatorProjection(),mapMode2D:Cesium.MapMode2D.INFINITE_SCROLL,baseLayerPicker:false,geocoder:false,homeButton:false,sceneModePicker:false,navigationHelpButton:false,animation:false,timeline:false,fullscreenButton:false,infoBox:false,selectionIndicator:false,requestRenderMode:true,terrainProvider:new Cesium.EllipsoidTerrainProvider()});
  base=vectorBase=viewer.imageryLayers.get(0);
  viewer.scene.backgroundColor=Cesium.Color.fromCssColorString('#08121b');viewer.scene.skyBox.show=false;viewer.scene.sun.show=false;viewer.scene.moon.show=false;viewer.scene.globe.baseColor=Cesium.Color.fromCssColorString('#172d3c');viewer.scene.globe.enableLighting=false;viewer.scene.globe.showGroundAtmosphere=false;viewer.resolutionScale=Math.min(window.devicePixelRatio,1.5);
  viewer.camera.setView({destination:Cesium.Cartesian3.fromDegrees(105,30,11500000)});
  Cesium.GeoJsonDataSource.load(world.geojson,{stroke:Cesium.Color.fromCssColorString('#a4bdc9').withAlpha(.6),strokeWidth:1,clampToGround:true}).then(ds=>{worldLines=ds;viewer.dataSources.add(ds);viewer.scene.requestRender();}).catch(()=>notify('国家边界线加载失败，底图仍可使用'));
  for(const id of ['CTH','CLP','PRECIP','BT108','RGB']){const p=products.find(x=>x.id===id&&!x.draft);if(!p)continue;const b=document.createElement('button');b.className='quick';b.dataset.id=id;b.innerHTML='<span class="swatch" aria-hidden="true"></span><span>'+p.name+'<small>'+p.en+'</small></span>';b.onclick=()=>select(p);$('quick-products').append(b);}
  document.querySelectorAll('[data-region]').forEach(b=>b.onclick=()=>locate(b.dataset.region));
  document.querySelectorAll('[data-tab]').forEach(b=>b.onclick=()=>{catalogMode=b.dataset.tab;buildCatalog();});
  $('catalog-open').onclick=()=>openCatalog(product.mode);$('catalog-close').onclick=()=>{$('catalog').hidden=true;$('catalog-open').focus();};
  $('realtime-tab').onclick=()=>openCatalog('realtime');$('forecast-tab').onclick=()=>openCatalog('forecast');
  for(const name of ['settings','about','account'])$(name+'-open').onclick=()=>$(name).showModal();
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
  $('point-forecast').onclick=()=>{const id=product.id.includes('PRECIP')?'PRECIP_1H':product.id.startsWith('FCST_')?product.id:'FCST_'+product.id;const next=products.find(p=>(p.id===id||p.pathId===id)&&p.mode==='forecast'&&!p.draft);if(next)select(next);else notify('该产品暂无可用预报');};
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
 $('point-series').replaceChildren();$('point-forecast').textContent=product.sample?'定位降水样例点':'查看亮温完整预报 →';
 $('point-forecast').hidden=!product.id.includes('PRECIP')&&!product.id.includes('BT');
 $('point-forecast').textContent='查看该产品完整预报 →';
 if(product.globalSample){
   $('point-value').textContent='读取原始网格…';$('point-note').textContent='';
   const field=product.field;
   try{
     const response=await fetch(`/global/point?lon=${lon}&lat=${lat}`);if(!response.ok)throw Error('数值查询失败');const result=await response.json();
     if(request!==pointRequest||!pickedPoint)return;
     if(result.status==='outside'){$('point-value').textContent='超出南北纬70°覆盖范围';return;}
     const val=result.values[field];$('point-value').textContent=val===null?'无有效数据':`${field} = ${val.toFixed(3)}（原始值）`;
     $('point-note').textContent=`历史测试 · 0.04°最近邻网格 ${result.grid.latitude.toFixed(2)}°, ${result.grid.longitude.toFixed(2)}° · QA=${result.qa}，SourceMask=${result.sourceMask} · 单位及质量码定义待确认，不套用旧FY-4B单位`;
     for(const [key,value] of Object.entries(result.values)){const line=document.createElement('div');line.className='series-row';line.textContent=`${key}：${value===null?'无数据':value.toFixed(3)}`;$('point-series').append(line);}
   }catch(e){if(request===pointRequest){$('point-value').textContent='查询失败';$('point-note').textContent=e.message;}}
   return;
 }
 if(product.sample){
   $('point-value').textContent='读取原始网格…';$('point-note').textContent='历史样例 · 正在查询 +1、+2、+3 小时';
   try{
     const key=lon.toFixed(5)+','+lat.toFixed(5);let result=pointCache.get(key);
     if(!result){const response=await fetch(`/sample/point?lon=${lon}&lat=${lat}`);if(!response.ok)throw Error('原始数值查询失败');result=await response.json();if(pointCache.size>32)pointCache.clear();pointCache.set(key,result);}
     if(request!==pointRequest||!pickedPoint)return;
     if(result.status==='outside'){$('point-value').textContent='超出产品覆盖范围';$('point-note').textContent='样例覆盖 60–180°E、60°S–60°N';return;}
     const phaseName=v=>v.rate===null?'无数据':v.rate<.1?'无显著降水':v.phase===1?'雪':v.phase===2?'雨':'未分类';
     const active=result.series[index]??result.series[0];$('point-value').textContent=!active||active.rate===null?'无数据':`${active.rate.toFixed(3)} mm/h · ${phaseName(active)}`;
     $('point-note').textContent=`历史样例 · 最近邻网格 ${result.grid.latitude.toFixed(2)}°, ${result.grid.longitude.toFixed(2)}° · 0.05° · 直接读取NetCDF数值`;
     const max=Math.max(.1,...result.series.map(v=>v.rate??0));
     result.series.forEach((v,i)=>{const button=document.createElement('button');button.className='series-row'+(i===index?' active':'');const text=document.createElement('span');text.textContent=`+${v.lead}h　${v.rate===null?'无数据':v.rate.toFixed(3)+' mm/h'}　${phaseName(v)}`;const bar=document.createElement('i');bar.style.width=((v.rate??0)/max*100)+'%';button.append(text,bar);button.onclick=()=>{stop();showFrame(i).catch(e=>notify(e.message));};$('point-series').append(button);});
   }catch(error){if(request===pointRequest){$('point-value').textContent='查询失败';$('point-note').textContent=error.message;}}
   return;
 }
 $('point-value').textContent=containsPoint(g.bounds,lon,lat)?'精确数值暂不可用':'该位置不在产品覆盖范围内';
 $('point-note').textContent=product.mode==='realtime'&&['CLP','CTH','COT','CER','CWP','CBH'].includes(product.id)?'该产品暂未接入数值查询；只显示位置与图像时次，不从颜色反推数值。':'该产品暂未接入数值查询；不从图像颜色反推，也不将缺测视为0。';
}
window.addEventListener('DOMContentLoaded',()=>{init().catch(error=>{window.mapLoading.fail('地图加载失败：'+error.message);$('data-status').textContent='地图初始化失败：'+error.message;});});
