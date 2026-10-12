# 持久前端件（```novex-def / ```novex-use）

需要**每轮更新**的界面（状态栏、血条、战报板、仪表盘）不要每轮重写整段 html——注册一次模板，之后每轮只发数据：界面不因重写而抖动或出错，每轮的消息也更短。

## 注册（只出现一次）

````markdown
```novex-def
{"id":"hp","kind":"math","name":"血量条"}
\frac{ {{hp}} }{ {{max}} }
```
````

- 首行 JSON 元信息：`id`（必填）、`kind`（`html`/`math`/`table`，缺省 html）、`name`、`display`（`"fullscreen"` 声明整页舞台，见下节；除 `id` 外均可省）。
- 其余行 = 模板，`{{键名}}` 占位符在渲染时被当轮数据替换；数据中不存在的键原样保留。
- kind 落点：`math`→KaTeX 数学渲染（极简状态条/公式牌）；`table`→内置表格渲染（模板写 Markdown 表格，也可含标题段落）；`html`→界面岛。

## 调用（每轮只发这个）

````markdown
```novex-use
{"id":"hp","data":{"hp":42,"max":100}}
```
````

`data` 可写成同级散键（`{"id":"hp","hp":42}`）。

## 位置

件渲染在 use 块写下的那个位置——像普通文字一样随叙事流排版。想让件出现在别处，就在下一轮的新位置再发一次 use（每次都是就地新画的投影，不是移动旧组件）。没有"钉在屏幕顶部/底部"这回事——前端件属于叙事流。

## 全屏舞台件（display:"fullscreen"）

地图、沙盘这类整页界面，在 def 元信息里声明 `"display":"fullscreen"`：**首次** use 在消息流里渲染真岛（点击放大成整页舞台），之后每轮的 use 只渲染一张轻量入口卡，整页界面不再每轮内联重复。舞台始终用最新一轮数据投影，点入口卡随时回到舞台。适合"第一轮画一张大地图、之后每轮只更新数据"的场景。

## 注册表是持久的（4.0.6 起）

`novex-def` 一经回复落库就进**会话注册表**（按消息分支存储），不再只依赖消息正文在场：重试或编辑分叉之后，分叉点之前注册的件仍属新分支（分叉那刻界面已存在），你也会在每轮的「已注册前端件」提示里看到当前可用的 id 清单——**优先引用已注册的 id，不要重新 def**。想改模板就用**同 id** 再发一次 def 覆盖；只有想推翻重做才换新 id。

## 卡内预置

带 `novex-frontend` 标签的卡片模块，其正文的 `novex-def` 在开局自动注册（开局资料会标注"前端"）。直接用 id 调 `novex-use` 即可，不要重复 def。同 id 的 def 后者覆盖前者——剧情中可以现场改版。

## 把前端件封进卡

建模块 → 正文写 `novex-def` 块 → 打 `novex-frontend` 标签 → 随卡导出分享，别人装卡即用。

## 常见边界

`novex-use` 的 id 未注册 → 界面不显示；先用 `novex-def` 注册或确认卡内预置的 id。数学渲染的是 KaTeX 子集；不支持的宏会显示为原文，换通用写法。

旧卡里可能见到 `novex-skin` 围栏（气泡/顶栏皮肤 JSON）——该机制已退役，新作品一律走 `novex-def`/`display:"fullscreen"` 舞台件；旧皮肤围栏只会显示一行下线提示，不再换装。

## 舞台协议（全屏作品，v0.3）

`display:"fullscreen"` 的件被点开后进入**舞台**：整页 WebView 接管消息区，宿主每轮推一个 `NOVEN_FRAME` 数据帧，页内监听 `noven:frame` 事件拿新帧。

### 帧字段

| 字段 | 内容 |
|---|---|
| `card` / `session` | 卡与局身份：`card.{id,name,kind}`，`session.id` |
| `env` | 环境：`bridgeVersion`（协议版本，当前 `2`）、`webview`（内核大版本号）、`ime.visible`/`height`（键盘） |
| `state` / `max` | 本局状态值与上限（register_controls 那套） |
| `theme` | 明暗与主题色板 |
| `transcript` | 结构化叙事流：`[{id, messageId, segment, turnId, role, kind, speaker, text, html, src, streaming}]`。**作品直接用解析好的段，不要再自己解析正文或协议围栏** |
| `cast` | 名册 `[{id,name,avatar,color}]`——avatar 是资源 id，经 `asset()` 取地址 |
| `choices` | 选项数据 `{turnId, items:[{id,label}]}`——模型给的选项以数据形式下发，作品自己画 |
| `deltas` | 档案增量流 `[{seq,messageId,envelope}]`：按 seq 顺序重放物化；envelope 自带操作动词（`replace`/`delta`/`insert`/`remove`/`move`），宿主不猜语义 |
| `archive` | 物化快照（可丢弃缓存）；当前恒 `{}`，以 `deltas` 重放为准 |
| `assets` | 资源 id → 平台地址映射；页内取地址一律 `NOVEN_FRAME.asset(id)` |
| `reason` | 本帧来由：`init`/`turn`/`query`/`branch`/`ime`/`send`。`branch` = 路径换了（重试/切分支），丢弃本地物化按新 `deltas` 重放 |
| `turn` | 回合状态：`streaming`、`queued` |
| `sendResult` | send 回执 `{result,why}`：`accepted`/`queued`/`dropped`；dropped 的 why：`rate`/`gesture`/`not-own`/`length` |
| `messages` | 旧投影，已弃用——新作品用 `transcript` |

### 出站（作品 → 宿主）

- `NOVEN_FRAME.send(text)`：发一条玩家输入。**只认真实触摸**：脚本定时/自动发会被门控丢回 `dropped/gesture`；一次触摸只放行一发；最短间隔 1 秒；最长 8000 字。
- `NOVEN_FRAME.compose(text)`：往原生草稿框填字（own 输入模式下已隐藏，不要用）。
- `NOVEN_FRAME.query(domain, entity)`：静默查证——宿主让同模型换查证角色后台答一轮，结果作为附属记录写进 `deltas`，以 `reason:"query"` 推帧回来；不回话进消息流。
- `NOVEN_FRAME.asset(id)`：资源 id 换成可用地址。
- `NOVEN_FRAME.ready()`：首屏画完必须调一次——8 秒没 ready 宿主判作品卡死，自动退回原生对话。

### 输入层

def 元信息 `"input":"own"` 声明作品自带输入层：宿主隐藏原生输入栏、`send` 放行门控内发送。不声明的作品走原生输入栏，`send` 一律 `dropped/not-own`。

### 自检

聊天菜单 →「舞台自检」打开内置体检页：逐项核验帧字段、helper、出站、内核基线，产出 JSON 报告（可复制）。同一张页面在电脑模拟器和真机各跑一遍逐项对账，不一致即缺陷。
