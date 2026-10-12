# 任务：舞台协议 v0.3 手机侧施工（2026-10-12）

> **给接手模型**：本文是完整交接。先通读「0. 必读」与「1. 为什么」，再按「4. 施工步骤」顺序做。每一步都写了**做什么 / 为什么 / 落点 / 验收**。遇到本文没覆盖的产品取舍，停下来问用户，不要自己拍。
>
> 协议依据（冻结，均在 `Novex-Development/local/design/shaosong-ui/`）：
> - `STUDIO-ALIGNMENT-v0.3.md`（r2，手机侧发起，**主依据**）
> - `STUDIO-ALIGNMENT-PC-v0.3.md`（PC 回执，冻结声明 + 4 处补充 + PC 施工顺序）
> - `STUDIO-ALIGNMENT-ANDROID.md` / `STUDIO-ALIGNMENT-PC.md` / `STUDIO-ALIGNMENT.md`（v0.2 及原始绍宋文档，背景）
>
> 设计本意（给用户与 AI 的版本）：`src/android/app/src/main/assets/skills/novex-manual/design-intent.md`「前端作品（全屏）」「电脑工坊与手机戏台」两节。

---

## 0. 必读（开工前）

### 0.1 授权边界

- 本文只是规划。**开工需要用户明确说"开工"**；每个 PR 合并、任何发版都要用户另行批准（`.agents/skills/code-review/SKILL.md` 铁律 2）。
- 4.0.7 正在收尾（`0225d504 chore(android): 4.0.7 版本号 + 发布说明`）。**本任务排在 4.0.7 发布之后**，从当时的主线开新分支 `tasks/stage-protocol-v0.3`，不要混进 4.0.7。
- 开工前 `git status`：用户经常在本地直接改代码（近期改过 `ChatScreen.kt` / `ChatTranscriptOverlay.kt` / `HtmlIsland.kt`）。**不要覆盖、回退或顺手"整理"用户未提交的改动**；有冲突先问。

### 0.2 环境与流程坑（前任踩过的）

| 坑 | 对策 |
|---|---|
| 本机**没有 Java 运行时**，`./gradlew` 跑不了 | 本地只跑 `scripts/tests/` 三项契约测试（纯 shell/python）；Kotlin 编译与单测走 CI（`android-validate`，推分支或 PR 触发） |
| IDE 缓冲区的编辑**可能没落盘** | 每次改完用 `git diff` 核对磁盘真实内容 |
| git HTTPS 推送间歇 TLS 故障（`tlsv1 alert protocol version` / `record overflow`） | 重试；仍失败可用 `gh api` 建 blob/tree/commit 推送（前任做过），远端 tree 与本地一致即可 |
| 代码注释风格：中文、带任务标签（如 `[T-xxx]`）、解释"为什么" | 新代码沿用；**不删已有注释** |
| 说明书全局规则："只教实际存在的能力" | 功能没上线前，**不改** `frontend-widgets.md` / `islands.md` / `skins.md` 的用法说明（否则模型会调用不存在的接口）；上线后再改（步骤 S10） |

### 0.3 审查等级

本任务是**重**级（新功能、跨层、UI 系统）：按 `.agents/skills/code-review/SKILL.md` 第 5 节走 A 源码对比 + B 整体链路 + C 修复后复查；UI 行为在用户真机验收前，汇报里一律标"未视觉验证"。

---

## 1. 为什么要做（背景与目的）

### 1.1 产品目标

诺文的核心是"AI 原生创作社区，AI 是笔，握笔的永远是人"。前端有两档需求：

- **对话内的界面**：小，图片 + 少量组件。已有（`novex-def/use`、html 岛），本任务不动。
- **全屏前端作品**：**大需求、主战场**。创作者（主要在 PC 工坊）做一整套全屏界面（视觉小说、仿聊天软件、朝堂沙盘……），开启后 AI 的全部表现都由它呈现；用户在作品里输入、点选，传回诺文。AI 照常写故事，不管界面怎么画。

"电脑做、手机玩"：PC 模拟器复刻手机运行环境，作品在 PC 上做好看，手机上直接跑（前端是网页，手机不用重新编译）。**兼容原则：电脑向手机妥协（能力上限 = 手机，基线 Chromium 111），手机向电脑看齐（协议在电脑侧先定义，手机必须补齐）。**

