import {toUtc} from './scientific-client.js';

/** 审计筛选快照 → UTC、一基分页参数；只读授权审计，不计算传输完成率。 */
export function auditParams(form,page=1){
  if(!Number.isSafeInteger(page)||page<1)throw Error('页码无效');
  const from=toUtc(form.from,'UTC'),to=toUtc(form.to,'UTC');if(from>to)throw Error('开始时间不能晚于结束时间');
  const params=new URLSearchParams({from,to,page,pageSize:20});
  const code=(form.productCode||'').trim(),organization=(form.organization||'').trim(),user=(form.userId||'').trim();
  if(code){if(!/^[A-Z][A-Z0-9_]{1,63}$/.test(code))throw Error('产品编码格式不正确');params.set('productCode',code);}
  if(organization.length>255)throw Error('机构名称不能超过 255 字符');if(organization)params.set('organization',organization);
  if(user){if(!/^[1-9]\d*$/.test(user)||!Number.isSafeInteger(Number(user)))throw Error('用户 ID 必须为正整数');params.set('userId',user);}
  return params;
}
/** 筛选、页码 → 管理审计分页；403/401 交给调用方清除敏感列表。 */
export async function loadAudits(form,page=1,fetcher=fetch,signal){
  const params=auditParams(form,page);let response;
  try{response=await fetcher('/api/v1/admin/download-audits?'+params,{method:'GET',credentials:'same-origin',cache:'no-store',signal});}
  catch(error){if(error.name==='AbortError')throw error;throw Error('审计记录读取失败，请重试');}
  if(!response.ok){const error=Error(({401:'请重新登录管理员账号',403:'当前账号没有管理员权限',422:'审计筛选条件不合法'})[response.status]||'审计服务暂时不可用');error.status=response.status;throw error;}
  const result=await response.json();
  if(!Array.isArray(result.items)||result.page!==page||result.pageSize!==20||!Number.isSafeInteger(result.total)||result.total<0)throw Error('审计响应格式异常');
  return result;
}
