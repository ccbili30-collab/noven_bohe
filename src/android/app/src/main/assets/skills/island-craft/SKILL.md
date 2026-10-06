---
name: island-craft
description: Novex 界面岛与聊天排版创作 Skill。要写 ```html 界面岛（状态面板、角色档案页、仿微信/手机应用界面、仪表盘、小部件、可交互前端页面），或在回复里排版数学公式、表格、图文混排时先读本技能。覆盖：界面岛沙箱契约与 NOVEN_FRAME 数据桥、移动端宽度下的视觉语法（配色、字号、间距、圆角、组件配方）、聊天正文可用的 Markdown/KaTeX 格式清单、以及常见翻车点（浏览器默认脸、写死像素、主题色不跟随、把 CSS/JS 写出块外）。用户问"怎么做出好看的界面/页面"时也可直接引用本技能教用户。
version: 1.0.0
---

# 界面岛创作 1.0.0

写给消息流里的 ```html 界面岛。岛是一块嵌在聊天气泡里的活界面：沙箱 WebView、可交互、无网络。写任何界面前先读本文件；组件细节查 references/。

## 动笔前三件事

1. **画布宽 ≈ 320–380px**。岛长在手机聊天气泡里，不是桌面浏览器。按单列移动布局设计，别写多栏。
2. **先读 NOVEN_FRAME**。`window.NOVEN_FRAME` 在脚本执行前已就绪：`card`（当前卡名/类型，可能缺省）、`state`（本局变量）、`max`（变量上限）、`theme`（`dark`/`accent`/`text`/`surface`）。配色必须吃 theme，数据必须读 state——state 空就渲染"暂无数据"，不编数字。
3. **一块收齐**。一个岛 = 一个 ```html 块，结构+样式+脚本全在里面，块内不出现 ``` 开头的行。块外补写的 CSS/JS 会裸露成聊天文本。

## 最低可用骨架

```html
<style>
  .x { box-sizing: border-box; margin: 0; font: 13px/1.5 system-ui, sans-serif;
       color: var(--tx); }
  :root { --tx: #1b1d21; --ac: #4caf8d; --sf: #ffffff; }
</style>
<div class="x" style="background:var(--sf);border-radius:12px;padding:12px">
  <!-- 内容 -->
</div>
<script>
  const F = window.NOVEN_FRAME || {};
  const T = F.theme || {};
  document.documentElement.style.setProperty('--tx', T.text || '#1b1d21');
  document.documentElement.style.setProperty('--ac', T.accent || '#4caf8d');
  document.documentElement.style.setProperty('--sf', T.surface || (T.dark ? '#17191d' : '#ffffff'));
</script>
```

骨架的要点：CSS 变量从 theme 回写 → 深浅色自动跟随应用；字号 12–13px 起步（聊天气泡里 16px 已经显大）；圆角 10–14px；内边距 12px。

## 美观的四个杠杆

细节配方在 references/design.md，这里只钉死原则：

- **层级靠透明度，不靠颜色堆**。次级文字 `opacity:.55`，分割线 `1px solid` 半透明色，悬浮层 `rgba()` 半透明面——同一色相里做深浅，比引入第二个颜色高级。
- **数字和标签分开**。指标类界面：标签小字（11px、低透明、可加 letter-spacing），数值大字（17–20px、半粗）。一行标签一行值，别混排。
- **对齐只有一种**。同一界面内所有元素对齐同一根网格线（4px 步进）；圆角、间距、内边距同一组常量。东一个 8px 西一个 13px 是"散装感"的第一来源。
- **留空是内容**。卡片之间 8–12px 间距、卡片内 12px 内边距是底线；塞满的界面=文字表换皮。

## 实时刷新（有剧情变量时）

```js
window.addEventListener('noven:frame', () => { /* 重读 NOVEN_FRAME.state 并重绘 */ });
```

变量值不写死在 HTML 里——剧情推进后岛会自己跟上。没有实时数据可读的纯展示界面不挂监听。

## 沙箱红线（写了也不生效，别浪费用户的屏）