### 1.2 现状差距（读码核对，2026-10-12）

手机端今天的全屏能力是两块雏形：

- **皮肤 overlay**：满幅 HTML 盖住转录区，读 `NOVEN_FRAME.messages`（最近 30 条原始文本）。`ChatScreen.kt` ~2126。
- **`display:"fullscreen"` 舞台件**：单例 WebView，缩小时 1dp 保活。`ChatScreen.kt` ~2120–2140、2210。

它们离"全屏作品"差四个硬缺口：

1. **只能看不能操作**：岛无 JS 桥、链接一律吞掉（`HtmlIsland.kt` `shouldOverrideUrlLoading` 返回 true），作品里点什么、输什么都发不出去。
2. **拿到的是原始文本**：作品得自己解析 markdown、围栏、选项，每个作者写一遍、必然写错。
3. **帧字段不全**：`buildIslandFrameJson`（`HtmlIsland.kt` ~547）只有 `card{name,kind}/state/max/messages/theme`。v0.2 承诺的 `session/env/archive/deltas/assets` 全缺；`sessionId` 在 bridge 里有却没下发（v0.2 已指认的 bug）。
4. **v0.2 承诺的 5 项一项没做**（r2 §D1）。

### 1.3 为什么按这个方案（关键决策与理由）

| 决策 | 理由 |
|---|---|
| 沿用 URL 拦截出站（`novex-query/compose/send://`）+ helper 兜底，**不开 JavascriptInterface / WebMessagePort** | 岛保持纯沙箱，无新增攻击面；与 PC 同构（v0.2 已冻结） |
| 作品可声明 `"input":"own"` 自带输入层，**磨砂叙事层取消** | 磨砂层是"岛无法替换输入层"的补偿，破坏沉浸也难做 |
| `send` 只认真实触摸、限频、发出内容进原生对话、**无自动发送** | 防脚本代发刷 token（定位不是证明用户意图）；真正的保护是"发出的每句都在原生层可见可撤" |
| `input:"own"` 是**模式切换不是授权** | 它只决定谁画输入框；权限约束全在宿主侧 |
| 作品内**不提供重试/编辑**；原生对话始终在下层 | 原生层 = 记录 + 逃生口：退出全屏可查模型原文、重试、编辑、切分支 |
| **档案真相源 = 日志**，锚定产生它的消息、沿活动路径解析，快照只是缓存 | 原生层能重试/切分支，档案必须跟分支走；手机注册表 `artifactDefs` 已是这个模式（条目锚定消息 id、沿路径回溯），复用而不另造分支系统 |
| **查证回写不新建消息节点**（作为当前助手消息的附属记录） | 新节点会成为最新叶子，污染重试目标与分支切换器——手机刚修过同类的重试错挂与空壳行 |
| `transcript` 由宿主解析一次下发，**解析规格 = 共享黄金样例** | 作品不再各写解析器；两端解析器必有差异，用样例定规格，才能公平判"不一致归谁" |
| 显式 `ready()` + 8s 超时/崩溃自动退回原生 | 全屏作品常用 `100vh`/`fixed`，高度回报不能当"已画出"信号 |
| Chromium 111 是**制作上限不是运行门槛** | 低于 111 只提示"作品可能显示不全"，不挡旧机型用户 |
| 一个模型演到底，台词标记 `行首+名册内名字+冒号` | 用户判断：结果表现好，多 agent 多是伪命题；标记可能误判，作品不得依赖 `speaker` 做逻辑 |

---

## 2. 范围

### 2.1 本任务做（手机侧 = r2 §D1 全部）

v0.2 欠账 5 项 + v0.3 新增 8 项（B1–B8），见步骤 S1–S9；S10 说明书；S11 审查与 CI。

### 2.2 明确不做

- 对话内原生组件库（`kind:"components"`）——另立项。
- 代理输入（原生输入条换肤）——B3 后备，真机验证 WebView 输入法有问题后再定。
- 自动发送、卡级"禁止回退"、角色卡向世界作品提供界面片段、`transcript` 历史翻页（v0.4）。
- 多 agent / 模型路由。
- 旧会话数据的破坏性清理。
- PC 侧任何代码（PC 有自己的施工顺序，回执 §D）。

