/** 旧文件名时间均为 UTC；严格校验，避免 2 月 30 日被 Date 自动滚动。 */
export function dateOf(stamp) {
  if (!/^\d{12}$/.test(stamp || '')) throw Error('数据时间格式无效');
  const iso=`${stamp.slice(0,4)}-${stamp.slice(4,6)}-${stamp.slice(6,8)}T${stamp.slice(8,10)}:${stamp.slice(10,12)}:00Z`;
  const date=new Date(iso);
  if (!Number.isFinite(date.getTime()) || date.toISOString().replace(/[-:T]/g,'').slice(0,12)!==stamp) throw Error('数据时间无效');
  return date;
}

/** 只取文件名内最后一个独立的 12 位时间；不把目录起报时间当作有效时间。 */
export function stamp(file) {
  if(typeof file!=='string')return undefined;
  return [...file.split('/').at(-1).matchAll(/(?<!\d)\d{12}(?!\d)/g)].at(-1)?.[0];
}

/** 输出有效时间与起报时间的小时差，不把当前时刻当作起报时间。 */
export function leadHours(file,cycle) {
  return (dateOf(stamp(file))-dateOf(cycle))/3600000;
}

/** 保留生产页面的 24h/30min 抽帧策略；这是显示窗口，不是服务器保留期限。 */
export function realtimeWindow(files) {
  if(!files.length)return [];
  const end=Math.floor(dateOf(stamp(files.at(-1))).getTime()/1800000)*1800000;
  return files.filter(file=>{
    const time=dateOf(stamp(file)).getTime();
    return time<=end&&time>end-86400000&&time%1800000===0;
  }).slice(-48);
}

/**
 * 输入目录条目与可注入 JSON 请求函数，输出独立的批次/有序文件快照。
 * 降水由三个物理目录读取；任一请求失败或批次不完整都拒绝展示半批数据。
 * 不读 NC、不扫描磁盘、不用当前时间截断未来预报。
 */
export async function loadTimeline(product,json,root='WebP/WebP_V2_Dpi500_4KM') {
  const forecast=product.mode==='forecast', code=product.pathId||product.id;
  let cycle='';
  const query=(endpoint,params)=>json('api/'+endpoint+'?'+new URLSearchParams(params));
  if(forecast){
    const response=await query('fcst_latest.php',{path:root+'/forecast',product:product.combined?'PRECIP_1H':code});
    cycle=response.latest;
    if(!cycle)throw Error('该预报产品暂无可用图像');
    dateOf(cycle);
  }
  const base=forecast?`${root}/forecast/${cycle}`:`${root}/realtime`;
  const directories=product.combined?[1,2,3].map(h=>`${base}/PRECIP_${h}H`):[`${base}/${code}`];
  const groups=await Promise.all(directories.map(async (directory,i)=>{
    const response=await query('files.php',{path:directory,number:200});
    if(!Array.isArray(response.files))throw Error('图像列表格式无效');
    return [...new Set(response.files)].filter(file=>{
      if(typeof file!=='string'||!file.startsWith(directory+'/'))return false;
      const name=file.slice(directory.length+1);
      if(!name.endsWith('.webp')||/[\/\\%?#]/.test(name))return false;
      try{
        dateOf(stamp(file));
        if(forecast){
          const lead=leadHours(file,cycle);
          if(lead<0 || (product.combined&&lead!==i+1))return false;
          const times=[...name.matchAll(/(?<!\d)\d{12}(?!\d)/g)].map(m=>m[0]);
          if(times.length>1 && times[0]!==cycle)return false;
        }
        return true;
      }catch{return false;}
    });
  }));
  if(product.combined&&groups.some(group=>group.length!==1))throw Error('降水预报批次不完整，请刷新后重试');
  const all=[...new Set(groups.flat())].sort((a,b)=>stamp(a).localeCompare(stamp(b))||a.localeCompare(b));
  return {cycle,all,frames:forecast?all:realtimeWindow(all)};
}
