import {queryCopilot,safeAction} from './copilot-client.js';
import {openScientificSearch} from './scientific-search.js';

const launcher=document.getElementById('copilot-open');
launcher.className='copilot-launch';
launcher.innerHTML='<span aria-hidden="true">✦</span> 和大禹 AI 聊一聊';
launcher.setAttribute('aria-controls','copilot');
launcher.setAttribute('aria-expanded','false');
document.body.append(launcher);
const productRail=document.querySelector('.product-rail');
function placeLauncher(){
  if(!productRail)return;
  launcher.style.setProperty('--copilot-launch-top',Math.ceil(productRail.getBoundingClientRect().bottom+12)+'px');
}
placeLauncher();
if(productRail&&globalThis.ResizeObserver)new ResizeObserver(placeLauncher).observe(productRail);
window.addEventListener('resize',placeLauncher);

const dialog=document.createElement('dialog');
dialog.id='copilot';
dialog.setAttribute('aria-labelledby','copilot-title');
dialog.innerHTML=`<div class="drawer-heading"><div><span class="eyebrow">DAYU COPILOT</span><h2 id="copilot-title">大禹 AI 助手</h2></div><button id="copilot-close" type="button" aria-label="收起大禹AI助手">—</button></div>
<div id="copilot-messages" aria-live="polite"></div>
<div id="copilot-summary" hidden></div>
<div id="copilot-actions"></div>
<p id="copilot-status" role="status" aria-live="polite"></p>
<form id="copilot-form"><label for="copilot-question">消息</label><textarea id="copilot-question" rows="3" required maxlength="2000" placeholder="问问大禹是什么、有哪些产品，或帮你查找平台数据"></textarea><div class="copilot-form-actions"><button id="copilot-clear" type="button">清空</button><button id="copilot-search" type="button">普通检索</button><button id="copilot-send">发送</button></div><small>未说明的自然语言时间默认按北京时间理解</small></form>`;
document.body.append(dialog);

const $=id=>document.getElementById(id);
let history=[],generation=0,busy=false;

function message(who,text){
  const item=document.createElement('article'),label=document.createElement('small'),content=document.createElement('p');
  item.className='copilot-message '+(who==='你'?'is-user':'is-assistant');label.textContent=who;content.textContent=text;
  item.append(label,content);$('copilot-messages').append(item);
  while($('copilot-messages').children.length>24)$('copilot-messages').firstChild.remove();
  $('copilot-messages').scrollTop=$('copilot-messages').scrollHeight;
}

function welcome(){
  message('大禹助手','你好，我可以介绍大禹平台和公开产品，也可以通过对话帮你整理地图预览或科学数据的检索条件。');
}

function resetResult(){
  $('copilot-summary').replaceChildren();$('copilot-summary').hidden=true;$('copilot-actions').replaceChildren();$('copilot-status').textContent='';
}

function clearConversation(){
  ++generation;history=[];$('copilot-messages').replaceChildren();resetResult();$('copilot-question').value='';welcome();
}

function open(){
  if(!dialog.open)dialog.show();launcher.hidden=true;launcher.setAttribute('aria-expanded','true');$('copilot-question').focus();
}

function close(){if(dialog.open)dialog.close();}

launcher.onclick=()=>dialog.open?close():open();
$('copilot-close').onclick=close;
$('copilot-clear').onclick=clearConversation;
$('copilot-search').onclick=()=>{close();openScientificSearch();};
dialog.addEventListener('close',()=>{++generation;launcher.hidden=false;launcher.setAttribute('aria-expanded','false');launcher.focus();});

