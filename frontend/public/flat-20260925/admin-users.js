import {createUserAdminClient} from './user-admin-client.js';
/** 用户列表分页与显式确认操作，不展示或读取密码。 */
export function mountUsers(onDenied){
  const $=id=>document.getElementById(id),api=createUserAdminClient();let generation=0,query=null,page=1,changing=false;
  const snapshot=()=>Object.fromEntries(['email','organization','role','status'].map(k=>[k,$('users-'+k).value]));
  function reset(){++generation;query=null;$('users-list').replaceChildren();$('users-page').textContent='';$('users-prev').disabled=true;$('users-next').disabled=true;}
  function fail(e){$('users-message').textContent=e.message;if([401,403].includes(e.status)){reset();onDenied(e.message);}}
  async function load(criteria=snapshot(),next=1){
    reset();const token=generation;$('users-message').textContent='正在读取用户…';
    try{const r=await api.list(criteria,next);if(token!==generation)return;const last=Math.max(1,Math.ceil(r.total/20));if(r.page>last)return load(criteria,last);query=criteria;page=r.page;
      for(const u of r.items){const row=document.createElement('tr');for(const v of [u.userId,u.email,u.organization,u.role==='ADMIN'?'管理员':'普通用户',u.status==='ACTIVE'?'启用':'禁用']){const cell=document.createElement('td');cell.textContent=v;row.append(cell);}const actions=document.createElement('td');
        for(const kind of ['status','role']){const value=kind==='status'?(u.status==='ACTIVE'?'DISABLED':'ACTIVE'):(u.role==='ADMIN'?'USER':'ADMIN');const label=kind==='status'?(value==='ACTIVE'?'启用':'禁用'):(value==='ADMIN'?'设为管理员':'设为普通用户');const b=document.createElement('button');b.textContent=label;b.disabled=changing;b.onclick=()=>change(u,kind,value,label);actions.append(b);}row.append(actions);$('users-list').append(row);}
      $('users-message').textContent=r.total?`共 ${r.total} 个用户`:'未找到符合条件的用户';$('users-page').textContent=`第 ${page} 页 / ${last} 页`;$('users-prev').disabled=page<=1;$('users-next').disabled=page*20>=r.total;
    }catch(e){if(token===generation)fail(e);}
  }
  async function change(user,kind,value,label){
    if(changing||!confirm(`确认将 ${user.email} ${label}？${kind==='role'?'此操作会改变管理权限。':value==='DISABLED'?'禁用后将无法登录或执行需要身份的操作。':'启用后可重新登录。'}`))return;
    changing=true;const token=generation;const criteria=query,oldPage=page;$('users-list').querySelectorAll('button').forEach(b=>b.disabled=true);
    try{await api.change(user.userId,kind,value);if(token===generation)await load(criteria,oldPage);}
    catch(e){if(token===generation)fail(e);}
    finally{changing=false;$('users-list').querySelectorAll('button').forEach(b=>b.disabled=false);}
  }
  $('users-form').onsubmit=e=>{e.preventDefault();load();};$('users-form').addEventListener('input',()=>{reset();$('users-message').textContent='条件已改变，请重新查询';});
  $('users-prev').onclick=()=>{if(query)load(query,page-1);};$('users-next').onclick=()=>{if(query)load(query,page+1);};return {reset,load:()=>load()};
}
