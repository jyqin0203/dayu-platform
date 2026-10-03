import {loadAudits} from './audit-client.js';
import {timeInput} from './scientific-client.js';

/** 授权审计列表。分页使用已提交的条件快照，动态用途和文件名一律作为文本。 */
export function mountAudits(onDenied){
  const $=id=>document.getElementById(id),now=new Date();
  $('audit-to').value=timeInput(now,'UTC');$('audit-from').value=timeInput(new Date(now-30*86400000),'UTC');
  let generation=0,active,query=null,page=1;
  const states={REQUESTED:'已申请',AUTHORIZED:'已授权',DENIED:'已拒绝',DELIVERED:'已传输',INTERRUPTED:'传输中断'};
  function reset(){++generation;active?.abort();query=null;$('audit-list').replaceChildren();$('audit-page').textContent='';$('audit-prev').disabled=true;$('audit-next').disabled=true;}
  const snapshot=()=>({from:$('audit-from').value,to:$('audit-to').value,productCode:$('audit-product').value,userId:$('audit-user').value,organization:$('audit-organization').value});
  async function run(criteria,next=1){
    reset();const token=generation,controller=new AbortController();active=controller;
    $('audit-message').textContent='正在查询授权记录…';const timer=setTimeout(()=>controller.abort(),15000);
    try{
      const result=await loadAudits(criteria,next,fetch,controller.signal);if(token!==generation)return;
      const last=Math.max(1,Math.ceil(result.total/20));if(result.page>last)return run(criteria,last);
      query=criteria;page=result.page;
      for(const item of result.items){
        const card=document.createElement('article'),title=document.createElement('h3'),meta=document.createElement('p'),purpose=document.createElement('p');
        title.textContent=`#${item.downloadEventId} · ${item.fileName}`;
        meta.textContent=`${states[item.status]||item.status} · ${new Date(item.authorizedAt).toISOString().slice(0,19).replace('T',' ')} UTC\n用户 ID：${item.userId} · 机构：${item.organizationSnapshot||'—'}\n资产 ID：${item.assetId} · 产品：${(item.productSnapshot||[]).join(' / ')} · 预期大小：${(item.expectedBytes/1048576).toFixed(2)} MiB`;
        purpose.textContent='用途：'+item.purpose;card.append(title,meta,purpose);$('audit-list').append(card);
      }
      $('audit-message').textContent=result.total?`共 ${result.total} 条授权记录`:'未找到符合条件的授权记录';
      $('audit-page').textContent=`第 ${page} 页 / ${last} 页`;$('audit-prev').disabled=page<=1;$('audit-next').disabled=page*20>=result.total;
    }catch(error){if(token===generation){$('audit-message').textContent=error.name==='AbortError'?'查询超时，请重试':error.message;if([401,403].includes(error.status)){reset();onDenied(error.message);}}}
    finally{clearTimeout(timer);}
  }
  $('audit-form').onsubmit=e=>{e.preventDefault();run(snapshot());};
  $('audit-form').addEventListener('input',()=>{reset();$('audit-message').textContent='条件已改变，请重新查询';});
  $('audit-prev').onclick=()=>{if(query)run(query,page-1);};$('audit-next').onclick=()=>{if(query)run(query,page+1);};
  return {reset,load:()=>run(snapshot())};
}
