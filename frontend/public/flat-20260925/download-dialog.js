import {requestLogin} from './account.js';
import {createSessionClient} from './session-client.js';
import {authorizeDownload,validateGrant} from './download-client.js';

const dialog=document.createElement('dialog');dialog.id='nc-download';dialog.setAttribute('aria-labelledby','download-title');
dialog.innerHTML=`<div class="drawer-heading"><h2 id="download-title">下载数据</h2><button id="download-close" type="button" aria-label="关闭下载">✕</button></div>
<p id="download-file"></p><p id="download-status" role="status" aria-live="polite"></p>
<button id="download-login" type="button" hidden>登录后继续</button>
<form id="download-form"><label for="download-purpose">数据使用用途</label><textarea id="download-purpose" required minlength="10" maxlength="2000" rows="4" placeholder="请说明研究或使用目的（10～2000 字）"></textarea><button id="download-authorize" type="submit">申请下载</button></form>
<a id="download-link" hidden target="_blank" rel="noopener">下载文件</a>`;
document.body.append(dialog);
const $=id=>document.getElementById(id),session=createSessionClient();
let selected=null,generation=0,busy=false;
function resetGrant(){ $('download-link').hidden=true;$('download-link').removeAttribute('href');$('download-link').onclick=null; }
function lock(value){busy=value;$('download-authorize').disabled=value;$('download-login').disabled=value;$('download-purpose').disabled=value;}
async function identify(token){
  const state=await session.current();if(token!==generation)return;
  $('download-login').hidden=state.authenticated;$('download-form').hidden=!state.authenticated;
  $('download-status').textContent=state.authenticated?'请填写使用用途':'请登录后继续，已保留所选文件';
}
/** 候选资产快照 → 下载弹窗；底层检索对话框不关闭，条件/页码/结果原位保留。 */
export async function openDownload(asset){
  selected={assetId:asset.assetId,fileName:asset.fileName};const token=++generation;
  $('download-file').textContent=selected.fileName;$('download-purpose').value='';resetGrant();
  $('download-form').hidden=true;$('download-login').hidden=true;$('download-status').textContent='正在检查登录状态…';
  lock(true);dialog.showModal();
  try{await identify(token);}catch(error){if(token===generation){$('download-status').textContent=error.message;$('download-login').hidden=false;}}
  finally{if(token===generation)lock(false);}
}
$('download-close').onclick=()=>dialog.close();
dialog.addEventListener('close',()=>{++generation;selected=null;resetGrant();lock(false);});
$('download-login').onclick=async()=>{
  const token=generation;lock(true);
  try{if(await requestLogin()){if(token===generation)await identify(token);}else if(token===generation)$('download-status').textContent='已保留所选文件，可重新登录后继续';}
  catch(error){if(token===generation)$('download-status').textContent=error.message;}
  finally{if(token===generation)lock(false);}
};
$('download-purpose').oninput=resetGrant;
$('download-form').onsubmit=async event=>{
  event.preventDefault();if(busy||!selected)return;
  const token=generation,asset=selected;lock(true);resetGrant();$('download-status').textContent='正在申请下载…';
  try{
    const grant=await authorizeDownload(asset.assetId,$('download-purpose').value);
    if(token!==generation)return;
    const link=$('download-link');link.href=grant.downloadUrl;link.download=grant.fileName||asset.fileName;link.hidden=false;
    // 原生下载交给浏览器；不 fetch().blob()，也不把授权成功说成文件已保存。
    link.onclick=event=>{try{validateGrant(grant);$('download-status').textContent='已发起下载，请查看浏览器下载记录';}catch(error){event.preventDefault();resetGrant();$('download-status').textContent=error.message;}};
    $('download-status').textContent='已获授权，请点击“下载文件”';
  }catch(error){if(token===generation){$('download-status').textContent=error.message;if(error.status===401){$('download-login').hidden=false;$('download-form').hidden=true;}}}
  finally{if(token===generation)lock(false);}
};