function renderSummary(result){
  const criteria=result.criteria;if(!criteria)return;
  const labels=[];
  if(criteria.productCode)labels.push(criteria.productCode);
  if(criteria.dataMode)labels.push(criteria.dataMode==='FORECAST'?'预报':'实况');
  if(criteria.queryKind==='PREVIEW_SEARCH')labels.push('地图预览');
  if(criteria.queryKind==='SCIENTIFIC_SEARCH')labels.push('科学数据');
  if(!labels.length)return;
  const card=document.createElement('section'),eyebrow=document.createElement('small'),title=document.createElement('strong');
  card.className='copilot-result-card';eyebrow.textContent='已确认条件';title.textContent=labels.join(' · ');card.append(eyebrow,title);
  if(criteria.from&&criteria.to){const time=document.createElement('span');time.textContent=`UTC ${new Date(criteria.from).toISOString().slice(0,16).replace('T',' ')} — ${new Date(criteria.to).toISOString().slice(0,16).replace('T',' ')}`;card.append(time);}
  $('copilot-summary').append(card);$('copilot-summary').hidden=false;
}

function openAbout(){
  close();const about=$('about');if(about&&!about.open)about.showModal();
}

function openCatalog(){
  close();$('catalog-open')?.click();
}

function applyPreview(parameters){
  const tab=$(parameters.dataMode==='FORECAST'?'forecast-tab':'realtime-tab');tab?.click();
  const product=[...document.querySelectorAll('#product-list [data-id]')].find(button=>button.dataset.id===parameters.productCode);
  if(!product)throw Error('该产品当前不能应用到地图，请从产品目录选择');
  close();product.click();
}

async function productDetails(parameters,token){
  const response=await fetch('/api/v1/products/'+parameters.productCode,{cache:'no-store',signal:AbortSignal.timeout(15000)});
  if(!response.ok)throw Error('产品详情读取失败，请打开产品目录查看');
  const product=await response.json();if(token!==generation)return;
  const card=document.createElement('section'),title=document.createElement('strong'),description=document.createElement('p');
  card.className='copilot-detail-card';title.textContent=`${product.nameZh} · ${parameters.productCode}`;description.textContent=product.descriptionZh||'暂无产品说明';card.append(title,description);
  $('copilot-summary').replaceChildren(card);$('copilot-summary').hidden=false;
}

async function performAction(item){
  const token=generation;
  try{
    const {type,parameters}=safeAction(item);
    if(type==='OPEN_ABOUT'){openAbout();return;}
    if(type==='OPEN_PRODUCT_CATALOG'){openCatalog();return;}
    if(type==='APPLY_PREVIEW_FILTER'){applyPreview(parameters);return;}
    if(type==='APPLY_SCIENTIFIC_SEARCH'){close();const {displayZone,...criteria}=parameters;await openScientificSearch(criteria,displayZone);return;}
    await productDetails(parameters,token);
  }catch(error){if(token===generation)$('copilot-status').textContent=error.message;}
}

function renderActions(actions){
  for(const item of actions){
    try{safeAction(item);}catch{continue;}
    const button=document.createElement('button');button.type='button';button.textContent=item.label||'继续';button.onclick=()=>performAction(item);$('copilot-actions').append(button);
  }
}

$('copilot-question').addEventListener('keydown',event=>{
  if(event.key==='Enter'&&!event.shiftKey){event.preventDefault();$('copilot-form').requestSubmit();}
});

$('copilot-form').onsubmit=async event=>{
  event.preventDefault();if(busy)return;
  const text=$('copilot-question').value.trim();if(!text)return;
  busy=true;const token=++generation;$('copilot-send').disabled=true;resetResult();$('copilot-status').textContent='大禹助手正在整理…';message('你',text);$('copilot-question').value='';
  try{
    const result=await queryCopilot(text,history);if(token!==generation)return;
    history.push({role:'USER',content:text},{role:'ASSISTANT',content:result.answer});history=history.slice(-12);
    message('大禹助手',result.answer);$('copilot-status').textContent=result.degraded?'AI 暂不可用，已返回可信的产品信息':'';
    renderSummary(result);renderActions(result.suggestedActions);
  }catch(error){if(token===generation)$('copilot-status').textContent=error.message;}
  finally{busy=false;$('copilot-send').disabled=false;}
};

welcome();
