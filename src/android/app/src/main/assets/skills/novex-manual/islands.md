# 界面岛（```html 交互界面）

回复里写 ` ```html ` 围栏 → 渲染成气泡内的可交互界面（沙箱 WebView），不是代码文本。适合状态面板、角色档案页、仪表盘、小游戏。

## 一个完整的岛长什么样

````markdown
```html
<style>
  .box { box-sizing: border-box; margin: 0; font: 13px/1.5 system-ui, sans-serif;
         color: var(--tx); }
  :root { --tx: #1b1d21; --ac: #4caf8d; --sf: #ffffff; }
</style>
<div class="box" style="background:var(--sf);border-radius:12px;padding:12px">
  <div id="hp"></div>
</div>
<script>
  const F = window.NOVEN_FRAME || {};
  const T = F.theme || {};
  document.documentElement.style.setProperty('--tx', T.text || '#1b1d21');
  document.documentElement.style.setProperty('--ac', T.accent || '#4caf8d');
  document.documentElement.style.setProperty('--sf', T.surface || (T.dark ? '#17191d' : '#ffffff'));
  const draw = () => {
    const s = F.state || {}, hp = s.hp, max = (F.max || {}).hp || 100;
    document.getElementById('hp').textContent =
      hp == null ? '暂无数据' : '生命 ' + hp + ' / ' + max;
  };
  draw();
  window.addEventListener('noven:frame', draw);
</script>
```
````

写任何岛都从这个骨架出发：CSS 变量从 `theme` 回写（自动跟随应用深浅色）、数据从 `state` 读、`state` 里没有的键显示"暂无数据"而不是编数字。

## 写法规则

- 写片段即可：不带 `<!DOCTYPE>/<html>`，可含 `<style>`/`<script>`。结构、样式、脚本必须全部收在同一块内，块内不得出现 ` ``` ` 开头的行。
- 移动端画布约 320–380px，按单列布局设计；禁 `position:fixed`/`100vh`/`100vw`（高度随内容自适应）。

## 数据桥 window.NOVEN_FRAME

脚本执行前就绪，字段如下：

- `card`：当前挂载的卡 `{name: 卡名, kind: 卡类型}`；`kind` 是小写类型（如 `"world"`/`"character"`），未挂卡时整个 `card` 可能缺省。
- `state`：本局状态变量的当前值，扁平键值表（值是文本、数值或开关）。
- `max`：数值型状态的上限表——只有声明过上限的键才出现（如 `{hp: 100}`），画进度条/血条时做分母。
- `theme`：`{dark, accent, text, surface}` 配色，跟随应用深浅色；岛内取色一律从 `theme` 来，不要写死颜色。

实时刷新：`update_playthrough_state` 改动状态后会派发 `noven:frame` 事件，`addEventListener('noven:frame', 重绘)` 让常驻面板自动跟上（见上面例子的 `draw`）。

视觉与组件配方读 `island-craft` 技能。

## 沙箱红线

无网络（CDN/外链图/fetch 全失败）、无 localStorage、无弹窗。**图片例外**：本会话图片文件可进岛当素材（用户上传图、生图、工作区文件），`src` 写 `/var/minis/attachments/...`、`minis://attachments/...` 或 `novex-res://attachments/...` 等价（仅图片扩展名，路径禁 `..`）；其余资源仍须 `data:` 内联或内联 SVG/CSS 渐变。

## 出问题怎么办

岛渲染失败会显示"界面未渲染：原因 · 点右上角 </> 看源码"——让用户把源码发回来排查，不要假装界面已出。排错细节见 `troubleshooting.md`。
