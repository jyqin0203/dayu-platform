import {createProductAdminClient,modePayload} from './product-admin-client.js';

/** 单模式编辑：成功后须重新读取产品快照，再允许下一次保存。 */
export function mountProductModes(onSaved,onDenied){
  const $=id=>document.getElementById(id),dialog=$('mode-editor'),api=createProductAdminClient();
  let original=null,dirty=false,busy=false,generation=0;
  const modeName=value=>value==='REALTIME'?'实况':'预报';
  function lock(value){busy=value;dialog.querySelectorAll('button,input,select').forEach(el=>el.disabled=value);}
  function fill(){
    const policy=original.modes.find(m=>m.dataMode===$('mode-kind').value);
    $('mode-enabled').checked=policy?.enabled??false;$('mode-minutes').value=policy?.staleAfterMinutes??'';
    $('mode-message').textContent=policy?'':'该模式尚未配置';dirty=false;
  }
  function reset(){++generation;dirty=false;original=null;dialog.close();lock(false);}
  function open(product){original=structuredClone(product);++generation;$('mode-title').textContent=product.code+' · 模式配置';$('mode-kind').value='REALTIME';fill();dialog.showModal();}
  function close(){if(busy)return;if(dirty&&!confirm('放弃尚未保存的模式配置？'))return;reset();}
  $('mode-close').onclick=close;dialog.addEventListener('cancel',e=>{e.preventDefault();close();});
  let previous='REALTIME';
  $('mode-kind').onfocus=()=>{previous=$('mode-kind').value;};
  $('mode-kind').onchange=()=>{if(dirty&&!confirm('切换模式将放弃未保存的修改，继续？')){$('mode-kind').value=previous;return;}fill();previous=$('mode-kind').value;};
  for(const id of ['mode-enabled','mode-minutes'])$(id).oninput=()=>{dirty=true;};
  $('mode-form').onsubmit=async e=>{
    e.preventDefault();if(busy||!original)return;
    const mode=$('mode-kind').value,enabled=$('mode-enabled').checked,minutes=$('mode-minutes').value;
    try{modePayload(original,mode,enabled,minutes);}catch(error){$('mode-message').textContent=error.message;return;}
    if(!confirm(`确认将 ${original.code} 的${modeName(mode)}模式设为${enabled?'启用':'停用'}，延迟阈值设为 ${minutes} 分钟？`))return;
    const token=generation;lock(true);$('mode-message').textContent='正在保存…';
    try{
      await api.saveMode(original,mode,enabled,minutes);if(token!==generation)return;
      // PUT 返回模式而非完整产品；重新获取 updatedAt，不能继续用旧快照覆盖。
      const updated=(await api.list(original.code)).find(p=>p.productId===original.productId);if(token!==generation)return;
      if(!updated)throw Error('保存后无法读取产品，请重新打开模式窗口');
      original=updated;fill();dirty=false;$('mode-message').textContent='模式配置已保存';await onSaved();
    }catch(error){if(token===generation){$('mode-message').textContent=error.message;if([401,403].includes(error.status)){reset();onDenied(error.message);}}}
    finally{if(token===generation)lock(false);}
  };
  return {open,reset};
}
