/** 管理员扫描 API：只接受任务编号/页码；不允许前端传目录或文件路径。 */
export function createScanClient(fetcher=fetch){
  async function request(path,method='GET',csrf){
    let response;
    try{response=await fetcher('/api/v1/'+path,{method,credentials:'same-origin',cache:'no-store',signal:AbortSignal.timeout(15000),headers:csrf?{'X-CSRF-TOKEN':csrf}:{}});}
    catch{throw Error(method==='POST'?'请求结果未确认，请先刷新任务列表，勿重复提交':'扫描记录读取失败，请重试');}
    if(!response.ok){const error=Error(({401:'登录已失效，请重新登录',403:'无权操作或会话已变更',404:'扫描任务不存在',409:'已有扫描任务运行，请刷新列表',429:'操作过于频繁，请稍后重试'})[response.status]||'扫描服务暂时不可用');error.status=response.status;throw error;}
    return response.json();
  }
  return {
    /** 页码 → 每页20条历史任务；一基页码，与后端契约一致。 */
    history:async(page=1)=>{
      if(!Number.isSafeInteger(page)||page<1)throw Error('页码无效');
      const result=await request(`admin/index-scans?page=${page}&pageSize=20`);
      if(!Array.isArray(result.items)||result.page!==page||result.pageSize!==20||!Number.isSafeInteger(result.total)||result.total<0)throw Error('任务列表格式异常');
      return result;
    },
    /** 任务ID → 状态、数量及安全错误列表。 */
    detail:id=>{if(!Number.isSafeInteger(id)||id<1)throw Error('任务编号无效');return request('admin/index-scans/'+id);},
    /** 无目录参数；重新获取 Session/CSRF 后触发一次异步扫描，不重试 POST。 */
    start:async()=>{
      const state=await request('session');
      if(!state.authenticated||state.user?.role!=='ADMIN'){const error=Error('请登录管理员账号');error.status=state.authenticated?403:401;throw error;}
      if(!state.csrfToken)throw Error('会话校验失败，请刷新页面');
      return request('admin/index-scans','POST',state.csrfToken);
    }
  };
}