无网络（CDN/外链图/网络字体/fetch 全部失败）、无本地文件、无存储、无表单提交、无弹窗（alert 被吞）。图片只能 data: 内联——大图片改用内联 SVG 或 CSS 渐变画。`localStorage`/`cookie` 不可用，状态放 JS 变量或 NOVEN_FRAME。

## 持久前端件（建一次，之后每轮只喂数据）

需要**每一轮都更新**的界面（血量条、状态面板、战报板）不要每轮重写 HTML——注册一次，之后只发数据。

**注册**（只出现一次）：

````markdown
```novex-def
{"id":"hp","kind":"html","name":"血量条","slot":"bottom"}
<style>…</style><div>HP：{{hp}} / {{max}}</div>
```
````

- 首行 JSON 元信息：`id`（必填）、`kind`：`html`/`math`/`table`、`name`（展示名，可省）、`slot`（默认位置，可省）。
- 余下为模板，`{{键名}}` 是占位符，渲染时被当轮数据替换。
- `math` 的模板写 LaTeX（走数学渲染，适合极简状态条/公式牌）；`table` 的模板写 Markdown 表格（走内置表格渲染）；`html` 走界面岛。

**调用**（每轮只发这个，短小便宜）：

````markdown
```novex-use
{"id":"hp","slot":"bottom","data":{"hp":42,"max":100}}
```
````

`data` 也可写成散键（`{"id":"hp","slot":"bottom","hp":42}`），效果相同。

**槽位**：`top` 钉在会话顶部、`bottom` 钉在输入框上方、`inline`（缺省）画在这条消息里。**每条 use 自己带 slot，每件以最近一次 use 的位置为准**——要挪位置就在下一轮 use 里换个 slot 写。

**约束**：

- `{{key}}` 只替换本轮数据里存在的键，模板里别写和数据键对不上的占位符。
- html 件的模板照样受上面的沙箱红线约束。
- 注册/调用块之外照常写叙事正文——同一轮里"底槽血条 + 中间叙事 + inline 战报"就是三个块的事。

## 把前端件装进卡（做卡时）

调好的界面可以作为卡的一部分封进去，玩家开局即用：

- 建一个模块，正文写 `novex-def` 围栏（可以一个模块装多件，也可以一件一模块）。
- 给该模块打上 **`novex-frontend`** 标签（模块"携带与标签"里加一行即可）——会话开始时会自动把这些定义预铺进注册表。
- 路由建议保持"默认"（进开局资料包），模型在第一轮就能看到全部模板和 id；带 `name`/`slot` 元信息能让默认位置开箱就位。
- 前端件随卡导出/分享：导出的卡里界面皮肤、布局、占位符约定都是作者的版本，这就是作品资产的一部分。
- 对话中同名 id 的 `novex-def` 会覆盖卡内定义（模型可在剧情里现场改版某件界面）。

## 按任务读取

- 写**具体组件**（状态栏、聊天界面、档案卡、仪表盘、时间线、进度条）→ `references/design.md`：每种组件的结构和 CSS 配方。
- 确认**聊天正文还能排什么**（Markdown 能力清单、KaTeX 数学语法、表格、媒体）→ `references/canvas.md`。
- 要一份**完整可抄的成品**（仿真手机：聊天流+实时状态页+信件+底栏 tab）→ `references/example-phone.md`，结构照抄、内容换剧情的。
- 用户问"你能做出什么样的界面/页面"时，用本技能的能力清单回答，并把主题配色、数据联动讲给用户听。

## 反模式（看到就停手）

- `position:fixed`/`100vh`/`100vw`——岛的高度随内容自适应，视口概念不存在。
- 白底黑字的浏览器默认脸——没声明背景色就透明底露出气泡底色，大概率违和。
- `<input>`/`<form>` 收集用户输入——岛无存储无提交，做了也是死的；要交互用岛上按钮点切视图。
- 用 emoji 当图标堆界面——用 CSS 几何形、内联 SVG 或纯排版。
- 给岛配文字标题（"XX预览""XX面板"）——界面自己说明自己，正文前后不加招牌。