---

## 3. 架构落点总览

```
AI 输出（助手消息，持久化，parent_message_id 成树）
   │
   ├─ 解析（新：novex.core 纯 Kotlin，可单测）
   │    StageTranscriptParser  → transcript 段     （S3）
   │    StageDeltaResolver     → deltas / archive  （S7）
   │
   ├─ 帧组装（新：IslandFrame 模型 + 编码，替代 buildIslandFrameJson 的字符串拼装）（S1）
   │    card/session/env/state/max/theme/messages(弃用)/reason/turn
   │    + transcript/choices/cast/deltas/archive/assets/sendResult
   │
   ├─ 推帧：IslandRenderer.sync → evaluateJavascript(frameUpdateJs)  （已有，S1 加 reason）
   │
   └─ 出站：shouldOverrideUrlLoading 拦三条 scheme + console 信号（高度/ready）（S2）
          novex-query://   → 静默查证轮 → 附属记录 → 推帧 reason:"query"（S7）
          novex-compose:// → 填输入框草稿（S2）
          novex-send://    → 门控 → 现有发送/排队路径 → 推帧 reason:"send"（S4）
```

既有关键位置：

| 位置 | 内容 |
|---|---|
| `ui/chat/HtmlIsland.kt` | `HtmlIslandBridge`（~63）、`HtmlIsland` 组合（~114）、`IslandRenderer`（~264，含重载合并、`sync`、`load`）、`configure`（~443）、`wrapIslandDocument`（~471，包装页：CSP、注入帧、高度信号）、`frameUpdateJs`（~504）、`buildIslandFrameJson`（~547） |
| `ui/chat/ChatScreen.kt` | `HtmlIslandBridge` 构造与 `messagesJson`（~1850）、`LocalNovexStageOpener`（~1870）、舞台/overlay 渲染与保活（~2120–2225）、`stageVisible` 与返回（~521、~684）、输入区 `ChatComposerSection`（~2242） |
| `ui/chat/NovexArtifacts.kt` | `NovexArtifactDef`（~58，含 `fullscreen`/`tavernCompat`）、def 元信息解析（~150） |
| `novex/core/NovexConversationConfiguration.kt` | `ConversationArtifactDefinition`（~176，持久注册表）、`PlaythroughState`、配置快照 |
| `novex/core/NovexConversationConfigurationCodec.kt` | 配置持久化编解码 |
| `novex/core/InteractiveFictionRuntime.kt` | `resolveArtifactDefs`（沿活动路径解析，**S7 的范本**） |
| `ui/chat/ChatViewModel.kt` | `_promptQueue`（~964，agent loop 运行中排队，**S4 的排队路径**） |
| `ui/chat/ChatTranscriptRow.kt` / `NovexChoiceButtons.kt` | 选项渲染（~226、~282）—— S6 选项数据来源 |
| `test/.../HtmlIslandDocumentTest.kt`、`NovexArtifactsTest.kt` | 已有包装页/件解析单测，扩展即可 |

---

## 4. 施工步骤

> 顺序与 PC 回执 §D 对齐（先帧骨架，后样例，再出站，再档案）。建议拆 4 个 PR：**PR1 = S1+S2+S9**（v0.2 欠账 + 帧骨架 + 出站 + 逃生口），**PR2 = S3–S6**，**PR3 = S7**（先解决阻塞项 Q1），**PR4 = S8+S10**。每个 PR 独立过 CI、独立审查。

### S1. 帧骨架：`IslandFrame` 模型 + `env` / `session` / `reason` / `turn`

