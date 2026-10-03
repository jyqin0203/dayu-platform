// Loaded before the external map scripts: even a failed CDN gets a retry state.
(() => {
  const screen=document.getElementById('loading-screen');
  const message=document.getElementById('loading-message');
  const retry=document.getElementById('loading-retry');
  let watchdog,hideTimer;
  function stage(step,text){
    clearTimeout(hideTimer);clearTimeout(watchdog);
    screen.hidden=false;screen.classList.remove('leaving','failed');
    screen.setAttribute('aria-busy','true');retry.hidden=true;message.textContent=text;
    screen.querySelectorAll('[data-stage]').forEach((el,i)=>{el.classList.toggle('active',i===step);el.classList.toggle('done',i<step);});
    watchdog=setTimeout(()=>fail('加载时间较长，请检查网络后重试。'),35000);
  }
  function fail(text){clearTimeout(watchdog);screen.classList.add('failed');screen.setAttribute('aria-busy','false');message.textContent=text;retry.hidden=false;}
  function ready(){clearTimeout(watchdog);screen.setAttribute('aria-busy','false');message.textContent='地图已就绪';screen.classList.add('leaving');hideTimer=setTimeout(()=>{screen.hidden=true;},350);}
  retry.onclick=()=>location.reload();
  window.mapLoading={stage,ready,fail};
  stage(0,'正在加载地图组件…');
})();
