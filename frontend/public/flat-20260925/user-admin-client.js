/** 用户管理请求只接受白名单筛选/操作；后端负责最后管理员等并发保护。 */
export function createUserAdminClient(fetcher=fetch){
  async function request(path,method='GET',body,token){
    let r;try{r=await fetcher('/api/v1/'+path,{method,credentials:'same-origin',cache:'no-store',signal:AbortSignal.timeout(15000),headers:{...(body?{'Content-Type':'application/json'}:{}),...(token?{'X-CSRF-TOKEN':token}:{})},...(body?{body:JSON.stringify(body)}:{})});}catch{throw Error(method==='GET'?'用户列表读取失败':'操作结果未确认，请先刷新列表核对');}
    if(!r.ok){const e=Error(({401:'请重新登录',403:'没有管理员权限',409:'无法执行：不能禁用当前账号，且必须保留一个有效管理员',422:'用户筛选或修改参数不合法'})[r.status]||'用户管理服务暂时不可用');e.status=r.status;throw e;}return r.json();
  }
  return {
    list:async(filters={},page=1)=>{
      if(!Number.isSafeInteger(page)||page<1)throw Error('页码无效');
      const p=new URLSearchParams({page,pageSize:20});
      for(const key of ['email','organization','role','status']){const value=(filters[key]||'').trim();if(!value)continue;if(value.length>(key==='email'?254:255))throw Error('筛选条件过长');if(key==='role'&&!['USER','ADMIN'].includes(value)||key==='status'&&!['ACTIVE','DISABLED'].includes(value))throw Error('筛选条件无效');p.set(key,value);}
      const result=await request('admin/users?'+p);if(!Array.isArray(result.items)||result.page!==page||result.pageSize!==20||!Number.isSafeInteger(result.total)||result.total<0)throw Error('用户列表格式异常');return result;
    },
    change:async(userId,kind,value)=>{
      if(!Number.isSafeInteger(userId)||userId<1||!({status:['ACTIVE','DISABLED'],role:['USER','ADMIN']}[kind]||[]).includes(value))throw Error('用户操作无效');
      const s=await request('session');if(!s.authenticated||s.user?.role!=='ADMIN'){const e=Error('请登录管理员账号');e.status=s.authenticated?403:401;throw e;}
      if(kind==='status'&&value==='DISABLED'&&s.user.userId===userId)throw Error('不能禁用当前管理员账号');
      if(!s.csrfToken)throw Error('会话校验失败');
      return request(`admin/users/${userId}/${kind}`,'PUT',{[kind]:value},s.csrfToken);
    }
  };
}
