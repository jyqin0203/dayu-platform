import {createProductAdminClient,productFields,productForm,lifecycleAction} from './product-admin-client.js';
import {mountProductModes} from './admin-product-modes.js';
const labels={nameZh:'中文名称',nameEn:'英文名称',unit:'单位',descriptionZh:'中文说明',descriptionEn:'英文说明',producer:'生产方',algorithmName:'算法名称',sourceDescription:'数据来源说明',officialSourceUrl:'官方来源网址',colorbarPath:'色标相对路径',sortOrder:'排序',colorbarRequired:'必须配置色标'};
/** 产品列表与编辑弹窗；保留输入直到成功或用户确认放弃。动态文字不解析 HTML。 */
export function mountProducts(onDenied){
  const $=id=>document.getElementById(id),api=createProductAdminClient(),dialog=$('product-editor'),form=$('product-form');
  let products=[],original=null,dirty=false,saving=false,generation=0,page=1,creating=false,changing=false;
  const modes=mountProductModes(load,onDenied);
  for(const key of productFields){
    const label=document.createElement('label'),input=document.createElement(key.startsWith('description')||key==='sourceDescription'?'textarea':'input');
    label.textContent=labels[key];input.id='product-'+key;input.name=key;
    if(key==='colorbarRequired')input.type='checkbox';else if(key==='sortOrder'){input.type='number';input.step='1';input.required=true;}else if(input.tagName==='INPUT')input.type='text';
    if(['nameZh','nameEn','producer'].includes(key)){input.required=true;input.maxLength=255;if(key!=='producer')input.minLength=2;}
    if(key==='unit')input.maxLength=64;if(key==='colorbarPath')input.maxLength=512;if(key==='algorithmName')input.maxLength=255;
    label.append(input);$('product-fields').append(label);
  }
  function render(){
    $('products-list').replaceChildren();page=Math.min(page,Math.max(1,Math.ceil(products.length/20)));
    for(const p of products.slice((page-1)*20,page*20)){const row=document.createElement('tr');for(const value of [p.code,p.nameZh,p.family,({DRAFT:'草稿',PUBLISHED:'已发布',DISABLED:'已停用'})[p.status]||p.status]){const td=document.createElement('td');td.textContent=value;row.append(td);}const td=document.createElement('td'),button=document.createElement('button');button.textContent='编辑 '+p.code;button.onclick=()=>edit(p);td.append(button);row.append(td);$('products-list').append(row);}
    for(const [index,row] of [...$('products-list').children].entries()){const p=products[(page-1)*20+index],button=document.createElement('button');button.textContent='模式 '+p.code;button.onclick=()=>modes.open(p);row.lastElementChild.append(button);}
    for(const [index,row] of [...$('products-list').children].entries()){
      const p=products[(page-1)*20+index],button=document.createElement('button');
      button.textContent=(p.status==='PUBLISHED'?'停用 ':p.status==='DISABLED'?'重新发布 ':'发布 ')+p.code;
      button.disabled=changing;button.onclick=()=>transition(p,p.status==='PUBLISHED'?'disable':'publish');row.lastElementChild.append(button);
    }
    $('products-page').textContent=`第 ${page} 页 / ${Math.max(1,Math.ceil(products.length/20))} 页`;$('products-prev').disabled=page<=1;$('products-next').disabled=page*20>=products.length;
  }
  function reset(closeEditor=false){++generation;products=[];$('products-list').replaceChildren();$('products-page').textContent='';$('products-prev').disabled=true;$('products-next').disabled=true;if(closeEditor){modes.reset();dirty=false;dialog.close();form.reset();original=null;}}
  function failed(e){$('products-status').textContent=e.message;if([401,403].includes(e.status)){reset(true);onDenied(e.message);}}
  async function load(){const token=++generation;$('products-status').textContent='正在读取产品…';try{const result=await api.list();if(token!==generation)return;products=result;render();$('products-status').textContent=`共 ${products.length} 个产品`;}catch(e){if(token===generation)failed(e);}}
  function edit(p){
    creating=false;identityFields(false);original=structuredClone(p);dirty=false;const values=productForm(p);
    for(const key of productFields){const input=$('product-'+key);if(key==='colorbarRequired')input.checked=values[key];else input.value=values[key];}
    $('product-editor-title').textContent='编辑 '+p.code;$('product-readonly').textContent=`分类：${p.family} · 状态：${p.status} · 模式：${p.modes.map(m=>m.dataMode+' '+(m.enabled?'启用':'停用')).join(' / ')}`;
    $('product-message').textContent='';dialog.showModal();
  }
  function identityFields(show){$('product-identity').hidden=!show;for(const id of ['product-code','product-family']){$(id).required=show;$(id).disabled=!show;}}
  $('products-create').onclick=()=>{
    creating=true;original=null;dirty=false;form.reset();identityFields(true);$('product-sortOrder').value='0';
    $('product-editor-title').textContent='创建产品草稿';$('product-readonly').textContent='产品编码与分类创建后不可修改';$('product-message').textContent='';dialog.showModal();
  };
  async function transition(product,action){
    if(changing)return;
    try{lifecycleAction(product,action);}catch(error){$('products-status').textContent=error.message;return;}
    const text=action==='disable'?`确认停用 ${product.code}？停用后将不再提供该产品的公开检索和下载，不会删除原始文件。`:`确认${product.status==='DISABLED'?'重新发布':'发布'} ${product.code}？产品将对用户开放。`;
    if(!confirm(text))return;changing=true;const token=generation;render();$('products-status').textContent='正在更新产品状态…';
    try{await api.transition(product,action);if(token!==generation)return;await load();$('products-status').textContent='产品状态已更新';}
    catch(error){if(token===generation)failed(error);}
    finally{changing=false;render();}
  }
  function close(){if(saving)return;if(dirty&&!confirm('放弃尚未保存的修改？'))return;dirty=false;dialog.close();}
  $('product-close').onclick=close;dialog.addEventListener('cancel',e=>{e.preventDefault();close();});
  form.addEventListener('input',()=>{dirty=true;});
  form.onsubmit=async e=>{
    e.preventDefault();if(saving||(!creating&&!original))return;
    const values=Object.fromEntries(productFields.map(key=>[key,key==='colorbarRequired'?$('product-'+key).checked:$('product-'+key).value]));
    const create=creating;if(create){values.code=$('product-code').value;values.family=$('product-family').value;if(!confirm('确认创建该产品草稿？创建后仍需配置模式并发布。'))return;}
    saving=true;form.querySelectorAll('input,textarea,button').forEach(el=>el.disabled=true);$('product-message').textContent='正在保存…';
    try{const updated=create?await api.create(values):await api.save(original,values);if(!dialog.open)return;original=updated;creating=false;identityFields(false);dirty=false;$('product-message').textContent=create?'草稿已创建，请配置模式后发布':'已保存';$('product-editor-title').textContent='编辑 '+updated.code;await load();}
    catch(error){if([401,403].includes(error.status))failed(error);else $('product-message').textContent=error.message;}
    finally{saving=false;form.querySelectorAll('input,textarea,button').forEach(el=>el.disabled=false);identityFields(creating);}
  };
  $('products-refresh').onclick=load;$('products-prev').onclick=()=>{--page;render();};$('products-next').onclick=()=>{++page;render();};
  return {reset,load};
}