**做什么**
1. 新建帧模型（建议 `ui/chat/IslandFrame.kt`，纯数据 + `toJson()`），把 `buildIslandFrameJson` 的字符串拼装迁过来，字段按 r2 B3/§B 全表；本步先落：`card{id,name,kind}`、`session`、`env{platform:"android", bridgeVersion:2, webview, ime}`、`state`、`max`、`theme`、`messages`（保留，注释标弃用，v0.4 删）、`reason`、`turn{status,queued}`。
2. 修 v0.2 bug：`session` 下发（bridge 已有 `sessionId`）。
3. `env.webview`：`WebView.getCurrentWebViewPackage()?.versionName` 取 Chromium 主版本号（minSdk 26 可用）；取不到填 `null`。低于 111 时在舞台入口处给一次性提示"系统 WebView 过旧，作品可能显示不全"，**不拦截**。
4. `reason`：每次推帧带上原因（本步先有 `init`/`turn`；后续步骤加 `query/branch/ime/send`）。`IslandRenderer.sync` 现在只按帧内容去重推送——推帧接口改为携带 reason，**同内容但不同 reason 的事件（如 send 回执）不能被去重吞掉**。
5. `turn`：`status` = 是否流式中；`queued` = `_promptQueue.size`。

**为什么**：所有后续字段都挂在帧上；PC 也是第 1 步做帧骨架，体检岛先有东西可测。`messages` 保留是因为皮肤 overlay 依赖它。

**验收**
- 单测：帧 JSON 包含全部字段；`session` 非空；`bridgeVersion==2`；`messages` 仍在；reason 不同的两次推送都发出。
- 现有皮肤 overlay（`assets/skin-demo/skin-overlay.json` 弹幕皮肤）行为不变。

### S2. 出站：三条 scheme + 五个 helper + `asset()`

**做什么**
1. `shouldOverrideUrlLoading` 识别 `novex-query://<domain>/<id>?from=<scene>`、`novex-compose://?text=`、`novex-send://?text=`，其余链接维持吞掉。回调经 `IslandRenderer` → `HtmlIslandBridge` 新增的回调（`onQuery/onCompose/onSend`）交给 `ChatScreen`。
2. 包装页注入 helper：`NOVEN_FRAME.query(domain,id)` / `compose(text)` / `send(text)` 内部转 `location.href`；`asset(id)` 返回 `novex-asset://<id>`；`ready()` 走 console 信号（与 `IslandHeightSignal` 同机制，新前缀如 `__novex_ready__`）。
   - **坑**：`frameUpdateJs` 是 `window.NOVEN_FRAME = <新对象>`，热更新会**把 helper 冲掉**。必须改成"新数据合并进既有对象"或每次推帧后重挂 helper；写单测锁住"推帧后 helper 仍在"。
3. `compose`：把文本填进输入框草稿，**不发送**（找 `ChatScreen` 里输入框状态的 setter；若输入栏被 `input:"own"` 隐藏，则 compose 无意义——丢弃并记日志）。
4. `assets` 映射表：卡资源（`cardResourceLoader`）与收割期 `remoteResources` 合并下发（v0.2 B2）。
5. CSP 维持 `connect-src 'none'`，不动。

**为什么**：v0.2 欠账；没有出站，作品就只能看。URL 方案无新增攻击面。

**验收**
- 单测：包装页含五个 helper；`frameUpdateJs` 后 helper 存在；URL 解析（含中文/emoji urlencode、超长文本）正确；未知 scheme 仍被吞。
- 契约：`HtmlIslandDocumentTest` 里 CSP 断言不变。

### S3. 黄金样例 + `transcript` 解析器

**做什么**
1. **样例格式**（与 PC 共用，放 `src/android/app/src/test/resources/stage-golden/*.json`）：
   ```jsonc
   { "name": "多人台词", "cast": [{"name":"阿七"},{"name":"老鬼"}],
     "input":   [{ "id":"m1", "role":"assistant", "content":"……", "streaming": false }],
     "expected":[{ "id":"m1#0", "kind":"narration", "text":"…", "html":"…", "speaker":null, "streaming":false }] }
   ```
   PC 起草首批（旁白 / 单人台词 / 多人台词 / 误判样例 / 安全子集转义 / 出图 / 协议块剥离）；**手机负责补流式中间态**（同一消息在不同截断点的期望输出，含未闭合围栏、未闭合 `UpdateVariable`、台词标记被截一半）。PC 首批未到时，按此格式先起草手机版，标注"待与 PC 对齐"，到货后以合并后的样例为准。
