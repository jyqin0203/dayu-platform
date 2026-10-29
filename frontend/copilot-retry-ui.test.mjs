import test from 'node:test';
import assert from 'node:assert/strict';
import vm from 'node:vm';
import {readFile} from 'node:fs/promises';
import {createCopilotClient} from './public/flat-20260925/copilot-client.js';

// Exercise the real submit handler with a minimal DOM; no network or model calls.
test('失败保留问题，按钮手动重试，成功清空；不自动提交',async()=>{
  const nodes=new Map();
  const element=()=>({children:[],style:{setProperty(){}},value:'',textContent:'',disabled:false,
    setAttribute(){},addEventListener(){},append(...items){this.children.push(...items);},
    replaceChildren(){this.children=[];},get firstChild(){return this.children[0];}});
  const get=id=>{if(!nodes.has(id))nodes.set(id,element());return nodes.get(id);};
  let calls=0,fail=true;
  const context=vm.createContext({document:{getElementById:get,createElement:element,body:element(),querySelector:()=>null},
    window:{addEventListener(){}},publicProductDescription:x=>x,safeAction:x=>x,openScientificSearch(){},
    createStreamingCopilot:()=>({query:async()=>null,cancel(){},reset:async()=>{}}),
    createCopilotClient:()=>createCopilotClient(async text=>{calls++;assert.equal(text,'有多少NC？');
      if(fail)throw Object.assign(Error('failed'),{retryable:true});return {answer:'结果',suggestedActions:[],degraded:true};})});
  const code=(await readFile(new URL('./public/flat-20260925/copilot.js',import.meta.url),'utf8')).replace(/^import .*;\n/gm,'');
  vm.runInContext(code,context);
  get('copilot-question').value='有多少NC？';
  for(let i=1;i<=3;i++){
    await get('copilot-form').onsubmit({preventDefault(){}});
    assert.equal(calls,i);assert.equal(get('copilot-question').value,'有多少NC？');
    assert.equal(get('copilot-send').textContent,'重试');assert.equal(get('copilot-question').disabled,false);
    assert.match(get('copilot-status').textContent,i<3?/点击“重试”/:/暂不可用/);
  }
  fail=false;await get('copilot-form').onsubmit({preventDefault(){}});
  assert.equal(calls,4);assert.equal(get('copilot-question').value,'');assert.equal(get('copilot-send').textContent,'发送');
  assert.doesNotMatch(get('copilot-status').textContent,/不可用/);
  fail=true;get('copilot-question').value='有多少NC？';await get('copilot-form').onsubmit({preventDefault(){}});
  assert.match(get('copilot-status').textContent,/点击“重试”/);
});
