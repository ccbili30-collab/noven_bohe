# 设计案：对话内原生组件 kind:"components"（2026-10-13）

> **给接手模型**：本文是设计案，不是施工令。开工要用户明确放行；每个 PR、任何发版另行批准。文中"已定"项来自用户逐条拍板（对话存档 `history_d845edd1aa764f25` + 2026-10-13 续谈），"待拍板"项不许自决。
>
> 前史：源于一次 GPT"组件库"提案的取舍讨论——否决其"模型生成可执行界面"的路线，定为**模型写、创作者改、PC/手机同构的界面数据格式**。组件只是这种格式的词汇。

## 0. 定位与边界

- **需求分两档**：A 档 = 对话内件（图片 + 稍复杂组件，小需求）；B 档 = 全屏前端作品（舞台协议 v0.3，**已落地**）。本文只覆盖 A 档。
- **html 岛降级为 P3**：面板类界面（血条/状态卡/物品列表）一律走 components；html 只许小游戏、画布、自由版式。本设计的目的就是消灭"面板走 WebView"整类病（黑屏/重载抽动/写死色翻车）。
- **不是新协议**：`kind:"components"` 进现有 `novex-def`/`novex-use` 注册表，持久存储、分支继承、卡内预置三件套原样复用。

## 1. 格式骨架

```jsonc
```novex-def
{"id":"hud","kind":"components","name":"状态面板"}
{ "type":"panel", "title":"状态", "children":[
  {"type":"meter","label":"体力","bind":"体力"},
  {"type":"stat","label":"地点","bind":"当前地点"},
  {"type":"list","label":"背包","bind":"背包"} ]}
```
```novex-use
{"id":"hud","data":{}}   // bind 件通常不需要 data；临时数据仍可走 data/{{key}}
```

- `bind` 直指本局状态键；模型只写 `update_playthrough_state`，面板自动随 `noven:frame` 刷新——**界面数值永远等于真实状态**。
- `use` 的 `data`/`{{key}}` 保留，只用于临时信息（某场战斗的敌人卡）。

## 2. 组件目录（共 17 个）

### 基础件 ×10（模型可 def）

| 类别 | 组件 | 核心 props |
|---|---|---|
| 结构 | `panel` | `title`, `children` |
| 结构 | `row` / `grid` | `children`, `grid` 加 `cols`（闭枚举 2/3/4） |
| 数值 | `meter` | `label`, `bind`（有上限自动出分母） |
| 数值 | `stat` | `label`, `bind` 或 `value`, `icon`(Phosphor 名) |
| 徽标 | `tag` | `text`, `tone` |
| 集合 | `list` | `bind`（列表键，项数=行数）, `label` |
| 集合 | `kv` | `bind` 或显式 `pairs` |
| 互动 | `action` | `label`, `ref`（只许引用已注册快捷操作/选项） |
| 图 | `image` | `src`, `size`, `aspect`, `round` —— 见 §4 |

### 预置件 ×4（模型只许 `use`，不许 `def`）

| id | 用途 | 槽位 |
|---|---|---|
| `builtin.character` | 角色卡 | `name`, `portrait`(image src), `bind`(数值键), `tags[]` |
| `builtin.status` | 状态块 | `bind` 一组键；有上限→meter，纯值→stat，自动 |
| `builtin.items` | 物品栏 | `bind` 列表键；图标+名+数量 |
| `builtin.figure` | 图文卡 | `src`, `caption`, 可选 `bind` |

预置件**用自家 manifest 写**（基础件组合），顺便当格式自验证。

### 降级件 ×3（系统内部，不开放）

未知件→灰色占位"未知件：xxx"；绑定键缺失→"—"；整份描述解析失败→诊断卡（可展开原文）。**永不黑屏、空白、整块消失**——写进渲染器契约。

## 3. 三条铁律

1. **按钮只说话不改状态**：`action` 只能引用快捷操作（发一句话/填输入框），状态变化必经"模型+规则"权威通道。
2. **语义色调不写色**：组件只许 `tone: neutral|muted|positive|warning|danger`，由 ChatPalette 映射具体色；薄荷绿锁给可点击元素。组件**没有颜色属性**——写死错色的可能从根上消失。
3. **图标走 Phosphor 白名单**：`icon` 字段只许已收录的 Phosphor 名（当前收录 38 个，按需扩充；SVG→VectorDrawable 转换脚本化）。未知名→降级占位。**符号级图形不进 image**——icon 通道渲，永远主题色固定字号。

## 4. image 三档尺级（稳定优先）

| 场景 | 写法 | 规则 |
|---|---|---|
| 符号级 | 各组件 `icon` 字段（Phosphor 名） | 不存在缩放问题 |
| 头像/logo 级 | `"size":"sm"` | 固定 48×48 方形居中裁切，`"round":true` 出圆头像 |
| 插图/封面级 | `"size":"md"` / 默认 `"full"` | md=固定 96px 高按 aspect 出宽；full=容器宽×aspect |

- `aspect` 闭枚举 `1:1 / 4:3 / 16:9 / 3:4`，默认 `4:3`；`size` 闭枚举 `sm/md/full`，默认 `full`。
- `src` 只认两种写法：卡资源 id（→`novex-asset://`）/ `attachments` 路径（→`minis://`），与岛同一套来源规则；http 外链进不来。
- **四个 props 全闭枚举，模型零自由参数**——布局高度永远可预测，图未加载时占位同尺寸不闪跳。