2. 解析器放 `novex/core/StageTranscriptParser.kt`（纯 Kotlin，无 Android 依赖）：
   - 剥协议块：复用 `stripNovexProtocolFences`（`ChatScreen` 已在用）与 `MvuStateProtocol.stripBlocks`（含未闭合尾部）。
   - 切段：按空行分段；markdown 图片 → `kind:"image"`；段 id = `消息id#段序号`。
   - 台词标记：行首 + `cast` 内名字（可带 `@`）+ 全/半角冒号 → `dialogue` + `speaker`；否则 `narration`。
   - `html` 安全子集：`**`→`<b>`、`*`→`<i>`、换行→`<br>`，其余全部转义。
   - `streaming`：消息仍在流式时，**最后一段**标 `true`。
3. 帧加 `transcript`：活动路径上最近 60 段（user + assistant）。

**为什么**：作品不该自己解析文本；样例是两端共同的规格，"不一致算谁的"才有依据（r2 §A 前提）。

**验收**：所有样例通过（单测逐个跑 json）；流式中间态不出现半截协议块或半截标记。

### S4. `send` + `input:"own"` + `sendResult`

**做什么**
1. def 元信息解析 `"input":"own"` → `NovexArtifactDef.ownInput`；同步加进持久注册表 `ConversationArtifactDefinition`（新增字段 + codec + round-trip 测试，**老数据缺字段默认 false**）。
2. 舞台可见且 `ownInput` 时隐藏原生输入区（`ChatComposerSection` 所在 Box）。缩小舞台或退出全屏立即恢复原生输入栏。
3. 门控（只对全屏舞台岛生效；对话内小岛 `send` 一律 `dropped/not-own`）：
   - 给舞台 WebView 挂触摸监听，记录最近一次 `ACTION_UP` 的 uptime 与手势序号；
   - `send` 到达时：该岛 `ownInput` 为真；距最近抬起 ≤1500ms；该手势尚未用过；距上次 send ≥1s；长度 ≤ 原生输入栏上限（找现有常量，不要新造）。任一不满足 → `dropped` + `why`。
4. 通过后走**现有**发送/排队路径（`ChatScreen` 的 send-or-enqueue 共享处理器 / `viewModel.sendMessage`），生成中自动进 `_promptQueue` → 回执 `queued`，否则 `accepted`。发出内容是普通用户消息行。
5. 推帧 `reason:"send"` + `sendResult{result, why}`。

**为什么**：作品自带输入层必须能发；门控防脚本刷 token；回执让作品的输入框能显示"没发出去"；排队与两端现有行为一致。

**验收**
- 单测：门控状态机（无触摸、超时、同一手势二次、限频、超长、非 own）各分支；codec 新字段 round-trip 与旧数据兼容。
- 真机（用户）：作品里输入 → 消息出现在原生层；生成中发送显示排队；连点只发一条。

### S5. `env.ime` + 作品区抬升

**做什么**
1. 根 Column 已有 `.imePadding()`（`ChatScreen` ~1960），舞台区域会随键盘收缩——**先真机确认**这已满足"平滑抬升、不遮挡"，不满足再加动画。
2. 读 `WindowInsets.ime` 底部高度，换算 CSS px（÷ density）写入 `env.ime{visible,height}`，变化时推帧 `reason:"ime"`（节流，键盘动画期间不逐帧推）。

**为什么**：作品要贴着键盘排版自己的输入框；用户决策是"用系统键盘，诺文不造键盘"。

**验收**：单测换算；真机（用户）验证中文输入法组字、长文本、粘贴——结果决定代理输入后备是否启用（PC 回执 §E-2 的回请）。

### S6. `choices` + `cast`

**做什么**
1. `choices`：取活动路径最新一轮助手回复里的选项（`present_choices` 工具块 / 快捷操作，参照 `ChatTranscriptRow.kt` ~226、~282 的数据来源），下发 `{turnId, items:[{id,label}]}`，无则 `null`。
   - 注意：舞台全屏时转录区本就被舞台替换，原生选项条自然不可见——"全屏隐藏原生选项条"已满足，**不要另写隐藏逻辑**；只需确认缩小舞台后选项条正常回来。
2. `cast`：本局挂载的角色卡 → `[{id,name,avatar,color}]`。**数据来源需先调研**：从 `NovexConversationConfigurationSnapshot` 的 `backgroundSettings` / `managedSubjects` / `cardBindingJson` 与卡仓库找"挂载的角色卡"及其头像资源；头像以资源 id 下发（经 `asset()` 解析）。缺字段给 `null`，不报错。
3. 台词解析（S3）用的名册 = 这份 `cast`。

