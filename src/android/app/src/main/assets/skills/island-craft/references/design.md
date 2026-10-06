# 界面岛视觉配方

SKILL.md 钉原则，这里给可抄的组件配方。所有片段假定已按骨架把 theme 写进 `--tx`（文字）、`--ac`（主题色）、`--sf`（面底色）三个 CSS 变量。宽度方向 `width:100%`，不假设具体像素。

## 通用常量

```css
:root {
  --r: 12px;                    /* 圆角：卡片 12，小件 8，胶囊 999 */
  --pad: 12px;                  /* 卡片内边距 */
  --gap: 8px;                   /* 卡片/元素间距 */
  --lbl: 11px; --num: 18px;     /* 标签/数值字号 */
}
.hairline { border: 0; border-top: .5px solid color-mix(in srgb, var(--tx) 15%, transparent); }
.label { font-size: var(--lbl); opacity: .55; letter-spacing: .04em; }
```

`color-mix(in srgb, …)` 在 Android WebView 可用，是从主题色衍生半透明色的首选；不支持时用 `rgba()` 手写。

## 配方一：状态 HUD（血量/精力/好感）

进度条是岛上最高频元件。要点：轨道用半透明同色相，填充用主题色或语义色，标签和数值分居两端。

```html
<div style="background:var(--sf);border-radius:var(--r);padding:var(--pad);display:flex;flex-direction:column;gap:10px">
  <div>
    <div style="display:flex;justify-content:space-between;align-items:baseline">
      <span class="label">血量</span><b id="hp-v" style="font-size:var(--num)">–</b>
    </div>
    <div style="height:6px;border-radius:3px;background:color-mix(in srgb,var(--tx) 12%,transparent);margin-top:4px;overflow:hidden">
      <div id="hp-bar" style="height:100%;width:0;border-radius:3px;background:var(--ac);transition:width .4s"></div>
    </div>
  </div>
</div>
<script>
  const s = (NOVEN_FRAME.state || {}).血量;
  const mx = (NOVEN_FRAME.max || {}).血量 || 100;
  if (s != null) {
    document.getElementById('hp-v').textContent = s + (NOVEN_FRAME.max?.血量 ? '/' + mx : '');
    document.getElementById('hp-bar').style.width = Math.min(100, 100 * s / mx) + '%';
  }
</script>
```

- 状态多时（血/蓝/经验）每行一条同结构，颜色区分：血用 accent 或 `#e05d5d`，法力 `#5d8de0`，警戒值才用红——日常数据别全红。
- 数值变化加 `transition` 让条滑动，比瞬移高级。

## 配方二：仿聊天应用界面（微信式）

结构三段式：顶部标题栏（半透明毛玻璃感）→ 消息流（气泡行）→ 底部输入栏（装饰性）。气泡左右分列，自己一侧靠右用主题色底。

```html
<div style="background:var(--sf);border-radius:var(--r);overflow:hidden">
  <!-- 顶栏 -->
  <div style="padding:10px 12px;text-align:center;font-weight:600;font-size:14px;border-bottom:.5px solid color-mix(in srgb,var(--tx) 12%,transparent)">林晚</div>
  <!-- 消息流 -->
  <div style="padding:10px;display:flex;flex-direction:column;gap:8px;max-height:300px;overflow-y:auto">
    <div style="display:flex;gap:8px;align-items:flex-start">
      <div style="width:28px;height:28px;border-radius:8px;background:var(--ac);color:#fff;display:flex;align-items:center;justify-content:center;font-size:12px;flex:none">林</div>
      <div style="background:color-mix(in srgb,var(--tx) 7%,transparent);border-radius:4px 12px 12px 12px;padding:8px 10px;max-width:70%;font-size:13px">对方的泡</div>
    </div>
    <div style="align-self:flex-end;background:var(--ac);color:#fff;border-radius:12px 4px 12px 12px;padding:8px 10px;max-width:70%;font-size:13px">自己的泡</div>
  </div>
  <!-- 底栏（装饰） -->
  <div style="padding:8px 12px;border-top:.5px solid color-mix(in srgb,var(--tx) 12%,transparent);display:flex;gap:8px;align-items:center">
    <div style="flex:1;background:color-mix(in srgb,var(--tx) 7%,transparent);border-radius:14px;padding:6px 10px;opacity:.6;font-size:12px">说点什么…</div>
  </div>
</div>
```

要点：头像用首字色块（主题色底白字），气泡 `max-width:70%`，自己一侧 `border-radius` 的不对称角要朝尾。

## 配方三：档案卡/角色页

顶部身份区（大名字+一行小标签）→ 分区段（小标题+内容）。分区用标签行+hairline，不用大色块标题。

```html
<div style="background:var(--sf);border-radius:var(--r);padding:14px 12px">
  <div style="display:flex;gap:10px;align-items:center">
    <div style="width:44px;height:44px;border-radius:12px;background:linear-gradient(135deg,var(--ac),color-mix(in srgb,var(--ac) 60%,#7a5de0))"></div>
    <div>
      <div style="font-weight:600;font-size:15px">角色名</div>
      <div class="label">流浪医师 · 北境</div>
    </div>
  </div>
  <div class="hairline" style="margin:12px 0"></div>
  <div class="label" style="margin-bottom:6px">特质</div>
  <div style="display:flex;gap:6px;flex-wrap:wrap">
    <span style="font-size:11px;padding:3px 8px;border-radius:999px;background:color-mix(in srgb,var(--ac) 15%,transparent);color:var(--ac)">沉默寡言</span>
  </div>
</div>
```

标签 chip = 主题色 15% 底 + 主题色字 + 胶囊圆角，是最百搭的强调元件。

## 配方四：仪表盘/统计

指标瓷砖横排（`display:grid;grid-template-columns:repeat(auto-fit,minmax(90px,1fr))`），每砖一标签一数值；趋势图用内联 SVG 折线——SVG 是岛内唯一的"画图"手段，别用字符画。

```html
<svg viewBox="0 0 200 40" style="width:100%;height:40px">
  <polyline points="0,32 40,24 80,28 120,14 160,18 200,8" fill="none" stroke="var(--ac)" stroke-width="2" stroke-linejoin="round"/>
</svg>
```

## 配方五：选项/列表页

整行可点卡片：`display:flex` 左图标区右文字区（标题+一行小描述），hover/active 给 `background` 变化；竖排 gap 8px。别用 `<button>` 默认样式——按钮都要自定义（胶囊或卡片）。

## 交互模式

- **Tab 切换**：顶部一排小胶囊按钮 + 一个内容容器，JS 换 `display` 和激活态底色。
- **翻页**：内容容器 + 底部 `‹ 4/9 ›` 行，JS 改页码重渲染数据切片。
- **点击反馈**：`active` 态 `transform:scale(.97)` 或透明度微降，比变色自然。
- 所有可点元素 `cursor:pointer` + 最小 28×28px 命中区。

## 深色适配自查

写完问自己：文字色是不是 `--tx`？面底色是不是 `--sf`？强调色是不是 `--ac`？有这三个问题答"是"，深色浅色自动都对。写死 `#fff`/`#000`/`#333` 的地方就是会在反主题下翻车的地方。