## 5. each 不单独造——集合组件自带重复

`list`/`kv` 绑列表键时自动按项数展开（背包 3 件画 3 行、5 件画 5 行、空显"空"）。不引入通用 each 容器——多一层嵌套 = 模型多一处写错。真碰到"列表画成卡片网格"再升级。

## 6. 三级组件库

| 级 | 谁造 | 存哪 | 范围 |
|---|---|---|---|
| `builtin.*` | 宿主预置 | App | 所有会话 |
| **个人库**（新增） | AI 写→用户收藏 | 用户级注册表 | 该用户所有会话 |
| 卡内件 | AI 写/作者做 | 卡模块（`novex-frontend` 标签，现有通道） | 挂该卡的会话 |

个人库是新增面：注册表加用户级命名空间 + "收藏进个人库"入口。同 id 覆盖沿用现有规则。

## 7. 校验（novex.core 纯 Kotlin）

嵌套 ≤6 层、节点 ≤80、文本限长；不认识的字段忽略不报错；`icon`/`tone`/`size`/`aspect`/`action.ref` 全白名单校验，越界→降级件。

## 8. 双端同构（本期纳入，不再推迟）

- manifest 设计**禁用平台特有属性**（无 dp/px、无 Compose-only 概念）——为 PC 铺路。
- `noven-ui.js`：同一 manifest 的 Web 渲染器，PC 工坊预演器直接画出与手机一致的面板。原则沿用"电脑向手机妥协，手机向电脑看齐"。

## 9. 模型教学

- 说明书新章 `components.md`（上线后才写，遵守"只教实际存在的能力"）。
- 每轮注册表摘要标 kind + 来源（builtin/个人库/卡/本局）+ **当前状态键清单**（模型能绑什么看得见）。
- 选择链（写死）：全屏作品开启→模型只写内容不出界面；未开启→P0 已注册件 use → P1 builtin use → P2 新建 components def → P3 新建 html def → 兜底只写正文。

## 10. 实施分期

| 期 | 内容 |
|---|---|
| C1 | novex.core 解析器+校验器（纯 Kotlin 可测）+ 注册表收 `components` |
| C2 | Compose 渲染器（10 基础件 + 3 降级件 + bind 刷新） |
| C3 | 4 个 builtin 件 + 个人库收藏通道 |
| C4 | 摘要注入 kind/状态键清单 + 说明书章节 + 斗巢状态面板改写验收 |
| C5 | `noven-ui.js`（PC 同构渲染器） |

## 11. 遗留待拍板

- `builtin.*` 各槽位的最终字段名（上面是建议值）
- 个人库的 UI 入口放哪（件上长按收藏？会话设置？）
- `each` 未来升级的触发条件（暂不实现）

## 12. 已否决项（防回潮）

- ❌ GPT 式"模型生成可执行代码界面"——组件是数据不是代码
- ❌ pinned 顶部常驻状态条——件属于叙事流（用户已否）
- ❌ 模型每轮重写 manifest——def 一次、use 填值，builtin/个人库兜底
- ❌ 通用 `each` 容器
- ❌ image 自由尺寸/外部图
