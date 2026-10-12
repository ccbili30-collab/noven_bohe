package com.openminis.app.ui.chat

/**
 * 舞台协议体检岛（v0.3 S8）：内置整页作品，从聊天菜单「舞台自检」
 * 打开。逐项核验帧字段、helper、出站通道、能力基线，产出可复制
 * 的 JSON 报告——PC 端有同一张检查页，报告可逐项对账。
 *
 * 声明 input:"own"：send 门控（手势窗/一手势一发/限频）正是被测
 * 对象；页面 onload 立即 ready() 满足 own 舞台的首屏信号要求。
 */
internal val NOVEX_STAGE_CHECK_DEF = NovexArtifactDef(
    id = "novex.stage-check",
    kind = "stage-check",
    name = "舞台自检",
    defaultSlot = null,
    template = STAGE_CHECK_HTML,
    fullscreen = true,
    ownInput = true,
)

// Kotlin raw string 内禁止裸 $（会被当模板插值）——JS 一律用字符串拼接。
private const val STAGE_CHECK_HTML = """
<style>
  body{font:14px/1.6 -apple-system,sans-serif;padding:16px;color:#222}
  h1{font-size:16px;margin:0 0 8px}
  .row{display:flex;justify-content:space-between;gap:8px;padding:4px 0;border-bottom:1px solid #eee}
  .ok{color:#1a7f37}.bad{color:#cf222e}.skip{color:#9a6700}
  .btns{margin:12px 0}.btns button{margin-right:8px;padding:6px 12px;font-size:13px}
  pre{background:#f6f8fa;padding:10px;font-size:11px;overflow:auto;max-height:40vh}
</style>
<h1>舞台协议体检 v0.3</h1>
<div class="btns">
  <button onclick="runSend(true)">发送探针（真触摸）</button>
  <button onclick="runQuery()">查证探针</button>
  <button onclick="copyReport()">复制报告</button>
</div>
<div id="list"></div>
<pre id="report"></pre>
<script>
var R={platform:'android-webview',ts:0,checks:[]};
var baseline=111;
function add(n,ok,d){R.checks.push({name:n,ok:ok?'pass':(ok===null?'skip':'fail'),detail:d||''});paint()}
function paint(){
  var l=document.getElementById('list'),r=document.getElementById('report');
  l.innerHTML=R.checks.map(function(c){
    var cls=c.ok==='pass'?'ok':(c.ok==='fail'?'bad':'skip');
    return '<div class="row"><span>'+c.name+'</span><span class="'+cls+'">'+c.ok+(c.detail?' · '+c.detail:'')+'</span></div>';
  }).join('');
  r.textContent=JSON.stringify(R,null,2);
}
var lastFrame=null,sendProbe='',composeProbe='';
function onFrame(F){
  lastFrame=F;
  if(sendProbe&&F.sendResult){add('send 回执',true,sendProbe+' → '+F.sendResult.result+'/'+(F.sendResult.why||'-'));sendProbe=''}
  if(composeProbe&&F.composeResult){add('compose 回执',true,composeProbe+' → '+F.composeResult.result+'/'+(F.composeResult.why||'-'));composeProbe=''}
  if(F.reason==='query')add('reason:query 到达',true,'deltas='+(F.deltas||[]).length)
}
function runStatic(){
  var F=window.NOVEN_FRAME;
  if(!F){add('NOVEN_FRAME 存在',false);return}
  R.webview=F.env&&F.env.webview;
  add('bridgeVersion',F.env&&F.env.bridgeVersion===2,'v='+(F.env&&F.env.bridgeVersion));
  ['card','session','env','state','max','theme','transcript','turn'].forEach(function(k){
    add('帧字段 '+k,F[k]!=null)})
  add('env.webview 数值',typeof R.webview==='number','v='+R.webview);
  add('WebView ≥ 基线 '+baseline,R.webview==null?'skip':R.webview>=baseline,'v='+R.webview);
  add('reason 字段',typeof F.reason==='string','reason='+F.reason);
  if(F.scene!=null)add('scene 视图通知',typeof F.scene==='string','v='+F.scene);
  else add('scene 视图通知',null,'未声明（模型经 state 写 scene）');
  var t=F.transcript;
  if(Array.isArray(t)&&t.length){
    var s=t[0];
    add('transcript 段形状',['id','messageId','segment','turnId','role','kind'].every(function(k){return s[k]!=null}),'segs='+t.length);
  } else add('transcript 段形状',t!=null?'skip':false,'segs='+(t?t.length:'-'));
  if(F.cast)add('cast 对象数组',Array.isArray(F.cast)&&(!F.cast.length||(F.cast[0].id!=null&&F.cast[0].name!=null)));
  else add('cast 名册',null,'字段未下发');
  if(F.choices)add('choices 形状',Array.isArray(F.choices.items),'turnId='+(F.choices.turnId||'-'));
  else add('choices',null,'字段未下发');
  if(F.deltas){
    var okArr=Array.isArray(F.deltas);
    add('deltas 数组',okArr,'n='+F.deltas.length);
    if(okArr&&F.deltas.length){var d0=F.deltas[0];add('deltas 元素形状',d0.seq!=null&&d0.messageId!=null&&d0.envelope!=null)}
  } else add('deltas',null,'字段未下发');
  if(F.archive!=null)add('archive 对象',typeof F.archive==='object');
  else add('archive',null,'字段未下发');
  ['query','compose','send','asset','ready'].forEach(function(h){
    add('helper '+h+'()',typeof F[h]==='function')});
  if(F.assets){var k=Object.keys(F.assets)[0];
    add('assets 映射',k!=null,'n='+Object.keys(F.assets).length);
    if(k!=null)add('asset() 形状',(''+F.asset(k)).indexOf('novex-asset://')===0,k);
  } else add('assets/asset()',null,'本会话无卡资源');
  add('CSS 容器查询',window.CSS&&CSS.supports('container-type','inline-size'));
  add('ES2022 Promise',typeof Promise==='function'&&typeof queueMicrotask==='function');
}
function runSend(tapped){
  var F=window.NOVEN_FRAME;if(!F||!F.send)return;
  sendProbe=tapped?'tapped':'programmatic';F.send('__stage_check_probe__');
}
function runCompose(){
  var F=window.NOVEN_FRAME;if(!F||!F.compose)return;
  composeProbe='probe';F.compose('__stage_check__');
}
function runQuery(){
  var F=window.NOVEN_FRAME;if(!F||!F.query)return;
  F.query('stage-check','probe','check-page');add('query 出站',true,'等待 reason:query')
}
function copyReport(){
  var t=document.getElementById('report').textContent;
  if(navigator.clipboard&&navigator.clipboard.writeText){navigator.clipboard.writeText(t);return}
  var ta=document.createElement('textarea');ta.value=t;document.body.appendChild(ta);ta.select();
  try{document.execCommand('copy')}catch(e){}document.body.removeChild(ta);
}
window.addEventListener('noven:frame',function(){onFrame(window.NOVEN_FRAME)});
// 无触摸的 programmatic send：手势窗拒绝 → 期望 dropped/gesture——
// 这本身就是受测断言（非 own 之外第二道门）。compose 在 own 舞台
// 期望 dropped/own（输入栏已隐藏，草稿框不存在）。
runStatic();runSend(false);runCompose();
if(window.NOVEN_FRAME&&window.NOVEN_FRAME.ready)window.NOVEN_FRAME.ready();
</script>
"""
