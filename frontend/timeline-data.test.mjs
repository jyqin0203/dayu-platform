import test from 'node:test';
import assert from 'node:assert/strict';
import {dateOf,stamp,leadHours,realtimeWindow,loadTimeline} from './public/flat-20260925/timeline-data.js';
const root='WebP/WebP_V2_Dpi500_4KM',cycle='202610032300';
const rain=h=>`${root}/forecast/${cycle}/PRECIP_${h}H/FY4B_AGRI_REPPIC_PRECIP_${h}H_${cycle}_202610040${h-1}00_palettev2_Dpi500.webp`;
const product={pathId:'PRECIP',mode:'forecast',combined:true};
const rainJson=async resource=>{
  const url=new URL(resource,'http://localhost');
  if(url.pathname.endsWith('fcst_latest.php'))return {latest:cycle};
  const h=Number(url.searchParams.get('path').match(/PRECIP_(\d)H$/)[1]);
  return {files:[rain(h)]};
};
test('UTC 时间严格校验，目录时间不能冒充文件有效时间',()=>{
  assert.equal(dateOf(cycle).toISOString(),'2026-10-03T23:00:00.000Z');
  assert.throws(()=>dateOf('202602300000'));
  assert.throws(()=>dateOf('202610032400'));
  assert.equal(stamp('forecast/202610032300/no-time.webp'),undefined);
  assert.equal(stamp(rain(2)),'202610040100');
  assert.equal(leadHours(rain(2),cycle),2);
});
test('降水跨 UTC 日期加载三目录并保留未来有效时间',async()=>{
  const calls=[];
  const result=await loadTimeline(product,async url=>{calls.push(url);return rainJson(url);});
  assert.equal(result.cycle,cycle);
  assert.deepEqual(result.frames,[rain(1),rain(2),rain(3)]);
  assert.equal(calls.length,4);
  assert.deepEqual(result.frames.map(f=>leadHours(f,cycle)),[1,2,3]);
});
test('缺图、错时效、网络失败都不能显示半个降水批次',async()=>{
  for(const files of [[],[rain(2).replace('202610040100','202610040200')]]) {
    await assert.rejects(loadTimeline(product,async url=>url.includes('PRECIP_2H')?{files}:rainJson(url)),/不完整/);
  }
  await assert.rejects(loadTimeline(product,async url=>{
    if(url.includes('PRECIP_2H'))throw Error('network');return rainJson(url);
  }),/network/);
});
test('普通云预报排序去重并拒绝错目录、错日期、错起报',async()=>{
  const dir=`${root}/forecast/${cycle}/CTH/`;
  const first=dir+`CTH_${cycle}_202610040000.webp`,last=dir+`CTH_${cycle}_202610040100.webp`;
  const result=await loadTimeline({pathId:'CTH',mode:'forecast'},async url=>url.includes('fcst_latest')?{latest:cycle}:{files:[last,first,first,
    dir+'../CTH_202610040200.webp',dir+'bad_202602300000.webp',dir+'CTH_202610032200_202610040200.webp']});
  assert.deepEqual(result.frames,[first,last]);
});
test('实况保留原有 24h/30min 窗口，排除 15 分钟帧，不伪造缺帧',()=>{
  const files=['202610020030','202610020100','202610030000','202610030015','202610030030'].map(t=>`x_${t}.webp`);
  assert.deepEqual(realtimeWindow(files),[files[1],files[2],files[4]]);
  assert.deepEqual(realtimeWindow([]),[]);
});
test('实况列表空、格式异常、预报无批次和非法日期',async()=>{
  assert.deepEqual((await loadTimeline({pathId:'CTH',mode:'realtime'},async()=>({files:[]}))).frames,[]);
  await assert.rejects(loadTimeline({pathId:'CTH',mode:'realtime'},async()=>({})),/格式/);
  await assert.rejects(loadTimeline(product,async()=>({latest:''})),/暂无/);
  await assert.rejects(loadTimeline(product,async()=>({latest:'202602300000'})),/时间/);
});
test('并行加载的批次快照互不污染',async()=>{
  const later='202610040300';
  const [a,b]=await Promise.all([
    loadTimeline(product,rainJson),
    loadTimeline({pathId:'BT108',mode:'forecast'},async url=>url.includes('fcst_latest')?{latest:later}:{files:[]})
  ]);
  assert.equal(a.cycle,cycle);assert.equal(b.cycle,later);
  assert.equal(leadHours(a.frames[0],a.cycle),1);
});
