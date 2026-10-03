import {createSessionClient} from './session-client.js';

/** 只接管既有账号弹窗，不依赖地图初始化成功，也不触碰下载授权。 */
const dialog=document.getElementById('account'),opener=document.getElementById('account-open');
const client=createSessionClient();
const initialUrl=new URL(location.href),adminLogin=initialUrl.searchParams.get('login')==='admin';
if(adminLogin){initialUrl.searchParams.delete('login');history.replaceState(null,'',initialUrl);}
dialog.setAttribute('aria-labelledby','account-title');
dialog.innerHTML=`
  <div class="drawer-heading"><h2 id="account-title">账号与数据服务</h2><button id="account-close" type="button" aria-label="关闭账号窗口">✕</button></div>
  <p id="account-message" role="status" aria-live="polite"></p>
  <div id="account-user" hidden><p id="account-summary"></p><a id="account-admin" href="/admin.html" hidden>管理概览 →</a><button id="account-logout" type="button">退出登录</button></div>
  <form id="account-form">
    <div class="account-tabs"><button type="button" id="account-login-tab" aria-pressed="true">登录</button><button type="button" id="account-register-tab" aria-pressed="false">注册</button></div>
    <label for="account-email">邮箱</label><input id="account-email" name="email" type="email" autocomplete="username" required maxlength="254">
    <label for="account-password">密码</label><input id="account-password" name="password" type="password" autocomplete="current-password" required maxlength="128">
    <div id="account-organization-row" hidden><label for="account-organization">工作单位</label><input id="account-organization" name="organization" autocomplete="organization" minlength="2" maxlength="255"></div>
    <p id="account-password-help" hidden>注册密码为 10～128 个字符，工作单位为 2～255 个字符。</p>
    <button id="account-submit" type="submit">登录</button>
  </form>
  `;
const $=id=>document.getElementById(id);
let register=false,busy=false;
let loginCompletion=null;
/** 上层下载流程等待登录；关闭/取消返回 false，不触碰检索状态。 */
export function requestLogin(){
  if(loginCompletion)return Promise.resolve(false);
  mode(false);if(!dialog.open)dialog.showModal();
  const result=new Promise(resolve=>{loginCompletion=resolve;});
  perform(client.current);return result;
}
function render(state){
  const logged=state.authenticated===true;
  $('account-user').hidden=!logged;$('account-form').hidden=logged;
  opener.textContent=logged?'账号':'登录';
  $('account-admin').hidden=!logged||state.user?.role!=='ADMIN';
  $('account-summary').textContent=logged?`${state.user.email} · ${state.user.organization}`:'';
  if(adminLogin&&logged&&state.user.role==='ADMIN')location.assign('/admin.html');
}
function mode(value){
  register=value;$('account-organization-row').hidden=!value;$('account-organization').required=value;
  $('account-password').minLength=value?10:1;$('account-password').autocomplete=value?'new-password':'current-password';
  $('account-password').value='';$('account-password-help').hidden=!value;
  $('account-submit').textContent=value?'注册并登录':'登录';
  $('account-login-tab').setAttribute('aria-pressed',String(!value));$('account-register-tab').setAttribute('aria-pressed',String(value));
}
async function perform(action,success=''){
  if(busy)return;
  busy=true;dialog.setAttribute('aria-busy','true');
  dialog.querySelectorAll('input,button:not(#account-close)').forEach(el=>el.disabled=true);
  $('account-message').textContent='正在处理…';
  try{
    const state=await action();render(state);$('account-message').textContent=success;
    if(state.authenticated&&loginCompletion){const resolve=loginCompletion;loginCompletion=null;dialog.close();resolve(true);}
  }
  catch(error){$('account-message').textContent=error.message;}
  finally{
    $('account-password').value='';busy=false;dialog.setAttribute('aria-busy','false');
    dialog.querySelectorAll('input,button').forEach(el=>el.disabled=false);
  }
}
opener.addEventListener('click',()=>{if(!dialog.open)dialog.showModal();perform(client.current);});
$('account-close').onclick=()=>dialog.close();
dialog.addEventListener('close',()=>{$('account-password').value='';if(loginCompletion){const resolve=loginCompletion;loginCompletion=null;resolve(false);}});
$('account-login-tab').onclick=()=>mode(false);$('account-register-tab').onclick=()=>mode(true);
$('account-form').onsubmit=event=>{
  event.preventDefault();
  const values={email:$('account-email').value.trim(),password:$('account-password').value,organization:$('account-organization').value.trim()};
  perform(()=>register?client.register(values):client.login(values),register?'注册成功，已登录':'登录成功');
};
$('account-logout').onclick=()=>perform(client.logout,'已退出登录');
// 初始化读取身份；读取失败只提示，不阻塞地图或假装已登录。
if(adminLogin)dialog.showModal();
perform(client.current);
