import {createSessionClient} from './session-client.js';
/** 白名单动作转为本地参数，不接受模型提供的URL或管理命令。 */
export function safeAction(action){
  if(!['APPLY_SCIENTIFIC_SEARCH','APPLY_PREVIEW_FILTER','OPEN_PRODUCT_DETAILS'].includes(action?.type))throw Error('不支持的建议操作');
  const p=action.parameters||{};if(!/^[A-Z][A-Z0-9_]{1,63}$/.test(p.productCode))throw Error('产品条件无效');
  const result={productCode:p.productCode};
  if(action.type!=='OPEN_PRODUCT_DETAILS'){
    if(!['REALTIME','FORECAST'].includes(p.dataMode))throw Error('数据模式无效');result.dataMode=p.dataMode;
    for(const key of ['from','to','cycleTime']){if(key==='cycleTime'&&!p[key])continue;if(typeof p[key]!=='string'||!/(Z|[+-]\d{2}:\d{2})$/.test(p[key])||!Number.isFinite(Date.parse(p[key])))throw Error('时间条件无效');result[key]=new Date(p[key]).toISOString();}
    if(result.from>result.to)throw Error('时间范围无效');
    if(p.leadMinutes!=null){const lead=Number(p.leadMinutes);if(!Number.isSafeInteger(lead)||lead<0)throw Error('预报时效无效');result.leadMinutes=lead;}
    if(result.dataMode==='REALTIME'&&(result.cycleTime||result.leadMinutes!=null))throw Error('实况不接受预报参数');
  }
  return {type:action.type,parameters:result};
}
/** 自然语言和最多6条近期消息 → 后端受控查询；密钥仅在Java服务端。 */
export async function queryCopilot(message,displayZone,recentMessages=[],fetcher=fetch){
  if(!message.trim()||message.length>2000||!['UTC','Asia/Shanghai'].includes(displayZone))throw Error('请填写有效问题并选择时区');
  const session=await createSessionClient(fetcher).current();if(!session.csrfToken)throw Error('会话校验失败，请重试');
  let response;try{response=await fetcher('/api/v1/copilot/queries',{method:'POST',credentials:'same-origin',cache:'no-store',signal:AbortSignal.timeout(45000),headers:{'Content-Type':'application/json','X-CSRF-TOKEN':session.csrfToken},body:JSON.stringify({message:message.trim(),displayZone,recentMessages:recentMessages.slice(-6).map(m=>m.slice(0,2000))})});}catch{throw Error('AI 请求超时或网络异常，可使用数据检索');}
  if(!response.ok)throw Error(response.status===429?'请求过于频繁，请稍后再试':response.status===503?'AI 暂不可用，可使用数据检索':'AI 查询失败，请稍后重试');
  const result=await response.json();if(typeof result.answer!=='string'||!Array.isArray(result.suggestedActions))throw Error('AI 响应格式异常');return result;
}
