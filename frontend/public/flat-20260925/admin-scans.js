import {createScanClient} from './scan-client.js';

/** 绑定现有管理页；reset 使迟到响应失效，onDenied 清除整个管理概览。 */
export function mountScans(onDenied){
  const $=id=>document.getElementById(id),api=createScanClient();
  const names={RUNNING:'运行中',SUCCEEDED:'成功',PARTIAL:'部分成功',FAILED:'失败'};
  const utc=value=>value?new Date(value).toISOString().slice(0,19).replace('T',' ')+' UTC':'—';
  let generation=0,listRequest=0,detailRequest=0,page=1,selected=null,submitting=false;
  function error(e){$('scans-status').textContent=e.message;if([401,403].includes(e.status))onDenied(e.message);}
  function reset(){++generation;++listRequest;++detailRequest;selected=null;$('scans-list').replaceChildren();$('scans-detail').replaceChildren();$('scans-page').textContent='';$('scans-prev').disabled=true;$('scans-next').disabled=true;$('scans-detail-refresh').disabled=true;}
  async function detail(id){
    const token=generation,request=++detailRequest;selected=id;$('scans-detail').replaceChildren();$('scans-status').textContent='正在读取任务…';
    try{
      const task=await api.detail(id);if(token!==generation||request!==detailRequest)return;
      const summary=document.createElement('p');
      summary.textContent=`任务 #${task.scanRunId} · ${names[task.status]||task.status}\n开始：${utc(task.startedAt)}\n结束：${utc(task.finishedAt)}\n扫描 ${task.scannedFiles} · 新增 ${task.createdAssets} · 更新 ${task.updatedAssets} · 撤下图片索引 ${task.removedWebpAssets} · 缺失 NC ${task.missingNetcdfAssets} · 错误 ${task.errorCount}`;
      $('scans-detail').append(summary);
      for(const item of task.errors||[]){const p=document.createElement('p');p.textContent=`${item.relativePath||'存储目录'} · ${item.errorCode} · ${item.safeMessage}`;$('scans-detail').append(p);}
      $('scans-detail-refresh').disabled=false;$('scans-status').textContent=task.status==='RUNNING'?'任务运行中，可刷新状态':'任务状态已更新';
    }catch(e){if(token===generation&&request===detailRequest)error(e);}
  }
  async function history(next=1){
    ++detailRequest;selected=null;$('scans-detail').replaceChildren();$('scans-detail-refresh').disabled=true;
    const token=generation,request=++listRequest;$('scans-prev').disabled=true;$('scans-next').disabled=true;$('scans-list').replaceChildren();$('scans-page').textContent='';
    $('scans-status').textContent='正在读取扫描记录…';
    try{
      const result=await api.history(next);if(token!==generation||request!==listRequest)return;
      const last=Math.max(1,Math.ceil(result.total/20));if(result.page>last)return history(last);page=result.page;
      for(const task of result.items){const row=document.createElement('tr');for(const value of [task.scanRunId,task.trigger==='MANUAL'?'手动':task.trigger==='SCHEDULED'?'定时':'启动',names[task.status]||task.status,utc(task.startedAt)]){const td=document.createElement('td');td.textContent=value;row.append(td);}const td=document.createElement('td'),button=document.createElement('button');button.textContent='查看 #'+task.scanRunId;button.onclick=()=>detail(task.scanRunId);td.append(button);row.append(td);$('scans-list').append(row);}
      $('scans-page').textContent=`第 ${page} 页 / ${Math.max(1,Math.ceil(result.total/20))} 页`;$('scans-prev').disabled=page<=1;$('scans-next').disabled=page*20>=result.total;
      $('scans-status').textContent=result.total?`共 ${result.total} 条扫描记录`:'暂无扫描记录';
    }catch(e){if(token===generation&&request===listRequest)error(e);}
  }
  $('scans-start').onclick=async()=>{
    if(submitting||!confirm('确认扫描已配置的数据目录并更新索引？不会删除原始文件。'))return;
    const token=generation;submitting=true;$('scans-start').disabled=true;$('scans-status').textContent='正在提交扫描任务…';
    try{const task=await api.start();if(token!==generation)return;await history(1);if(token===generation)await detail(task.scanRunId);}
    catch(e){if(token===generation)error(e);}
    finally{submitting=false;$('scans-start').disabled=false;}
  };
  $('scans-refresh').onclick=()=>history(page);$('scans-prev').onclick=()=>history(page-1);$('scans-next').onclick=()=>history(page+1);
  $('scans-detail-refresh').onclick=()=>{if(selected)detail(selected);};
  return {reset,load:()=>history(1)};
}