**验收**：单测（无选项为 null；cast 缺头像/颜色为 null）；真机看一个双角色卡的名册是否正确。

### S7. 档案：日志即真相 + 分支重放 + 静默查证轮 【先解阻塞项 Q1】

**做什么**
1. **deltas**：沿活动路径收集助手消息里的 `novex-use` 信封（复用 `NovexArtifacts` 的围栏解析；合并气泡要展开 `sourceDbIds`——4.0.6 注册表踩过此坑），每条带 `{seq, messageId, envelope}`，按路径顺序编号。
2. **查证回写 = 附属记录**：在会话配置里新增按消息锚定的列表（建议 `stageAnnotations: List<{messageId, seq, envelopeJson, label}>`，codec + 测试），解析沿活动路径（照抄 `InteractiveFictionRuntime.resolveArtifactDefs` 的路径语义：继承祖先、兄弟不可见）。**禁止为查证插入任何消息行**。
3. **静默查证轮**：收到 `novex-query` → 用当前会话模型 + query 提示词角色（P1 单模型双提示词，v0.2 C2）发起一次不入消息树的调用；要求只回 `novex-use` 信封；解析成功写附属记录（锚定发起时的那条助手消息——即使期间新回合已开始，见 PC 回执 §B3 边界）→ 推帧 `reason:"query"`。不计轮次、不推时钟。失败：推帧带错误，不重试风暴。
4. **显示层留痕**：在那条助手消息下显示一枚折叠小标"查阅了××"（读附属记录渲染），不是消息行，不参与父子关系、不带操作行、不影响分支切换器。
5. **分支重放**：活动路径末端变化（切分支、重试生成新兄弟、编辑分叉）→ 重算 deltas/archive → 推帧 `reason:"branch"`。
6. **archive 快照**：见 Q1，定了合并语义后再实现；在此之前 `archive` 下发 `{}`、由作品重放 `deltas`（v0.2 原设计"岛端 JS 重放 delta 物化"），这是合规的最小实现。

**为什么**：原生层能重试/切分支，档案必须跟分支走；锚定消息 + 沿路径解析是手机已验证的模式；不建节点是为了不再制造空壳行与重试错挂。

**验收**
- 单测：路径解析（祖先继承、兄弟隔离、同消息多条按 seq）；附属记录 codec；查证回写不改变消息树（行数不变）；`reason:"branch"` 在路径变化时触发。
- 真机（用户）：重试一条带档案增量的回复 → 作品显示的事实随分支切换而切换。

### S8. 一致性体检岛

**做什么**
1. 资产 `assets/stage-check/check.html`（与 PC 同一份，来源以先写好的一侧为准）：逐项检测 r2 B8 清单（帧字段、`env.webview` 对基线、CSS/JS 特性、`asset()`、三条出站、send 三态回执、各 reason 派发、`ready()`、高度/错误回报），输出 JSON 报告并显示在页面上。
2. 入口：开发者选项里"运行舞台体检"，以全屏舞台方式打开；报告可复制。
3. 黄金样例（S3）在单测里跑；体检岛只测运行时行为。

**为什么**：把"不一致视为手机侧缺陷"变成可执行检查，不靠肉眼。

**验收**：真机跑通并导出报告；与 PC 模拟器报告逐项比对，差异逐条归属。

### S9. `ready()` 与逃生口

**做什么**
1. 全屏舞台装载后计时 8s，收到 `ready()` 即解除；超时 → 自动退出全屏（`stageVisible=false`）回原生对话 + 提示"作品未能加载，已切回对话"。
2. 运行中渲染进程丢失（`IslandRenderer.onCrash` 已有自疗重载）：舞台连续崩溃 2 次 → 退出全屏 + 提示，不无限重载。
3. 兼容：未调 `ready()` 的旧岛仍按高度回报判定，**仅限非全屏件**；旧的 `display:"fullscreen"` 件与皮肤 overlay——**不施加 8s 超时**（它们是 v0.2 以前的作品，不会调 `ready()`），只对声明 `input:"own"` 或 `bridgeVersion` 新协议的作品施加。

