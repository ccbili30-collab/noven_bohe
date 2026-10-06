# 参考实现：仿真手机（三页 tab + 实时状态 + 主题跟随）

这是经真机包装页验证过的完整界面岛：消息流（气泡弹入 + typing）、状态页（读
NOVEN_FRAME.state 画进度条，无数据显示"尚无登记"）、信件列表→详情、底栏
图标 tab。配色全部走 theme 变量，亮暗双主题自动正确。

直接整段抄改即可；注意演示的聊天文本是写死的样板，真实使用时界面内容应
来自剧情上下文，只有结构可以照抄。

```html
<style>
  .ph{font:12px/1.5 system-ui,sans-serif;color:var(--tx);background:var(--bg);
      border-radius:18px;overflow:hidden;display:flex;flex-direction:column;
      border:1px solid var(--ln)}
  .ph-bar{display:flex;justify-content:space-between;align-items:center;
      padding:6px 14px;font-size:11px;font-weight:600;background:var(--sf);
      border-bottom:.5px solid var(--ln)}
  .ph-ic{display:flex;gap:5px;align-items:center}
  .ph-body{flex:1;min-height:0;position:relative}
  .ph-page{display:none;flex-direction:column;height:100%;max-height:430px;overflow-y:auto}
  .ph-page.on{display:flex}
  .ph-nav{display:flex;background:var(--sf);border-top:.5px solid var(--ln)}
  .ph-nav button{flex:1;border:0;background:none;padding:9px 0 8px;cursor:pointer;
      font-size:10px;color:var(--tx);opacity:.45;display:flex;flex-direction:column;
      align-items:center;gap:2px;transition:opacity .15s}
  .ph-nav button.on{opacity:1;color:var(--ac)}
  .ph-nav svg{width:18px;height:18px;stroke:currentColor;fill:none;stroke-width:1.6;
      stroke-linecap:round;stroke-linejoin:round}
  /* —— 消息页 —— */
  .ms-head{padding:9px 12px;text-align:center;font-weight:600;font-size:13px;
      background:var(--sf);border-bottom:.5px solid var(--ln)}
  .ms-flow{padding:10px;display:flex;flex-direction:column;gap:9px}
  .ms-row{display:flex;gap:7px;align-items:flex-start;animation:pop .35s both}
  .ms-row.me{flex-direction:row-reverse}
  .ms-av{width:26px;height:26px;border-radius:8px;flex:none;display:flex;
      align-items:center;justify-content:center;font-size:11px;color:#fff}
  .ms-b{max-width:68%;padding:7px 10px;font-size:12.5px;border-radius:4px 11px 11px 11px;
      background:var(--cd)}
  .ms-row.me .ms-b{background:var(--ac);color:#fff;border-radius:11px 4px 11px 11px}
  .ms-typing{display:inline-flex;gap:3px;padding:9px 11px;background:var(--cd);
      border-radius:4px 11px 11px 11px;width:fit-content}
  .ms-typing i{width:5px;height:5px;border-radius:50%;background:var(--tx);opacity:.35;
      animation:blink 1.2s infinite}
  .ms-typing i:nth-child(2){animation-delay:.2s}
  .ms-typing i:nth-child(3){animation-delay:.4s}
  /* —— 状态页 —— */
  .st-id{display:flex;gap:10px;align-items:center;padding:14px 14px 10px}
  .st-face{width:40px;height:40px;border-radius:12px;flex:none;
      background:linear-gradient(135deg,var(--ac),color-mix(in srgb,var(--ac) 55%,#7a5de0))}
  .st-name{font-weight:600;font-size:15px}
  .st-sub{font-size:11px;opacity:.55}
  .st-m{padding:0 14px 8px}
  .st-mrow{display:flex;justify-content:space-between;align-items:baseline}
  .st-mrow .l{font-size:11px;opacity:.55;letter-spacing:.04em}
  .st-mrow .v{font-size:15px;font-weight:600}
  .st-tr{height:6px;border-radius:3px;margin:4px 0 10px;overflow:hidden;
      background:color-mix(in srgb,var(--tx) 12%,transparent)}
  .st-fl{height:100%;width:0;border-radius:3px;transition:width .45s ease}
  .st-empty{padding:26px 14px;text-align:center;font-size:11px;opacity:.5}
  /* —— 信件页 —— */
  .lt-item{display:flex;gap:9px;align-items:center;padding:11px 14px;cursor:pointer;
      border-bottom:.5px solid var(--ln)}
  .lt-dot{width:6px;height:6px;border-radius:50%;background:var(--ac);flex:none}
  .lt-dot.read{background:transparent}
  .lt-t{flex:1;min-width:0}
  .lt-t .a{font-size:12.5px;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}
  .lt-t .b{font-size:10.5px;opacity:.5;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}
  .lt-view{padding:16px 14px;font-size:12.5px;line-height:1.8;display:none}
  .lt-view.on{display:block}
  .lt-back{font-size:11px;color:var(--ac);cursor:pointer;margin-bottom:10px;width:fit-content}
  @keyframes pop{from{opacity:0;transform:translateY(6px)}to{opacity:1;transform:none}}
  @keyframes blink{0%,60%,100%{opacity:.25}30%{opacity:.8}}
</style>
<div class="ph" id="ph">
  <div class="ph-bar"><span id="clk">21:47</span>
    <span class="ph-ic"><svg width="14" height="10" viewBox="0 0 14 10" fill="currentColor"><rect x="0" y="6" width="2.4" height="4" rx="1"/><rect x="3.6" y="4" width="2.4" height="6" rx="1"/><rect x="7.2" y="2" width="2.4" height="8" rx="1"/><rect x="10.8" y="0" width="2.4" height="10" rx="1"/></svg>
    <svg width="20" height="10" viewBox="0 0 20 10" fill="none"><rect x="0" y="0.5" width="17" height="9" rx="2.5" stroke="currentColor" opacity=".5"/><rect x="2" y="2.5" width="11" height="5" rx="1.2" fill="currentColor"/><path d="M19 3.5v3a1.5 1.5 0 000-3z" fill="currentColor" opacity=".5"/></svg></span></div>
  <div class="ph-body">
    <div class="ph-page on" data-p="0">
      <div class="ms-head" id="ms-title">林晚</div>
      <div class="ms-flow" id="ms-flow"></div>
    </div>
    <div class="ph-page" data-p="1">
      <div class="st-id"><div class="st-face"></div>
        <div><div class="st-name" id="st-name">—</div><div class="st-sub" id="st-sub">本局状态</div></div></div>
      <div class="st-m" id="st-m"></div>
      <div class="st-empty" id="st-empty" hidden>尚无登记的状态数据</div>
    </div>
    <div class="ph-page" data-p="2">
      <div id="lt-list"></div>
      <div class="lt-view" id="lt-view"><div class="lt-back" id="lt-back">‹ 返回</div><div id="lt-text"></div></div>
    </div>
  </div>
  <div class="ph-nav" id="ph-nav">
    <button data-t="0" class="on"><svg viewBox="0 0 24 24"><path d="M21 12a8 8 0 01-8 8H5l-2 2V12a8 8 0 018-8h2a8 8 0 018 8z"/></svg>消息</button>
    <button data-t="1"><svg viewBox="0 0 24 24"><path d="M12 21s-7-4.6-9.2-9A5.4 5.4 0 0112 6.5 5.4 5.4 0 0121.2 12c-2.2 4.4-9.2 9-9.2 9z"/></svg>状态</button>
    <button data-t="2"><svg viewBox="0 0 24 24"><rect x="3" y="5" width="18" height="14" rx="2"/><path d="M3 7l9 6 9-6"/></svg>信件</button>
  </div>
</div>
<script>
(function(){
  var F=window.NOVEN_FRAME||{},T=F.theme||{};
  var root=document.getElementById('ph'),S=root.style;
  var tx=T.text||'#1c1e22',ac=T.accent||'#4caf8d';
  var sf=T.surface||(T.dark?'#171a1f':'#ffffff');
  root.style.setProperty('--tx',tx);root.style.setProperty('--ac',ac);
  root.style.setProperty('--sf',sf);
  root.style.setProperty('--bg',T.dark?'#101216':'#eceff2');
  root.style.setProperty('--cd',T.dark?'#23262d':'#ffffff');
  root.style.setProperty('--ln',T.dark?'rgba(255,255,255,.08)':'rgba(0,0,0,.07)');

  // 顶栏时钟
  var clk=document.getElementById('clk');
  var tick=function(){var d=new Date();clk.textContent=('0'+d.getHours()).slice(-2)+':'+('0'+d.getMinutes()).slice(-2)};
  tick();

  // 消息流：角色名优先取挂载卡
  var who=(F.card&&F.card.name)||'旅伴';
  document.getElementById('ms-title').textContent=who;
  document.getElementById('st-name').textContent=who;
  var flow=document.getElementById('ms-flow');
  var msgs=[
    {me:0,t:'城门的守卫换了班，今晚宵禁提前了。'},
    {me:1,t:'那我们走北墙的水道？'},
    {me:0,t:'嗯，带上灯。涨潮前只有二十分钟窗口。'},
    {me:0,t:'还有——她留下的信，我读完了。'}
  ];
  var hue={'林':ac};
  msgs.forEach(function(m,i){
    var r=document.createElement('div');
    r.className='ms-row'+(m.me?' me':'');r.style.animationDelay=(0.12*i)+'s';
    if(!m.me){var a=document.createElement('div');a.className='ms-av';
      a.style.background=ac;a.textContent=(who||'·').charAt(0);r.appendChild(a);}
    var b=document.createElement('div');b.className='ms-b';b.textContent=m.t;r.appendChild(b);
    flow.appendChild(r);
  });
  var ty=document.createElement('div');ty.className='ms-row';ty.style.animationDelay='0.6s';
  ty.innerHTML='<div class="ms-av" style="background:'+ac+'">'+(who||'·').charAt(0)+'<\/div><div class="ms-typing"><i><\/i><i><\/i><i><\/i><\/div>';
  flow.appendChild(ty);

  // 状态页：吃 NOVEN_FRAME.state，空则明示无数据
  function drawState(){
    var st=F.state||{},mx=F.max||{},box=document.getElementById('st-m');
    box.innerHTML='';
    var keys=Object.keys(st);
    document.getElementById('st-empty').hidden=keys.length>0;
    keys.slice(0,5).forEach(function(k){
      var v=st[k],row=document.createElement('div');
      var isNum=typeof v==='number';
      row.innerHTML='<div class="st-mrow"><span class="l">'+k+'<\/span><span class="v">'+
        (isNum&&mx[k]!=null?v+'/'+mx[k]:v)+'<\/span><\/div>'+
        (isNum?'<div class="st-tr"><div class="st-fl" style="background:'+(mx[k]!=null&&v/mx[k]<0.3?'#e05d5d':ac)+'"><\/div><\/div>':'');
      box.appendChild(row);
      if(isNum){var w=Math.min(100,100*v/(mx[k]!=null?mx[k]:100));
        requestAnimationFrame(function(){row.querySelector('.st-fl').style.width=w+'%'})}
    });
  }
  drawState();
  window.addEventListener('noven:frame',function(){F=window.NOVEN_FRAME||{};drawState()});

  // 信件页：列表 → 详情
  var letters=[
    {a:'未寄出的信',b:'致北方灯塔的守夜人',t:'守夜人：\n\n灯塔熄了三天，渔火却还亮着。你说海认得每一盏真火——那它应该也认得我们。\n\n别等我，但替我留灯。'},
    {a:'旅馆账单',b:'白鸥旅店 · 三日房钱',t:'住店三日，加床一张，热水两桶，窗玻璃赔一片。\n\n合计：银币十四枚。\n\n备注：你们房间里的笑声吵醒了整条走廊，不收钱。'}
  ];
  var list=document.getElementById('lt-list');
  letters.forEach(function(L,i){
    var it=document.createElement('div');it.className='lt-item';
    it.innerHTML='<span class="lt-dot'+(i?' read':'')+'"><\/span><div class="lt-t"><div class="a">'+L.a+'<\/div><div class="b">'+L.b+'<\/div><\/div>';
    it.onclick=function(){document.getElementById('lt-text').innerText=L.t;
      document.getElementById('lt-view').classList.add('on');list.style.display='none'};
    list.appendChild(it);
  });
  document.getElementById('lt-back').onclick=function(){
    document.getElementById('lt-view').classList.remove('on');list.style.display=''};

  // 底部 tab
  document.getElementById('ph-nav').addEventListener('click',function(e){
    var b=e.target.closest('button');if(!b)return;
    document.querySelectorAll('#ph-nav button').forEach(function(x){x.classList.toggle('on',x===b)});
    document.querySelectorAll('.ph-page').forEach(function(p){p.classList.toggle('on',p.dataset.p===b.dataset.t)});
  });
})();
</script>
```
