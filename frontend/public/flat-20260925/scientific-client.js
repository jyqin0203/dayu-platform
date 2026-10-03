import {dateOf} from './timeline-data.js';

export const zones=Object.freeze({UTC:{offset:0,label:'UTC'},'Asia/Shanghai':{offset:480,label:'北京时间 UTC+8'}});
/** 明确时区的分钟级输入 → UTC ISO，不依赖浏览器所在时区。当前只开放无夏令时的两个选项。 */
export function toUtc(value,zone){
  if(!zones[zone]||!/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}$/.test(value))throw Error('请填写完整时间并选择时区');
  return new Date(dateOf(value.replace(/[-T:]/g,''))-zones[zone].offset*60000).toISOString();
}
/** UTC 时间 → 所选显示时区的输入值；切换时区时保留同一真实时刻。 */
export function timeInput(iso,zone){
  if(!zones[zone])throw Error('不支持的时区');
  return new Date(new Date(iso).getTime()+zones[zone].offset*60000).toISOString().slice(0,16);
}
/** 表单快照 → 一基分页查询参数；实况不会携带隐藏的预报筛选值。 */
export function scientificParams(form,page=1){
  if(!/^[A-Z][A-Z0-9_]{1,63}$/.test(form.productCode)||!['REALTIME','FORECAST'].includes(form.dataMode))throw Error('请选择产品和可用模式');
  if(!Number.isInteger(page)||page<1)throw Error('页码无效');
  const from=toUtc(form.from,form.zone),to=toUtc(form.to,form.zone);
  if(from>to)throw Error('开始时间不能晚于结束时间');
  const params=new URLSearchParams({productCode:form.productCode,dataMode:form.dataMode,from,to,page,pageSize:20});
  if(form.dataMode==='FORECAST'){
    if(form.cycleTime)params.set('cycleTime',toUtc(form.cycleTime,form.zone));
    if(form.leadMinutes!=='' && form.leadMinutes!=null){
      const lead=Number(form.leadMinutes);
      if(!Number.isInteger(lead)||lead<0)throw Error('预报时效必须为非负整数分钟');
      params.set('leadMinutes',lead);
    }
  }
  return params;
}
/** 只查询候选 NC 元数据；不构造下载地址、不发起下载或读取 NC。 */
export async function searchScientific(form,page=1,fetcher=fetch,signal){
  const params=scientificParams(form,page);
  let response;
  try{response=await fetcher('/api/v1/scientific-assets?'+params,{credentials:'same-origin',cache:'no-store',signal});}
  catch(error){if(error.name==='AbortError')throw error;throw Error('检索网络异常，请重试');}
  if(!response.ok)throw Error(response.status===422?'检索条件不合法，请检查时间与时效':response.status===404?'该产品已不可用，请重新选择':'检索服务暂时不可用，请重试');
  const result=await response.json();
  if(!Array.isArray(result.items)||!Number.isInteger(result.total)||result.total<0||result.page!==page||result.pageSize!==20)throw Error('检索响应格式异常');
  return result;
}
