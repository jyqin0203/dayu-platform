import {dateOf,stamp} from './timeline-data.js';
/** Preserve the displayed frame; if it expired, choose the closest remaining time. */
export function reconcileTimeline(previousFile,snapshot,previousCycle=''){
  if(!snapshot.frames.length)throw Error('最新列表暂时为空，保留当前画面');
  if(previousCycle&&snapshot.cycle!==previousCycle)return {newCycle:true,index:-1};
  const retained=snapshot.frames.indexOf(previousFile);
  if(retained>=0)return {newCycle:false,index:retained};
  // Filenames have UTC time stamps; compare parsed times rather than product/path ordering.
  const time=file=>dateOf(stamp(file)).getTime();
  const target=time(previousFile);
  let index=0;
  for(let i=1;i<snapshot.frames.length;i++)if(Math.abs(time(snapshot.frames[i])-target)<Math.abs(time(snapshot.frames[index])-target))index=i;
  return {newCycle:false,index};
}
