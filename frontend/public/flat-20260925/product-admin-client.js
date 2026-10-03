/** 管理产品资料字段白名单；不发送状态、模式、产品编码或分类。 */
export const productFields=['nameZh','nameEn','unit','descriptionZh','descriptionEn','producer','algorithmName','sourceDescription','officialSourceUrl','colorbarRequired','colorbarPath','sortOrder'];
/** 允许的生命周期动作；重新发布复用 publish 端点，不物理删除产品。 */
export function lifecycleAction(product,action){
  if(!Number.isSafeInteger(product.productId)||product.productId<1)throw Error('产品编号无效');
  const allowed=action==='publish'?['DRAFT','DISABLED']:action==='disable'?['PUBLISHED']:[];
  if(!allowed.includes(product.status))throw Error('当前产品状态不允许此操作');
  if(action==='publish'&&!product.modes.some(m=>m.enabled))throw Error('发布前请先启用至少一种模式');
  return action;
}
/** 一次只配置一种模式；已发布产品不能关闭最后一个启用模式。 */
export function modePayload(product,dataMode,enabled,minutes){
  if(!['REALTIME','FORECAST'].includes(dataMode)||typeof enabled!=='boolean')throw Error('产品模式无效');
  const staleAfterMinutes=Number(minutes);
  if(!Number.isInteger(staleAfterMinutes)||staleAfterMinutes<10||staleAfterMinutes>10080)throw Error('延迟阈值需为 10～10080 的整数分钟');
  if(product.status==='PUBLISHED'&&!enabled&&!product.modes.some(m=>m.dataMode!==dataMode&&m.enabled))throw Error('已发布产品必须保留至少一种启用模式');
  return {enabled,staleAfterMinutes};
}
export function productForm(product){
  const value=Object.fromEntries(productFields.map(key=>[key,product[key]??'']));
  value.colorbarPath=product.colorbarUrl?decodeURIComponent(product.colorbarUrl.slice(1)):'';
  return value;
}
export function productPayload(form){
  const body=Object.fromEntries(productFields.map(key=>[key,form[key]]));
  for(const key of productFields.filter(k=>!['colorbarRequired','sortOrder'].includes(k)))body[key]=String(body[key]??'').trim();
  for(const key of ['nameZh','nameEn'])if(body[key].length<2||body[key].length>255)throw Error('中英文名称需为 2～255 个字符');
  if(!body.producer||body.producer.length>255)throw Error('请填写有效的生产方');
  if(body.unit.length>64||body.algorithmName.length>255||body.colorbarPath.length>512)throw Error('单位、算法名称或色标路径过长');
  body.sortOrder=Number(body.sortOrder);if(!Number.isInteger(body.sortOrder)||body.sortOrder< -2147483648||body.sortOrder>2147483647)throw Error('排序必须为有效整数');
  if(typeof body.colorbarRequired!=='boolean')throw Error('色标要求无效');
  const path=body.colorbarPath;
  if(path&&(/[\\:%?#\x00-\x1f]/.test(path)||path.split('/').some(s=>!s||s==='.'||s==='..')))throw Error('色标需为安全的相对路径');
  if(body.officialSourceUrl){let url;try{url=new URL(body.officialSourceUrl);}catch{throw Error('来源网址无效');}if(!['http:','https:'].includes(url.protocol)||url.username||url.password)throw Error('来源网址只允许 HTTP/HTTPS');}
  for(const key of ['unit','algorithmName','officialSourceUrl','colorbarPath'])if(!body[key])body[key]=null;
  return body;
}
/** 真实写入由服务器再次鉴权；所有修改请求只发一次。 */
export function createProductAdminClient(fetcher=fetch){
  async function request(path,method='GET',body,csrf){
    let response;try{response=await fetcher('/api/v1/'+path,{method,credentials:'same-origin',cache:'no-store',signal:AbortSignal.timeout(15000),headers:{...(body?{'Content-Type':'application/json'}:{}),...(csrf?{'X-CSRF-TOKEN':csrf}:{})},...(body?{body:JSON.stringify(body)}:{})});}
    catch{throw Error(method!=='GET'?'操作结果未确认，请重新读取产品后核对，勿重复提交':'产品读取失败，请重试');}
    if(!response.ok){const error=Error(({401:'请重新登录',403:'没有管理权限或会话已变更',404:'产品不存在',409:method==='POST'&&path==='admin/products'?'产品编码已存在，请更换编码':'产品状态已变化，请重新读取',422:'资料不符合要求，请检查名称、来源和色标配置'})[response.status]||'产品服务暂时不可用');error.status=response.status;throw error;}
    return response.json();
  }
  async function list(code){const result=await request('admin/products'+(code?'?code='+encodeURIComponent(code):''));if(!Array.isArray(result))throw Error('产品列表格式异常');return result;}
  async function adminToken(){const state=await request('session');if(!state.authenticated||state.user?.role!=='ADMIN'){const error=Error('请登录管理员账号');error.status=state.authenticated?403:401;throw error;}if(!state.csrfToken)throw Error('会话校验失败');return state.csrfToken;}
  return {list,
    /** 创建只保存草稿，不连带启用模式或发布。 */
    create:async(form)=>{
      const code=String(form.code??'').trim(),family=String(form.family??'').trim();
      if(!/^[A-Z][A-Z0-9_]{1,63}$/.test(code))throw Error('编码需以大写字母开头，包含 2～64 位大写字母、数字或下划线');
      if(!family||family.length>64)throw Error('请填写 1～64 字符的分类');
      const body={...productPayload(form),code,family},csrf=await adminToken();
      return request('admin/products','POST',body,csrf);
    },
    /** 状态操作独立 POST；预检快照避免对已变化的产品直接操作，不自动重试。 */
    transition:async(original,action)=>{
      lifecycleAction(original,action);const csrf=await adminToken();
      const fresh=(await list(original.code)).find(p=>p.productId===original.productId);
      if(!fresh||fresh.updatedAt!==original.updatedAt)throw Error('产品已被修改，请刷新列表后重试');
      lifecycleAction(fresh,action);
      return request(`admin/products/${original.productId}/${action}`,'POST',undefined,csrf);
    },
    /** 原快照、模式和设置 → 预检 Session/更新时间 → 独立模式 PUT；不改变产品发布状态。 */
    saveMode:async(original,dataMode,enabled,minutes)=>{
      if(!Number.isSafeInteger(original.productId)||original.productId<1)throw Error('产品编号无效');
      const body=modePayload(original,dataMode,enabled,minutes),state=await request('session');
      if(!state.authenticated||state.user?.role!=='ADMIN'){const error=Error('请登录管理员账号');error.status=state.authenticated?403:401;throw error;}
      if(!state.csrfToken)throw Error('会话校验失败');
      const fresh=(await list(original.code)).find(p=>p.productId===original.productId);
      if(!fresh||fresh.updatedAt!==original.updatedAt)throw Error('产品已被修改，请重新打开模式窗口');
      modePayload(fresh,dataMode,enabled,minutes);
      return request(`admin/products/${original.productId}/modes/${dataMode}`,'PUT',body,state.csrfToken);
    },
    /** 原产品快照和表单 → 预检更新时间、Session/CSRF → 更新后的产品。非原子并发锁。 */
    save:async(original,form)=>{
      if(!Number.isSafeInteger(original.productId)||original.productId<1)throw Error('产品编号无效');
      const body=productPayload(form),state=await request('session');
      if(!state.authenticated||state.user?.role!=='ADMIN'){const error=Error('请登录管理员账号');error.status=state.authenticated?403:401;throw error;}
      if(!state.csrfToken)throw Error('会话校验失败');
      const fresh=(await list(original.code)).find(p=>p.productId===original.productId);
      if(!fresh||fresh.updatedAt!==original.updatedAt)throw Error('产品已被修改，请重新打开编辑窗口');
      return request('admin/products/'+original.productId,'PUT',body,state.csrfToken);
    }
  };
}
