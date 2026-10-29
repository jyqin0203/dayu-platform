import {createSessionClient} from './session-client.js';
/** Incremental UTF-8/event decoder; a truncated stream is never treated as success. */
export async function readEvents(body,onEvent){
  const reader=body.getReader(),decoder=new TextDecoder();let pending='',done=false;
  try{for(;;){const chunk=await reader.read();pending+=decoder.decode(chunk.value||new Uint8Array(),{stream:!chunk.done});
    pending=pending.replace(/\r\n/g,'\n');let at;
    while((at=pending.indexOf('\n\n'))>=0){const block=pending.slice(0,at);pending=pending.slice(at+2);let event='message',data=[];
      for(const line of block.split('\n')){if(line.startsWith('event:'))event=line.slice(6).trim();if(line.startsWith('data:'))data.push(line.slice(5).trimStart());}
      if(data.length){if(done)throw Error('完成事件后收到额外数据');const value=JSON.parse(data.join('\n'));if(event==='error')throw Object.assign(Error(value.message),{code:value.code,retryable:true});onEvent(event,value);if(event==='done'){if(value.completed!==true)throw Error('回答未完成');done=true;}}
    }
    if(pending.length>131072)throw Error('响应过大');if(chunk.done)break;
  }if(!done)throw Object.assign(Error('连接中断，回答未完成，请手动重试'),{retryable:true});}
  finally{await reader.cancel().catch(()=>{});reader.releaseLock();}
}
export function createStreamingCopilot(fetcher=fetch){
  let conversation=null,controller=null,generation=0;
  async function csrf(){const s=await createSessionClient(fetcher).current();if(!s.csrfToken)throw Error('会话校验失败');return s.csrfToken;}
  return {
    cancel(){controller?.abort();},
    async reset(){++generation;controller?.abort();const id=conversation;conversation=null;if(id){const token=await csrf();await fetcher('/api/v1/copilot/conversations/'+id,{method:'DELETE',credentials:'same-origin',headers:{'X-CSRF-TOKEN':token}});}},
    async query(message,pageContext,onEvent){
      const current=generation;controller=new AbortController();const active=controller;const token=await csrf();if(current!==generation||active.signal.aborted)throw new DOMException('Cancelled','AbortError');
      if(!conversation){const response=await fetcher('/api/v1/copilot/conversations',{method:'POST',credentials:'same-origin',headers:{'X-CSRF-TOKEN':token},signal:active.signal});
        if(response.status===404)return null;if(!response.ok)throw Error('无法创建对话，请稍后重试');const data=await response.json();if(current!==generation)throw new DOMException('Cancelled','AbortError');conversation=data.conversationId;}
      const response=await fetcher('/api/v1/copilot/conversations/'+conversation+'/stream',{method:'POST',credentials:'same-origin',signal:active.signal,headers:{'Content-Type':'application/json','X-CSRF-TOKEN':token},body:JSON.stringify({message,displayZone:'Asia/Shanghai',pageContext})});
      if(!response.ok)throw Object.assign(Error('AI请求失败，请稍后重试'),{retryable:response.status>=500});
      let result=null,answer='';await readEvents(response.body,(event,value)=>{if(current!==generation)return;if(event==='result')result=value;if(event==='answer_delta')answer+=value.text;onEvent(event,value);});
      return {...(result||{criteria:null,suggestedActions:[],degraded:false}),answer};
    }
  };
}
