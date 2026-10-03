/** 只读管理员概览。Session 预检用于界面提示，真正权限由每个后端接口校验。 */
export async function loadAdminOverview(fetcher=fetch,signal){
  async function get(path){
    let response;
    try{response=await fetcher('/api/v1/'+path,{method:'GET',credentials:'same-origin',cache:'no-store',signal});}
    catch(error){if(error.name==='AbortError')throw error;throw Error('管理服务连接失败，请重试');}
    if(!response.ok){const error=Error(response.status===401?'请先登录管理员账号':response.status===403?'当前账号没有管理员权限':'管理数据读取失败，请重试');error.status=response.status;throw error;}
    return response.json();
  }
  const session=await get('session');
  if(!session.authenticated||session.user?.role!=='ADMIN'){
    const error=Error(session.authenticated?'当前账号没有管理员权限':'请先登录管理员账号');error.status=session.authenticated?403:401;throw error;
  }
  const [dashboard,health]=await Promise.all([get('admin/dashboard'),get('admin/product-health')]);
  if(!dashboard.downloads||!Array.isArray(health.items))throw Error('管理数据格式异常');
  return {dashboard,health:health.items};
}
