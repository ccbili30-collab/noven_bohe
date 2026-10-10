# 皮肤包（```novex-skin）

整套会话外观 = 一个 JSON（规范 v1）。**Android 端皮肤只由卡片模块播种或电脑端设定——消息里写 ` ```novex-skin ` 围栏不会换装**，用户要求改皮肤时如实告知「皮肤在电脑端编辑/随卡下发」。皮肤只换视觉，消息/工具/分支等交互不动。**好皮肤不需要图**——气泡的填充/描边/圆角/阴影都是原生绘制，图片只做"纹理"和"小角饰"两类素材。

````markdown
```novex-skin
{
  "spec": 1,
  "name": "墨玉羊皮",
  "base": "minis://shared/skins/demo/",
  "bubbleAssistant": {
    "fill": "#E9DDBB",
    "texture": {"img": "./paper-tile.png", "tile": true, "alpha": 0.4},
    "border": {"color": "#A9853F", "width": 1, "radius": 12},
    "shadow": {"color": "#66000000", "blur": 4, "dy": 1.5},
    "padding": [15, 11, 15, 11],
    "maxWidth": 330,
    "contentScheme": "light",
    "corners": {"img": "./corner.png", "size": 16, "inset": 3}
  },
  "bubbleUser": {
    "fill": "#402E20",
    "border": {"color": "#9C7B45", "width": 1, "radius": 16},
    "padding": [15, 10, 15, 10],
    "contentScheme": "dark"
  },
  "avatarFrame": {"color": "#C9A96A", "width": 1.5},
  "topBar": {"fill": "#1A1408", "hairline": {"color": "#8A6F3F", "edge": "bottom"}},
  "composer": {"fill": "#1A1408", "hairline": {"color": "#8A6F3F", "edge": "top"}},
  "input": {"bg": "#241B12", "border": "#7A6036", "text": "#EFE3C8"},
  "topSubtitle": "AI 助手 · 永远在你身边",
  "background": {"img": "./bg-dark.png", "scrim": "#5C000000"},
  "accent": "#C9A96A",
  "overlay": "<div>…整页 HTML…</div>"
}
```
````

## 气泡令牌（声明即覆盖，未声明用默认观感）

| 键 | 含义 |
|---|---|
| `fill` | 填充色 `#RRGGBB`/`#AARRGGBB` |
| `texture` | 贴图：`img` 图 + `tile`（无缝平铺）+ `alpha` 叠印透明度 |
| `border` | `color` + `width`(dp) + `radius`(dp)——圆角描边，任何尺寸都锐利 |
| `shadow` | `color` + `blur`(dp) + `dx`/`dy`(dp) 外阴影 |
| `padding` | `[左,上,右,下]` dp 内容内边距 |
| `maxWidth` | 气泡最大宽 dp |
| `contentScheme` | `"light"`=亮底深字 / `"dark"`=暗底浅字——气泡内整套内容色 |
| `corners` | 四角小饰件：`img` 透明 PNG + `size`(dp) + `inset`(dp)；镜像贴四角不拉伸，气泡太小自动省略 |
| `text` | 显式文字色（优先于 contentScheme 推导） |
| `slice` | 高级位图模式：`{"img","scale","stretchX":[[l,r]],"stretchY":[[t,b]],"content":[l,t,r,b],"min":[w,h]}`，多段拉伸；声明后替代令牌绘制 |
| `variants` | 状态/分组变体：`"queued"`/`"streaming"`/`"error"`/`"groupFirst"`/`"groupMid"`/`"groupLast"`，每个是覆盖基础令牌的部分声明 |

## 其余槽位

| 键 | 结构 |
|---|---|
| `avatarFrame` | `{"color","width"}` 原生圆环 + 可选 `"img"` 方形饰件图叠在头像上 |
| `topBar` / `composer` | `{"fill","texture","hairline":{"color","width","edge":"top\|bottom"}}`——暗底+边缘细线是经典打法 |
| `input` | `{"bg","border","text"}` 输入框底/描边/字色 |
| `topSubtitle` | 皮肤顶栏副标（有顶栏皮肤时顶栏切"头像+名+副标"排布） |
| `background` | `{"img","scrim"}`——scrim 是暗化罩层色，保氛围压文字对比 |
| `accent` | 全局强调色（链接/发送键/思考态） |
| `overlay` | 整页皮肤 HTML，见下 |

## 素材路径

绝对写法：`/var/minis/…`、`minis://…`、`novex-res://…`（岛资源同套解析）。相对写法 `./x.png` 挂到 `base` 目录下——皮肤进卡/演示包用相对路径可整体搬目录。**素材只用两类：无缝平铺纹理 + 小角饰**；不要让生成模型画整个气泡框（拉伸必糊）。纹理/饰件可以让用户上传或自己生图，但不要编不存在的文件名。

**内置演示包**：`minis://shared/skins/demo/` 预置「墨玉羊皮」（`paper-tile.png`/`corner.png`/`bg-dark.png`，JSON 在同目录 `skin.json`；`skin-overlay.json` 是同款皮肤的整页弹幕演示）。皮肤通过卡片 `novex-skin` 模块下发；演示 JSON 供电脑端编辑器与卡作者参考。

## 整页皮肤（overlay HTML）

`overlay` 非空 → 转录区整盖成一张满幅界面岛，原生消息流仍在底下（右下角「消息」浮钮随时切回，编辑/分支/复制不受影响）。岛经 `window.NOVEN_FRAME` 拿数据，比气泡岛多一个 `messages`：最近 30 条 `[{role,name,text,streaming}]`，新消息经 `noven:frame` 事件推进来。弹幕、书信、终端、报纸等"换骨"形态全走这条路——overlay 只做呈现，不改消息本体。overlay 里禁 `position:fixed`/`100vh` 的约束在满幅模式下取消（它就是一屏），其余沙箱红线不变。

## 数学渲染前端（math kind 复习）

皮肤只管"皮"，状态条/仪表盘这类数据件走前端件（见 `frontend-widgets.md`）：`kind:"math"` 把 LaTeX 模板注册一次，之后每轮 `novex-use` 只发数值——`\color{#C9A96A}\rule{ {{hp}} pt}{8pt}` 这种结构配数据就能画血条，模型不用重写公式。

## 皮肤进卡

模块打 `novex-skin` 标签、正文写皮肤 JSON（可带/不带围栏）→ 会话无皮肤时绑卡自动播种，随卡导出分享。皮肤图建议先存进会话产物目录再引用，卡的接收方才解析得到。
