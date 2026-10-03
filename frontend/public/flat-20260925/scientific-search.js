import {zones,toUtc,timeInput,searchScientific} from './scientific-client.js';
import {openDownload} from './download-dialog.js';

/** 独立检索弹窗，保留地图状态；动态文件名只使用 textContent，禁止拼入 HTML。 */
export function openScientificSearch(){dialog.showModal();if(!catalog.length)loadCatalog();}
const dialog=document.createElement('dialog');dialog.id='scientific-search';dialog.setAttribute('aria-labelledby','science-title');
dialog.innerHTML=`<div class="drawer-heading"><h2 id="science-title">科学数据检索</h2><button id="science-close" type="button" aria-label="关闭检索">✕</button></div>
<form id="science-form"><div class="science-grid">
<label>产品<select id="science-product" required></select></label>
<label>模式<select id="science-mode" required></select></label>
<label>筛选与结果时区<select id="science-zone"><option value="UTC">UTC</option><option value="Asia/Shanghai">北京时间 UTC+8</option></select></label>
<label>有效时间起点<input id="science-from" type="datetime-local" required></label>
<label>有效时间终点<input id="science-to" type="datetime-local" required></label>
<label id="science-cycle-row" hidden>起报时间（可选）<input id="science-cycle" type="datetime-local"></label>
<label id="science-lead-row" hidden>预报时效（分钟，可选）<input id="science-lead" type="number" min="0" step="1" placeholder="例如 120 表示 +2h"></label>
</div><button id="science-submit" type="submit" disabled>检索 NC</button><button id="science-retry" type="button" hidden>重试产品目录</button></form>
<p id="science-status" role="status" aria-live="polite">正在准备产品目录…</p>
<div id="science-results"></div><div class="science-pages"><button id="science-prev" disabled>上一页</button><span id="science-page"></span><button id="science-next" disabled>下一页</button></div>`;
document.body.append(dialog);
// 检索是公开入口，不要求登录；仅选中文件下载时进入认证流程。
document.getElementById('science-open').addEventListener('click',openScientificSearch);
const $=id=>document.getElementById(id);
let catalog=[],zone='UTC',active=null,query=null,page=1,generation=0;
const now=new Date();$('science-to').value=timeInput(now,zone);$('science-from').value=timeInput(new Date(now-86400000),zone);
function clear(){++generation;active?.abort();$('science-results').replaceChildren();$('science-page').textContent='';$('science-prev').disabled=true;$('science-next').disabled=true;query=null;}
function modes(){
  $('science-mode').replaceChildren();
  for(const mode of catalog.find(p=>p.code===$('science-product').value)?.modes||[])$('science-mode').add(new Option(mode.dataMode==='FORECAST'?'预报':'实况',mode.dataMode));
  forecastFields();
}
function forecastFields(){const forecast=$('science-mode').value==='FORECAST';$('science-cycle-row').hidden=!forecast;$('science-lead-row').hidden=!forecast;}
async function loadCatalog(){
  $('science-submit').disabled=true;$('science-retry').hidden=true;
  try{
    const response=await fetch('/api/v1/products',{cache:'no-store',signal:AbortSignal.timeout(15000)});
    if(!response.ok)throw Error();const products=await response.json();if(!Array.isArray(products))throw Error();
    catalog=products.filter(p=>p.modes?.length);$('science-product').replaceChildren();
    catalog.forEach(p=>$('science-product').add(new Option(p.nameZh,p.code)));modes();
    $('science-submit').disabled=!catalog.length;$('science-status').textContent=catalog.length?'请选择产品和时间范围':'暂无可检索产品';
  }catch{$('science-status').textContent='产品目录读取失败，请重试';$('science-retry').hidden=false;}
}
function snapshot(){return {productCode:$('science-product').value,dataMode:$('science-mode').value,zone,from:$('science-from').value,to:$('science-to').value,cycleTime:$('science-cycle').value,leadMinutes:$('science-lead').value};}
async function run(criteria,nextPage){
  clear();const token=generation;const controller=new AbortController();active=controller;
  $('science-status').textContent='正在检索…';const timer=setTimeout(()=>controller.abort(),15000);
  try{
    const result=await searchScientific(criteria,nextPage,fetch,controller.signal);
    if(token!==generation)return;query=criteria;page=result.page;
    const stamp=iso=>iso?timeInput(iso,criteria.zone).replace('T',' ')+' '+zones[criteria.zone].label:'—';
    for(const item of result.items){
      const article=document.createElement('article'),title=document.createElement('h3'),details=document.createElement('p');
      title.textContent=item.fileName;
      details.textContent=`${(item.fileSize/1048576).toFixed(2)} MiB · ${(item.products||[]).join(' / ')} · ${item.status}\n有效时间：${stamp(item.validTime)}\n起报时间：${stamp(item.cycleTime)} · 时效：${item.leadMinutes==null?'—':'+'+item.leadMinutes+' 分钟'}`;
      article.append(title,details);$('science-results').append(article);
      const download=document.createElement('button');download.type='button';download.textContent=item.status==='AVAILABLE'?'下载':'暂不可下载';download.disabled=item.status!=='AVAILABLE';
      download.onclick=()=>openDownload(item);article.append(download);
    }
    $('science-status').textContent=result.items.length?`共 ${result.total} 个文件 · 按有效时间从新到旧`:'未找到符合条件的数据，请调整筛选条件';
    $('science-page').textContent=`第 ${page} 页 / ${Math.max(1,Math.ceil(result.total/result.pageSize))} 页`;
    $('science-prev').disabled=page<=1;$('science-next').disabled=page*result.pageSize>=result.total;
  }catch(error){if(token===generation)$('science-status').textContent=error.name==='AbortError'?'检索超时，请重试':error.message;}
  finally{clearTimeout(timer);}
}
$('science-form').onsubmit=e=>{e.preventDefault();run(snapshot(),1);};
$('science-product').onchange=()=>modes();$('science-mode').onchange=forecastFields;
$('science-zone').onchange=()=>{
  const next=$('science-zone').value;
  for(const id of ['science-from','science-to','science-cycle']){const input=$(id);if(input.value){try{input.value=timeInput(toUtc(input.value,zone),next);}catch{input.value='';}}}
  zone=next;
};
$('science-form').addEventListener('input',()=>{clear();$('science-status').textContent='条件已改变，请重新检索。';});
$('science-prev').onclick=()=>{if(query)run(query,page-1);};$('science-next').onclick=()=>{if(query)run(query,page+1);};
$('science-close').onclick=()=>dialog.close();dialog.addEventListener('close',clear);$('science-retry').onclick=loadCatalog;
