/** 同源 Session 客户端：Cookie 交给浏览器，CSRF 仅在内存，不持久化密码或令牌。 */
export function createSessionClient(fetcher = fetch) {
  async function request(path, method='GET', body, token) {
    let response;
    try {
      response=await fetcher('/api/v1/'+path,{
        method,credentials:'same-origin',cache:'no-store',signal:AbortSignal.timeout(15000),
        headers:{...(body?{'Content-Type':'application/json'}:{}),...(token?{'X-CSRF-TOKEN':token}:{})},
        ...(body?{body:JSON.stringify(body)}:{})
      });
    } catch { throw Error('网络异常或请求超时，请检查当前账号状态后重试'); }
    if(!response.ok){
      const messages={401:'邮箱或密码错误，或登录已失效',403:'会话已变更或无权操作，请重试',409:'该邮箱已注册',422:'请检查邮箱、密码和工作单位格式',429:'操作过于频繁，请稍后再试'};
      throw Error(messages[response.status]||'账号服务暂时不可用，请稍后重试');
    }
    return response.status===204?null:response.json();
  }
  /** 查询服务器当前身份；不根据本地缓存推断用户是否登录。 */
  const current=()=>request('session');
  // 每次写操作前读取当前 Session 的 CSRF，避免跨标签页切换账号后使用旧令牌。
  async function mutate(path,method,body){
    const session=await current();
    if(!session.csrfToken)throw Error('会话校验失败，请刷新后重试');
    return request(path,method,body,session.csrfToken);
  }
  return {
    current,
    /** 邮箱和密码 → 后端验证并旋转 Session → 新身份及令牌。失败不自动重放。 */
    login:({email,password})=>mutate('session','POST',{email,password}),
    /** 邮箱、密码、工作单位 → 创建普通用户，事务成功后自动登录。 */
    register:({email,password,organization})=>mutate('users','POST',{email,password,organization}),
    /** 后端销毁 Session；204 即退出成功，下一次操作再获取匿名令牌。 */
    logout:async()=>{await mutate('session','DELETE');return {authenticated:false,user:null};}
  };
}