**为什么**：原生层是逃生口的另一半；作品坏了绝不能把用户困在黑屏里。

**验收**：单测（超时状态机、崩溃计数）；真机：故意写一个不调 ready 的新协议作品，8s 后回到对话。

### S10. 说明书

- `design-intent.md`「前端作品」节开头的"规划中"标注：按实际上线范围改成"已上线 / 部分上线"。
- 上线后才改用法章：`frontend-widgets.md`（`input:"own"`、helper 用法、`ready()`）、`islands.md`（帧新字段、出站）、`skins.md`（`messages` 弃用、改用 `transcript`）。只写真实可用的。
- `SKILL.md` 章节地图同步、版本号 +1；`FILES.txt` 若新增章节同步。
- `whats-new.md` 加对应版本条目（版本号由用户定）。

### S11. 审查、自检、CI

1. 每个 PR：契约测试三项（`scripts/tests/novex_core_module_test.sh`、`novex_forbidden_artifacts_test.sh`、`novex_ui_boundary_test.py`）全过；全文件扫描未用/缺失导入、组件禁用清单。
2. 重级审查（A/B/C），意见三分类处理（确定缺陷直接修 / 可能风险读码或加验证 / 产品取舍交用户）。
3. 推分支 → CI `android-validate`（单测 + 编译）。失败读真实日志定位到行再修。
4. 汇报格式：先结论、再证据、最后待确认项；UI 行为标"未视觉验证"，附编号真机验收步骤。

---

## 5. 阻塞项与待问

| # | 问题 | 为什么阻塞 | 处理 |
|---|---|---|---|
| **Q1** | **delta 合并语义未定义**：绍宋信封示例里 `persons.loyalty: 92` 像绝对值，`armies.strength: -3000` 像增量，协议没规定字段是"覆盖"还是"累加"。 | 宿主无法正确物化 `archive` 快照；两端各猜一种必然不一致 | S7 开工前带去和用户 + PC 对账（建议：默认覆盖；累加字段用显式写法如 `{"strength":{"$add":-3000}}`，或数值统一走 ledger/`update_playthrough_state`）。定之前 `archive` 下发 `{}`，作品自行重放 `deltas` |
| Q2 | `cast` 的数据来源（"挂载的角色卡"在配置里的确切表达） | S6 需要 | 调研后若不确定，问用户 |
| Q3 | 黄金样例首批由 PC 起草 | S3 依赖 | 未到货先按格式起草手机版，到货后合并 |
| Q4 | 版本号与发版节奏 | 每个 PR 是否单独出版本 | 问用户；默认不发版，攒到用户批准 |

---

## 6. 风险

| 风险 | 对策 |
|---|---|
| `frameUpdateJs` 整体替换 `NOVEN_FRAME` 冲掉 helper | S2 单测锁定 |
| 推帧去重吞掉同内容不同 reason 的事件 | S1 改推帧接口携带 reason |
| 查证回写误建消息节点 → 空壳行/重试错挂复发 | S7 附属记录 + "消息树行数不变"单测 |
| 舞台 WebView 内中文输入法组字/长文本异常 | S5 真机验证；不行启用代理输入后备（另行确认） |
| 门控误杀正常发送（触摸监听被 WebView 消费） | 用 `setOnTouchListener` 观察不消费（返回 false）；真机验证 |
| 旧作品（皮肤 overlay、v0.2 舞台件）被新超时/新纪律误伤 | S9 只对新协议作品施加超时；`messages` 保留 |
| 长会话 deltas 收集性能 | 只扫活动路径上的围栏，不解析正文；必要时按 `(会话末端, 最后 seq)` 缓存 |
| 两端解析器分歧被误判为手机缺陷 | 以黄金样例为准；规格未覆盖的差异先补样例再定归属（r2 §A） |

---

## 7. 完成定义

- r2 §D1 全部勾掉；PC 回执 §E 三条回请已答复（流式中间态样例、真机输入法验证结论、`env.webview` 如实上报确认）。
- 黄金样例两端全过；体检岛两端报告一致（或差异逐条归属并有处理）。
- 说明书与实际能力一致。
- 用户真机验收通过；发版另经用户批准。
