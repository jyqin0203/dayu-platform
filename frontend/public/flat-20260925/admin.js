import {loadAdminOverview} from './admin-client.js';
import {mountScans} from './admin-scans.js';
import {mountProducts} from './admin-products.js';
import {mountAudits} from './admin-audits.js';
import {mountUsers} from './admin-users.js';
const $=id=>document.getElementById(id);
let generation=0,active;
function denied(message){++generation;active?.abort();scans.reset();products.reset(true);audits.reset();users.reset();$('admin-content').hidden=true;for(const id of ['admin-counts','admin-downloads','admin-scan','admin-health'])$(id).replaceChildren();$('admin-status').textContent=message;}
const scans=mountScans(denied),products=mountProducts(denied),audits=mountAudits(denied),users=mountUsers(denied);
const utc=value=>value?new Date(value).toISOString().replace('T',' ').slice(0,19)+' UTC':'—';
const healthNames={HEALTHY:'正常',STALE:'延迟',MISSING:'缺失',DISABLED:'未启用'};
const scanNames={RUNNING:'运行中',SUCCEEDED:'成功',PARTIAL:'部分成功',FAILED:'失败',INTERRUPTED:'已中断'};
function metrics(id,entries){
  for(const [label,value] of entries){const card=document.createElement('div'),name=document.createElement('span'),number=document.createElement('strong');name.textContent=label;number.textContent=value??'—';card.append(name,number);$(id).append(card);}
}
/** 清空旧管理数据后重新验证身份；403/退出后不保留旧表格在 DOM 中。 */
async function refresh(){
  scans.reset();
  products.reset();
  audits.reset();
  users.reset();
  const token=++generation;active?.abort();const controller=new AbortController();active=controller;
  $('admin-content').hidden=true;for(const id of ['admin-counts','admin-downloads','admin-scan','admin-health'])$(id).replaceChildren();
  $('admin-status').textContent='正在读取管理数据…';const timer=setTimeout(()=>controller.abort(),15000);
  try{
    const {dashboard:d,health}=await loadAdminOverview(fetch,controller.signal);if(token!==generation)return;
    metrics('admin-counts',[['已发布产品',d.publishedProducts],['可预览产品',d.previewAvailableProducts],['可下载产品',d.downloadAvailableProducts],['数据缺失产品',d.missingProducts],['数据延迟产品',d.staleProducts]]);
    metrics('admin-downloads',[['下载授权次数',d.downloads.authorizedRequests],['用户数',d.downloads.uniqueUsers],['机构数',d.downloads.uniqueOrganizations],['文件数',d.downloads.uniqueAssets]]);
    const scan=d.latestScan;
    $('admin-scan').textContent=scan?`任务 #${scan.scanRunId} · ${scanNames[scan.status]||scan.status}\n开始：${utc(scan.startedAt)}\n结束：${utc(scan.finishedAt)}\n扫描 ${scan.scannedFiles} · 新增 ${scan.createdAssets} · 更新 ${scan.updatedAssets} · 撤下图片索引 ${scan.removedWebpAssets} · 缺失 NC ${scan.missingNetcdfAssets} · 错误 ${scan.errorCount}`:'暂无扫描记录';
    for(const item of health){const row=document.createElement('tr');for(const value of [item.productCode,item.dataMode==='FORECAST'?'预报':'实况',healthNames[item.status]||item.status,utc(item.latestValidTime),item.staleAfterMinutes]){const cell=document.createElement('td');cell.textContent=value??'—';row.append(cell);}$('admin-health').append(row);}
    if(!health.length){const row=document.createElement('tr'),cell=document.createElement('td');cell.colSpan=5;cell.textContent='暂无产品状态记录';row.append(cell);$('admin-health').append(row);}
    $('admin-content').hidden=false;$('admin-status').textContent='更新于 '+utc(new Date());
    scans.load();
    products.load();
    audits.load();
    users.load();
  }catch(error){if(token===generation){if([401,403].includes(error.status))denied(error.message);else $('admin-status').textContent=error.name==='AbortError'?'读取超时，请重试':error.message;}}
  finally{clearTimeout(timer);}
}
$('admin-refresh').onclick=refresh;
// 返回前台重新校验，降低其他标签页退出/禁用后仍显示旧管理数据的风险。
document.addEventListener('visibilitychange',()=>{if(!document.hidden)refresh();});
window.addEventListener('pageshow',event=>{if(event.persisted)refresh();});
refresh();
