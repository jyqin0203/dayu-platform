import {queryCopilot,safeAction} from './copilot-client.js';
import {openScientificSearch} from './scientific-search.js';
const dialog=document.createElement('dialog');dialog.id='copilot';dialog.setAttribute('aria-labelledby','copilot-title');
dialog.innerHTML=`<div class="drawer-heading"><h2 id="copilot-title">AI 数据助手</h2><button id="copilot-close" aria-label="关闭AI助手">✕</button></div>
<form id="copilot-form"><label>时间解释方式<select id="copilot-zone"><option value="UTC">UTC</option><option value="Asia/Shanghai">北京时间 UTC+8</option></select></label><label for="copilot-question">你想查询什么数据？</label><textarea id="copilot-question" rows="3" required maxlength="2000" placeholder="例如：查询昨天的云顶高度实况数据"></textarea><button id="copilot-send">发送</button><button id="copilot-clear" type="button">清空对话</button></form>
<p id="copilot-status" role="status" aria-live="polite"></p><div id="copilot-messages"></div><div id="copilot-actions"></div><div id="copilot-results"></div><button id="copilot-search">打开数据检索</button>`;
document.body.append(dialog);const $=id=>document.getElementById(id);let history=[],generation=0,busy=false;
function message(who,text){const p=document.createElement('p');p.textContent=who+'：'+text;$('copilot-messages').append(p);while($('copilot-messages').children.length>12)$('copilot-messages').firstChild.remove();}
function clear(){++generation;history=[];$('copilot-messages').replaceChildren();$('copilot-actions').replaceChildren();$('copilot-results').replaceChildren();$('copilot-status').textContent='';}
$('copilot-open').onclick=()=>dialog.showModal();$('copilot-close').onclick=()=>dialog.close();$('copilot-clear').onclick=clear;
dialog.addEventListener('close',()=>{++generation;});
$('copilot-search').onclick=()=>{dialog.close();openScientificSearch();};
async function action(item){
  const token=generation;$('copilot-results').replaceChildren();
  try{
    const {type,parameters:p}=safeAction(item);
    if(type==='APPLY_SCIENTIFIC_SEARCH'){dialog.close();await openScientificSearch(p,$('copilot-zone').value);return;}
    const path=type==='OPEN_PRODUCT_DETAILS'?'/api/v1/products/'+p.productCode:'/api/v1/preview-frames?'+new URLSearchParams(p);
    const response=await fetch(path,{cache:'no-store',signal:AbortSignal.timeout(15000)});if(!response.ok)throw Error('数据读取失败，请使用普通检索');const result=await response.json();if(token!==generation)return;
    if(type==='OPEN_PRODUCT_DETAILS'){const text=document.createElement('p');text.textContent=result.nameZh+'：'+result.descriptionZh;$('copilot-results').append(text);return;}
    if(!Array.isArray(result.items))throw Error('预览结果格式异常');
    const items=result.items;if(!items.length){$('copilot-status').textContent='未找到符合条件的预览';return;}
    const select=document.createElement('select'),image=document.createElement('img'),time=document.createElement('p');image.alt='所选气象产品预览';
    for(const [i,frame]of items.entries())select.add(new Option(new Date(frame.validTime).toISOString().slice(0,16).replace('T',' ')+' UTC',i));
    const show=()=>{const frame=items[Number(select.value)],url=frame.previewUrl;if(typeof url!=='string'||!url.startsWith('/media/webp/')||/[\\%?#]/.test(url)||url.split('/').includes('..')){image.removeAttribute('src');time.textContent='预览地址不可用';return;}image.src=url;time.textContent=select.selectedOptions[0].textContent;};select.onchange=show;image.onerror=()=>{time.textContent='预览图片暂不可用';};$('copilot-results').append(select,time,image);show();
  }catch(error){if(token===generation)$('copilot-status').textContent=error.message;}
}
$('copilot-form').onsubmit=async event=>{
  event.preventDefault();if(busy)return;busy=true;const token=++generation,text=$('copilot-question').value;
  $('copilot-send').disabled=true;$('copilot-status').textContent='正在查询…';$('copilot-actions').replaceChildren();$('copilot-results').replaceChildren();message('你',text);
  try{const result=await queryCopilot(text,$('copilot-zone').value,history);if(token!==generation)return;history.push(text,result.answer);history=history.slice(-6);message('助手',result.answer);$('copilot-question').value='';$('copilot-status').textContent=result.degraded?'AI 暂不可用，已提供可用的产品信息':'';
    for(const item of result.suggestedActions){try{safeAction(item);}catch{continue;}const button=document.createElement('button');button.textContent=item.label||'查看结果';button.onclick=()=>action(item);$('copilot-actions').append(button);}
  }catch(error){if(token===generation)$('copilot-status').textContent=error.message;}
  finally{busy=false;$('copilot-send').disabled=false;}
};
