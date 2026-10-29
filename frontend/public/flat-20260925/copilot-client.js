import {createSessionClient} from './session-client.js';
/** Copilot 不展示本地初始化、索引等运维占位文案。 */
export function publicProductDescription(value){
  if(typeof value!=='string')return '';
  return value.replace(/本地目录初始化；数据是否可用由后续文件索引决定[。．]?/g,'').replace(/本地目录初始化[；;]/g,'').trim();
}
/** 白名单动作转为本地参数，不接受模型提供的URL或管理命令。 */
export function safeAction(action){
  if(!['APPLY_SCIENTIFIC_SEARCH','APPLY_PREVIEW_FILTER','OPEN_PRODUCT_DETAILS','OPEN_PRODUCT_CATALOG','OPEN_ABOUT'].includes(action?.type))throw Error('不支持的建议操作');
  const p=action.parameters||{};
  if(['OPEN_PRODUCT_CATALOG','OPEN_ABOUT'].includes(action.type))return {type:action.type,parameters:{}};
  if(!/^[A-Z][A-Z0-9_]{1,63}$/.test(p.productCode))throw Error('产品条件无效');
  const result={productCode:p.productCode};
  if(action.type!=='OPEN_PRODUCT_DETAILS'){
    if(!['REALTIME','FORECAST'].includes(p.dataMode))throw Error('数据模式无效');result.dataMode=p.dataMode;
    for(const key of ['from','to','cycleTime']){if(key==='cycleTime'&&!p[key])continue;if(typeof p[key]!=='string'||!/(Z|[+-]\d{2}:\d{2})$/.test(p[key])||!Number.isFinite(Date.parse(p[key])))throw Error('时间条件无效');result[key]=new Date(p[key]).toISOString();}
    if(result.from>result.to)throw Error('时间范围无效');
    if(p.leadMinutes!=null){const lead=Number(p.leadMinutes);if(!Number.isSafeInteger(lead)||lead<0)throw Error('预报时效无效');result.leadMinutes=lead;}
    if(result.dataMode==='REALTIME'&&(result.cycleTime||result.leadMinutes!=null))throw Error('实况不接受预报参数');
    if(!['UTC','Asia/Shanghai'].includes(p.displayZone))throw Error('时间解释无效');result.displayZone=p.displayZone;
  }
  return {type:action.type,parameters:result};
}
/** 自然语言和最多12条带角色的短期消息 → 后端受控查询；未说明的本地时间按北京时间解释。 */
export async function queryCopilot(message,recentMessages=[],fetcher=fetch){
  if(typeof message!=='string'||!message.trim()||message.length>2000)throw Error('请输入不超过 2000 字的问题');
  const recent=recentMessages.slice(-12).map(item=>{
    if(!item||!['USER','ASSISTANT'].includes(item.role)||typeof item.content!=='string'||!item.content.trim())throw Error('对话上下文无效');
    return {role:item.role,content:item.content.slice(0,2000)};
  });
  const session=await createSessionClient(fetcher).current();if(!session.csrfToken)throw Error('会话校验失败，请重试');
  let response;try{response=await fetcher('/api/v1/copilot/queries',{method:'POST',credentials:'same-origin',cache:'no-store',signal:AbortSignal.timeout(55000),headers:{'Content-Type':'application/json','X-CSRF-TOKEN':session.csrfToken},body:JSON.stringify({message:message.trim(),displayZone:'Asia/Shanghai',recentMessages:recent})});}catch{throw Object.assign(Error('AI 请求超时或网络异常'),{retryable:true});}
  if(!response.ok)throw Object.assign(Error(response.status===429?'请求过于频繁，请稍后再试':'AI 查询失败，请稍后重试'),{retryable:response.status>=500});
  const result=await response.json();if(typeof result.answer!=='string'||!Array.isArray(result.suggestedActions))throw Error('AI 响应格式异常');return result;
}

/** 每次用户提交只调用一次；连续临时失败第三次才提示不可用，成功（含可信降级结果）或清空对话时复位。 */
export function createCopilotClient(query=queryCopilot){
  let failures=0,generation=0;
  return {
    reset(){failures=0;++generation;},
    async query(...args){
      const token=generation;
      try{const result=await query(...args);if(token===generation)failures=0;return result;}
      catch(error){
        if(error.retryable&&token===generation){
          ++failures;
          error.message=failures<3?'本次请求未成功，请点击“重试”':'AI 暂不可用，请稍后再试，或使用数据检索';
        }
        throw error;
      }
    }
  };
}
