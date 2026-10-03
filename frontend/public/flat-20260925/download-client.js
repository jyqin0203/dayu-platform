import {createSessionClient} from './session-client.js';

/** 仅接受本站标准内容路由，授权响应不能将用户带到外站或任意服务器路径。 */
export function validateGrant(grant,now=Date.now()){
  if(!Number.isSafeInteger(grant.downloadEventId)||grant.downloadEventId<1||
    grant.downloadUrl!==`/api/v1/downloads/${grant.downloadEventId}/content`||
    !Number.isFinite(Date.parse(grant.expiresAt))||Date.parse(grant.expiresAt)<=now)throw Error('下载授权无效或已过期，请重新申请');
  return grant;
}
/** assetId、用途 → 当前 Session/CSRF → 授权信息。不会读取大文件或自动重放 POST。 */
export async function authorizeDownload(assetId,purpose,fetcher=fetch){
  purpose=purpose.trim();
  if(!Number.isSafeInteger(assetId)||assetId<1)throw Error('文件编号无效，请重新检索');
  if(purpose.length<10||purpose.length>2000)throw Error('请填写 10～2000 个字符的使用用途');
  const state=await createSessionClient(fetcher).current();
  if(!state.authenticated){const error=Error('请登录后继续下载');error.status=401;throw error;}
  if(!state.csrfToken)throw Error('会话校验失败，请重试');
  let response;
  try{response=await fetcher('/api/v1/downloads',{method:'POST',credentials:'same-origin',cache:'no-store',signal:AbortSignal.timeout(15000),headers:{'Content-Type':'application/json','X-CSRF-TOKEN':state.csrfToken},body:JSON.stringify({assetId,purpose})});}
  catch{throw Error('授权请求未确认，请稍后重试');}
  if(!response.ok){
    const messages={401:'登录已失效，请重新登录',403:'无权下载或会话已变更，请重试',404:'文件不存在或已不可用',409:'文件当前不可下载',422:'用途或文件信息不合法',429:'操作过于频繁，请稍后重试'};
    const error=Error(messages[response.status]||'下载服务暂时不可用');error.status=response.status;throw error;
  }
  return validateGrant(await response.json());
}
