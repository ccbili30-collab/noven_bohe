# 全仓多维质量审查（2026-10-10）

> 审查基线：`ui-work` @ `bcd96503`（2026-10-10 02:52 +0800），分支 `ai-card-draft-fix`。审查期间另一 AI 正在本仓工作，行号以基线快照为准，可能已漂移。
> 方法：纯静态只读——未编译、未跑测试（避免与在途工作抢 gradle 锁）。12 个分片审查代理对 7 个 Gradle 模块全部主源码（1306 文件 / 约 22.6 万行）分片深读，主审对全部 P0 与关键 P1 逐条亲验证据行（下文标「亲验」者）。
> 维度：D1 逻辑正确性 / D2 代码整洁 / D3 可维护性 / D4 测试有效性 / D5 一致性 / D6 顺带发现。
> 与既有审计的关系：不重复 2026-09-30《架构质量审计》（结构面，本文只做增量复核）与 2026-10-10《模型寻址债务审计》（工具契约面，交叉点只引用）；外部独立评审简报的六项缺陷定位任务不在本审查范围。
> 分级：P0 = 功能不可用 / 安全越权 / 资产性缺口；P1 = 每次改动都在付息；P2 = 卫生记账。
> 全程未修改任何文件；本报告为唯一新增文件。

## 0. 总评

**不会立刻崩，且多处工程质量显著高于同类仓的水位——但四条 P0 各自代表一类系统性风险：凭据卫生（P0-1）、提示词-工具面漂移（P0-2）、全仓最大状态机零测试守护（P0-3）、数据库升级路径大半裸奔（P0-4）。**

横向上，约 30 条 P1 的根因高度收敛为三类「半途」债务：**退役不彻底**（file_write 从 schema 退役但分发层仍可执行、shell/browser 有拒绝 stub 而 file 族没有、PROPOSE 工具新旧两份描述互相矛盾、指南里成片死分支）、**迁移不彻底**（ui/cards 整族仍从旧世界取色、authkit 四家登录流复制骨架后各自漂移、三套角色持久化并存双写、i18n 半程）、**拆分不彻底**（ChatViewModel 16.6k 行物理拆分而非逻辑拆分、streamJob 样板 5 处逐字复制、三张平行 16 分支 when、length 前缀 8 处手拼）。

纵向上质量梯度明显：**conversation-runtime / model-transport / content-storage / novex-core 四个模块接近引擎级质量**（协议自洽、幂等三件套、崩溃恢复被测试钉死、错误文案面向模型可自救）；**app 模块的 chat 编排层与设置面是付息中心**；app/data 居中（单 DAO 操作考究、跨存储编排缺纪律）。测试面结论：**资产，不是幻觉**（零 mock 框架使同义反复结构性不可能，2075 例以行为级验证为主），问题在覆盖地形图——引擎全覆盖、编排层零覆盖。

并发安全总判：得益于「单回复内工具串行派发 + 实例级锁」的当前事实，绝大多数 TOCTOU 是潜伏而非现行；真正的现行风险集中在 ChatViewModel 集群的**跨线程裸结构**（P1-13/14/15）。

## 1. P0（4 条）

### P0-1｜D1 安全｜Codex OAuth 令牌明文进日志，且可随崩溃分享流出设备【亲验】【已降级为潜伏项，见补记】
> **复核补记（2026-10-10，产品方指出后亲验）**：产品当前不提供 OAuth 登录入口——`startBrowserLogin`/`exchangeCode` 在全部主源码零调用点（不只 Codex，五家登录流的浏览器入口一起无入口）；authkit 存活面仅剩 `isAuthenticated`/`validAccessToken`/`logout` 与导入导出，token 只能经 provider JSON 导入或手工 bearer 进入。裸日志位于 exchange 函数，刷新路径走基类 `sanitizeBody` 脱敏，不会被现存路径触达。**定性由「活体泄露」改为「死机械里的地雷」**：一旦未来接回登录入口（或有人复用 CodexLoginFlow 的客户端代码）即激活。处置建议相应改为二选一拍板：产品不再要 OAuth 登录 → 连同五家登录流一起退役入账（保留 vault/刷新/导出）；还要 → 先修脱敏再留。P0 实存数由此为 3+1。
- 现象：token 交换成功时把响应体前 500 字符原样写进 RunLog（含完整 `access_token`/`refresh_token`/`id_token`）。
- 证据：`app/src/main/java/novex/android/authkit/CodexLoginFlow.kt:208-211`（`body[0..500]=${responseBody.take(500)}`）；`:214` 异常消息同样带原始 responseBody。泄露链三段：`logkit/RunLog.kt:161-172`（无条件进 logcat + 开关开启时落 `filesDir/logs/minis-*.log`）→ `crashguard/CrashShareFlow.kt:179-203`（近 3 天日志列为可勾选分享项）→ `:306-321`（zip 经 FileProvider 外发）。
- 根因：`OAuthWire.redactForLog`（`OAuthWire.kt:35-40`）已为此而生，但 Codex 线新建 `proxyAwareClient` 绕开统一纪律，顺手绕开了脱敏；无人强制成功路径走它。
- 建议方向：`body[0..500]` 一律走 `redactForLog`（或只记 bodyLen+脱敏片段）；异常消息脱敏；考虑给 RunLog 加凭据字段兜底过滤层。同类待清：`LoopbackReceiver.kt:108` 与 `LoopbackRedirectRelay.kt:27,39` 把含 `code=/state=` 的授权 URI 整条进 logcat。

### P0-2｜D5 提示词漂移｜NovexProductToolGuide 旗舰协议块恒不渲染，else 文案与实际工具面直接矛盾【亲验】
- 现象：未挂卡会话的模型同时收到 `create_card`/`create_card_bulk` schema 和系统提示「本轮未提供完整卡片写入工具……不能声称已经创建或保存应用内卡片」。
- 证据链：`ChatViewModel.kt:1547` 把 `novex_read_context`/`novex_inspect_content`/`novex_propose_content_changes`/`novex_apply_content_changes`（`NovexManagementTools.kt:14-17`）连同 NovexCardFileTools、worldbook、文游名全部列为 legacy 过滤且无处加回；指南由同一过滤集构建（`ChatViewModelPersistExt.kt:530-536`），故 `NovexProductToolGuide.kt:14` 的 `containsAll` 门恒假 → `:36-38` else 恒渲染；`:13/:22-33` 全部恒假成死文本。请求 schema 确实取自该过滤集（`ChatViewModelAgentLoopExt.kt:583-587`）；挂卡会话走 `IntegratedCardPrompt`，完全不经过本指南。
- 根因：工具面从「novex_* 管理工具」迁往「integrated 卡工具」时只改了 schema 装配，指南门控名、分发层注释、friendlyToolTitle 停在旧世界——与已知的 browser_use 死分支、PROPOSE 双描述矛盾（`NovexManagementTools.kt:74` vs `:21`）同根：迁移半途。
- 建议方向：指南门控按现行面（create_card 族/CardToolProtocol 名）重写或并入 IntegratedCardPrompt；未挂卡 else 文案与 `creationDefinitions()` 对齐；顺带清 `NovexProductToolGuide.kt:63` browser_use 死分支与 PROPOSE 旧描述。
- 影响：每一条未挂卡会话都在发送自相矛盾的指令，弱模型行为劣化的候选根因之一（与外部缺陷定位评审的症状链相关，线索移交）。

### P0-3｜D4 测试｜ChatViewModel 集群 16.6k 行零实例化测试，三个已证实竞态无任何守护【亲验：grep `ChatViewModel(` 于全部测试 0 命中】
- 现象：全仓最大的状态机（主类 5705 行 + 10 个 Ext）从未被任何测试构造；`agentHistory`（普通 ArrayList，`ChatViewModel.kt:1051`，IO 循环 15+ 处无锁写）、`_promptQueue`（RMW 非原子，`ChatViewModelTurnExt.kt:445/508-510`）、`streamFlushStates`（HashMap 跨线程，`ChatViewModel.kt:628`）三个并发器械全测试面 grep 零引用。
- 根因：架构上刻意「抽出纯函数测、编排不测」（ToolPreflightTest 等文件头自述），该策略恰好把最易坏的部分留在射程之外。
- 建议方向：prompt 队列（改 `MutableStateFlow.update` 并抽 QueueCoordinator）、streamFlush 注册表、agentHistory 封装为带不变量的独立类，配竞争注入测试；至少给 drainQueuedPrompts 原子性写一条失败注入测试。

### P0-4｜D4/D1 测试｜Room 迁移 39 条仅 12 条有测试，exportSchema=false 无 schema 基线
- 现象：`NovexMainDatabase` version=40（`NovexMainDatabase.kt:97-98` 同时 `exportSchema = false`）；仅 14→15、15→25 两段有 androidTest；**1→14 共 13 条与 26→40 共 15 条完全无测试**。补偿只有 Robolectric in-memory 的当前形态断言——只护当前，不护升级路径。
- 根因：迁移测试靠手工搭旧版库，成本随版本线性增长，老段落从未回补；无 schema JSON 使 MigrationTestHelper 式全链验证结构性不可能。
- 建议方向：开 exportSchema 生成 40 版基线逐步前推；短期补 26→40 高频段手工测试；CI 加「迁移数组连续无洞」结构断言。与持久化片的 `exportSchema=false` 代码面发现（P1-22 同源）合并看。

## 2. P1（按域分组，约 30 条）

### 2.1 安全与边界

**P1-1｜ContentPaths 无路径规范化，模型可控 `..` 可越出挂载点**（两分片独立发现，亲验）
`novex/android/data/ContentPaths.kt:80-82/94-95` 裸 `File(base, tail)` 拼接，无 canonicalize+前缀校验；`tools/SkillReadTool.kt:47` 仅 `startsWith(ALLOWED_PREFIX)`——`/var/minis/skills/../../memory/x` 通过前缀检查后可达 filesDir 任意文件、跨会话目录。对照组：`ChatViewModel.kt:1511-1512` 对会话图片已做 canonical+前缀双检，模式已知但未套到最宽通路。PRoot 退役后「内核内路径天然受限」的假设失效。若 READ_ONLY 会话可触达 file_read，则逼近 P0。修法：`resolveHostPath/resolveSessionHostPath` 出口统一 canonicalFile + startsWith 双检。

**P1-2｜明文 HTTP 三层政策互相矛盾**：`network_security_config.xml:7` 全局 `cleartextTrafficPermitted=true` vs `NovexTransportProvider.kt:1039` 传输层仅 https/回环 vs `NovexProviderSetupScreen.kt:367` 等 UI 校验仍接受 `http://`。收窄为 loopback 域白名单即可（域配置已就位）。

### 2.2 工具面（退役不彻底 + 编排缺口）

**P1-3｜file_write/file_edit 已从 schema 退役但分发层仍可执行**（亲验）：`ChatViewModelAgentLoopExt.kt:2057-2062` 真实执行写盘且成功后触发 `maybeReloadSkillsForPath`（写 `/skills/<id>/SKILL.md` 即持久进提示词面）；同文件 `:2076-2088` 对 shell/browser 有显式拒绝 stub，file 族没有。模型幻觉/注入出的调用会真实落盘。修法：与 shell 同款拒绝 stub，或仅限持久化历史回放。

**P1-4｜Android 端无通用工具超时**（亲验：全仓 0 处 `timedOut = true` 赋值）：`ToolExecutionResult.kt:27-31` 注释与 `AgentLoopExt.kt:1430` 的 TIMEOUT 消费者存在，生产者不存在；任一工具挂死无限拖住回合，`learning_start awaitCompletion=true` 无上限（`ChatViewModel.kt:1137`）。修法：`novexToolExecution.execute` 外按类别包 `withTimeoutOrNull`。

**P1-5｜三个 Router 兜底把存储/索引故障误标为「参数无效」**：`NovexConversationWorkspaceTools.kt:570-576` 等把一切异常映射 `tool.invalid_arguments` 并附合法工具清单——索引损坏时模型被引向错误的自救方向（`NovexLearningTools.kt:211-217` 同型）。内层区分参数异常与存储异常，后者给稳定错误码。

**P1-6｜新增一个工具需同步 5-6 处，漂移已发生**：定义→AgentTools 注册→executeAuthorizedTool when（`AgentLoopExt.kt:2017-2144`）→friendlyToolTitle（`ChatViewModelHistoryExt.kt:596-639`，已缺 `learning_start`/`skill_read`/`web_fetch`，中文 UI 落英文兜底）→ToolGuide 条件行→卡工具另需 IntegratedCardToolLabels。让 `AgentToolDefinition` 携带 humanTitle、指南由定义自述，收敛事实源。

**P1-7｜学习回执账本 O(n) 重复重读，任务生命周期二次方开销**：`FileNovexLearningRepository.kt:31-35` 每次 save/find/run 批次全目录重读重解析回执 JSON；N 批任务 ≈ N²/2 次文件读。仓库内按 collectionRef 写穿透缓存，或改追加日志。

**P1-8｜同一工具面三套参数宽容度**：写卡主族按 [T-lenient-parse] 忽略多余键；`CardEditingTools.kt:38` 严格 `keys==全集`；`CardToolReader.kt:25` 读取工具也严格——弱模型给 read_card 顺手加一个说明字段即被硬拒，与本仓自己的事故批注（46 分钟建卡）认定的翻车点相悖。统一到「缺必需键报错点名、多余键忽略」。

**P1-9｜set_module_options 的 tags 解析失败静默清空标签**：`CardToolProtocol.kt:141` wrong-type 与缺省共用宽容路径，类型错被降级为合法空表且返回 saved 成功——一次「改规则」抹掉存量标签。wrong-type 抛带形状示例的错误。

**P1-10｜length 前缀命名空间编码手工复制 ≥8 处**：`"${chat.length}:$chat${turn.length}:$turn"` 在 ToolDialogueLoop/PersistentToolDialogue/CardToolCoordinator 等 8 文件手拼，是全部日志/暂停点/工具回执的寻址根，拼错不报错只让对账静默失败。抽单一编码/逆解析函数。

**P1-11｜控制/状态/存档三工具类 ~80 行同构样板**：`ConversationControlTools.kt:13-81` 等三类骨架逐行同构，细微不一致已产生（execute 异常捕获口径不一）。抽 `JournaledToolSession(parse, effect, idOf)` 模板。

**P1-12｜新增卡编辑变体需同步 4-5 处平行 when**：sealed 类/parse/execute 映射/encode/definitions（`CardToolProtocol.kt:14-187/314-336`）。把 execute/encode 挪进各变体多态方法，改动面从 5 压到 2。

### 2.3 ChatViewModel 集群并发与状态

**P1-13｜agentHistory 跨线程单边持锁，取消路径可 CME 崩溃**：`historyWriteLock` 只覆盖 Main 侧写点；IO 循环 15+ 处无锁写（`AgentLoopExt.kt:1081/1506/1533/1543/1601/1611` 等）；`cancelStream` 先协作式 cancel 就同步调 `handleUserCancelledCleanup`，其 `ChatViewModel.kt:5571-5572` 无锁 `lastOrNull()` 与 IO `add()` 并发可触发 CME → SupervisorJob 下未捕获 → 进程崩溃。修法：循环内 add 入锁，或取消路径先 `join()`（带超时）再清理。

**P1-14｜_promptQueue 的 read-then-clear 与 Main 侧 RMW 无原子性**：`ChatViewModelTurnExt.kt:657-659/508-510` 与 `:445` 特定交错下排队消息永久丢失（isQueued 气泡不落库，重载即蒸发），此后无 drain 触发点。统一 `MutableStateFlow.update { }` 或 Mutex。

**P1-15｜setInlineError 在 IO catch 中直调，遍历+clear 普通 HashMap 并与 _messages RMW**：IO 调用点 `TurnExt.kt:301/749/1133` 等未包 Main，与 Main 节流路径并发写 `streamFlushStates`（`ChatViewModel.kt:628`）——跨线程 HashMap 可至损坏/死循环。函数整体 `withContext(Dispatchers.Main)` 或换 ConcurrentHashMap。

**P1-16｜resumeQueueAfterCancel 的 claim 与检查隔多个挂起点，TOCTOU 可双开 streamJob**：`ChatViewModel.kt:5362-5420`，两个触发源（cancelStream 尾 + compactAll 尾）可各自通过 `_isStreaming` 检查后并发 claim → 双 agent loop。send/retry/rerun 都有同步 claim 纪律，唯此路径破例。

**P1-17｜标题生成预算是文件顶层静态（进程级），第 4 个新会话起标题永久 "New Chat"**（亲验）：`ChatViewModelTitleExt.kt:150-152` 顶层 `private var titleGenerationAttempts = 0` 编译为静态，跨 VM 实例共享；且早退在兜底标题逻辑之前——后续会话连首消息兜底都拿不到。改回实例属性。

**P1-18｜streamJob 启动样板 5 处逐字复制（每份 ~80 行）**：`TurnExt.kt:1091-1171`/`PersistExt.kt:680-752`/`ErrorExt.kt:439-511`/`TurnExt.kt:268-349`/`ChatViewModel.kt:5424-5491`——租约/回退/守卫全同构。抽 `launchAgentStream(...)` 单实现，这也是 P1-16 的修复载体。

**P1-19｜Enter 发送路径与按钮/上滑路径行为分叉**：`ChatComposerSection.kt:1177-1201` 自建发送逻辑，缺 `hasModelForSend` 守卫、`endSlashSessionForSend`、`takePendingCapabilities` 胶囊序列化（对照 `ChatScreen.kt:1280-1316`）——回车发送用户会丢紫卡胶囊/触发已修过的 slash 文本恢复 bug 复发。收敛为调用共享 handler。

### 2.4 持久化

**P1-20｜会话删除后媒体文件永不回收**（亲验：`deleteSessionMedia` 全仓 0 调用方）：附件先落盘再写消息行，`dropSession` 只删 DB 行（`ChatRepository.kt:213-216`）——磁盘无界增长 + 已删会话图片残留（隐私面）。删除入口补回收或启动期对账清扫。

**P1-21｜CharacterLibraryService 删除序列「子先根后、多事务」，中断即丢模块内容**：`CharacterLibraryService.kt:93-110` 无 FK 只能手工级联且未包事务；同文件 `:88-89` saveAsWorldVariant 先删后加两个事务，中间失败丢成员关系。根行先删或合并事务。

**P1-22｜角色数据三套持久化并存、双写路径活跃**：SharedPreferences（`CharacterCardStore`，UI 仍在写，`Card3Screens.kt:425`）+ Room 目录 + 文件制 CardStore，迁移是单向快照但旧写入口未关闭。明确单一事实源，迁移标记后禁写旧路径。

**P1-23｜stub+hydrate 的 N+1：列表查询逐行逐字段分片回读**：`CharacterCatalogDao.kt:111-115` 用 `'' AS x` 防_cursorwindow 后又无条件全量 hydrate，stub 规避加载的目的在列表路径被整体抵消；同构重复于 ContentModuleDao/InteractiveFictionDao/RevisionStore。列表 API 拆 stub 摘要版/hydrate 全文版两档。

### 2.5 性能与主线程

**P1-24｜设置面 31 处主线程同步落盘，最重一次点击串行 10+ 趟全表写**：`NovexProviderSetupScreen.kt:765`（onClick 内直接 `saveConnections`）→ `ProviderConfigStore.kt:296` `runBlocking { persist }`（五表全量换写+同步 commit 镜像）；每次 commit 还发布新 revision 连带活动 ChatViewModel 重算。给多步变更加「工作副本+单次 commit」事务 API 并挪 IO。

**P1-25｜ChatScreen 顶读流式 flatItems，每 ~80ms tick 触发 2305 行整函数重组，且组合期磁盘读+JSON 解析**：`ChatScreen.kt:1971`（effect key 订阅 flatItems）+ `:1696-1702`（`store.contents.open(ref).readBytes()` 主线程组合期执行，绑卡带 URL_MAP 扩展时每 tick 一次）+ `:1738-1762`（islandResources 每次新 Map → CompositionLocal 每 tick 变更）。桥对象打包 `remember(islandCard)`；行定位 effect 移进 ChatTranscript。

**P1-26｜ShareReceiverActivity 在主线程整流拷贝分享载荷**：`ShareReceiverActivity.kt:37-93` onCreate 同步 `copyTo`，分享数百 MB 视频即 ANR（Manifest 注册 `*/*`）。下放 Dispatchers.IO + 尺寸上限。

**P1-27｜START_STICKY 粘性重启后前台服务成孤儿：无停机裁决方 + 无超时唤醒锁常驻**（亲验 START_STICKY:90 + acquire():139 无超时）：停机是内存状态边沿触发（`LiveSessionHub.kt:218-248`），重生进程中枢空且未装配（attach 仅由 Activity 触发的 runtime 初始化调用），`haltService` 自身失能——崩溃/OEM 杀后服务带唤醒锁+Idle 通知无限存活。onStartCommand 对 `intent==null` 分支查两桶为空即 stopSelf，或唤醒锁改可续租+超时。

### 2.6 UI 行为

**P1-28｜保存任意供应商都会劫持全局默认模型组**（亲验）：`NovexProviderSetupScreen.kt:1136-1145` 两个分支都无条件 `repository.defaultPrimaryGroupId = it.id/group.id`，新会话直接落到被劫持组（`ChatViewModelSessionExt.kt:303`）；对照 `OnboardingModelSelectionScreen.kt:158` 有「原值为空才设」守卫。对齐守卫。

**P1-29｜会话多选工具栏「导出」按钮是空操作**（亲验）：`SessionListScreen.kt:864` `onExport = { /* TODO: export */ }`——违反设计合同 §7「每个可见操作必须立即产生反馈」。隐藏或复用单条导出循环。

**P1-30｜死代码规模 ≥2400 行成片滞留**：chat 片 `ToolPreviewBar.kt` 整文件 456 行 + `AssistantMessageView` ≈100 行 + InputCircleButton/ThinkingLevelPicker 等（`ChatAssistantMessageUI.kt:234`、`ChatComposerWidgets.kt:337/370`，grep 零调用）+ 生产 println/`if(false)` 段；settings 片三代编辑器叠葬 ≈1400 行死屏（`CharacterCardScreens.kt`/`Card3Screens.kt`/`WorldCatalogScreens.kt` 大部，生产路由已走 LegacyCardHost）；另 6+5 个文件头部整块复制的 import ≈2800 行噪音。按引用面分批清除。

### 2.7 结构与一致性

**P1-31｜NovexTransportProvider.kt 1411 行多职责堆叠**：四协议请求装配/头 URL Azure/嗅探白名单/流桥与 id 注册表/错误矩阵/生图代理/两条重试包装——每加一家供应商要同时动五处。按既定缝拆 host 嗅探 profile、StreamBridge+Registry、errorOf 矩阵三件起步。

**P1-32｜authkit 四家登录流「复制骨架再改」分裂出三处漂移**：①Claude/Gemini 覆写 `validAccessToken` 丢「手工 bearer 优先」分支（`ClaudeLoginFlow.kt:222-237` vs 基类 `VendorLoginFlow.kt:213`）；②Xai 重入闸挂实例字段而伴生 login() 每次新建实例，闸形同虚设（`XaiLoginFlow.kt:95` vs `:62`）；③Claude 私造刷新闸，RefreshGate/RefreshOutcome 三份拷贝（`ClaudeLoginFlow.kt:56-73` vs `RefreshGate.kt:16-27`）。上收 final 模板方法，子类只差量。

**P1-33｜ui/cards 整族 8 处从旧世界取色**：`novex/android/ui/cards/CardInjectionPage.kt:21` 等 8 文件 import 旧 `NovenColors/LocalChatPalette/NovenProfileStore`——旧世界删除之日即断链之时，新世界内部最大未绞杀飞地。换 NovexVisualSystem 或做过渡映射。

**P1-34｜i18n 半迁移**：strings.xml 800 键/7 语言，ui/ 下 222 个文件含 5383 行中文硬编码、stringResource 仅 623 次；zh-rTW 仅 380/800 键且无人挂账；`ModelGroupsScreen.kt:357-364` 硬编码英文与中文界面割裂。定「新代码必须 stringResource」lint 规则或明文放弃 i18n 的决策记录。

**P1-35｜同名包 novex.core 双栖两个编译单元，切分规则未成文**：模块侧 38 件（纯 JVM）vs app 侧 87 件（85 件纯 JVM 滞留 app）；当前零重名（重名即编译硬错），同包免 import 使「哪个类在哪个模块」不可见；`NovexWorkspace`（app，卡片库缝）vs `NovexConversationWorkspace`（模块）三处 workspace 语义过载。一句成文规矩 + 迁移候选清单。

**P1-36｜ARCHITECTURE.md 模块依赖图漏掉 conversation-runtime ← content-storage/content-core 两条真实边**（`conversation-runtime/build.gradle.kts` 四依赖 vs `ARCHITECTURE.md:15/19`）——上次修方向反转时没补漏边；文档数字快照（ui.cards 19→27、ChatViewModel「1.4 万行」口径等）全面过期。补边+数字带口径与日期。

## 3. P2 台账（按主题归并，约 160 条）

| 主题 | 代表条目（证据抽样） |
|---|---|
| 两阶段写补偿缺口 | workspace 索引写失败留漂移窗口（自愈靠 sha 拦截，`NovexConversationWorkspace.kt:398-418`）；EnvVar 元数据非原子+吞异常返回成功（`EnvVarRepository.kt:78-82/125-140`）；capture 文件先落去重在事务外（`CreativeArtifactRepository.kt:55-58`）；skill 部分删除可复活（`SkillRepository.kt:177-182`） |
| TOCTOU 潜伏族 | workspace_edit 三次锁分离（串行派发下潜伏，`NovexConversationWorkspaceTools.kt:202-275`）；propose 空卡 RMW（`NovexManagement.kt:560-591`）；CardToolCoordinator 共享字段无同步（每运行一实例纪律，`CardToolCoordinator.kt:59-75`）；LiveSessionHub 集合 RMW 无锁（`LiveSessionHub.kt:97/117`） |
| 错误处理 | Router 兜底误标（→P1-5）；StreamAssembler 丢弃无 id 工具调用无信号（`ChatCompletionStream.kt:207`）；错误细节吞没成泛化文案簇（`ChatCompletionClient.kt:404` 等 4 处）；runCatching 全仓 606 处，静默点集中在 Gemini 网络助手/BatchImporter/迁移 failures++ |
| 取消语义 | 两执行器裸 runCatching 吞 CancellationException（`ChatViewModelNovexToolsExt.kt:454/482`）；sendMessage activateBoundCard 同款（`TurnExt.kt:871-878`）；启动链 `NovexStartupCoordinator.kt:66/91` |
| @Volatile 遗漏 | `novexContextRevision`（Long，32 位 ARM 理论撕裂）/`activeBranchPathIds`/`realSessionId` 等 5 项（`ChatViewModel.kt:1564-1582/4248`），对照同文件已 @Volatile 的 3 项属遗漏 |
| 死代码/死参数 | friendlyToolTitle 停旧世界（→P1-6）；read_image legacy 分支注释与机制矛盾（`ChatViewModelNovexToolsExt.kt:551-553`）；`resolveNextFallbackProvider` 等 4 项零调用；`NovexActiveToolAuthorization` 死机制（`NovexToolExecutionTest` 外零消费）；ImagesClient 不可达尾码；WorldCatalogScreens 仅测试引用 |
| 重复样板 | sha256-hex 助手 ≥10 处；parts_json 类型标签 4 处独立手写（漂移证据：uiToolUse 仅一处有）；exists 查询跨 DAO 重复；mimeType 映射三份不一致；启动骨架页 60 行逐字重复；EXTRA_SESSION_COUNT 常量两处定义 |
| 命名/包组织 | repo 包 4/12 文件名与主类不符；`novex.android.models` vs `data.model` 撞名；MinisLinks.kt 无此类；vault 一词双义；表名三代并存；LLM/Agent/Wire 三套消息前缀同链接力（绞杀期冻结，记账） |
| UI 卫生 | 组合期 File.exists 多处；`thinkingLevel.value` 非订阅读（`ChatTranscript.kt:615`）；fallback 脉冲动画逐帧重组（`ChatScreenTopBar.kt:388`）；remember 键内嵌 collectAsState 反模式；ConversationSettingsScreen 单函数 964 行；ModelEntryDetail 无 key 无未保存拦截；路由 URI 编码政策不一致（8 处不编码 vs 1 处编码+崩溃注释） |
| 设置/导航 | configLoaded 门未铺满（PROVIDER_DETAIL 等恢复窗口可空屏）；FileBrowser 无代次守卫；FilePreviewHolder 全局单例进程恢复空屏；模型列表 take(100) 静默截断可吞已选项；verifyConnection 每次 new OkHttpClient |
| 安全外围 | OAuthRedirectActivity 导出面任意回环端口转发+URI 全量日志；allowBackup=true 与 wire 抓取/日志/DB 并存；SelfHealingPrefs 自愈删全局主键（爆炸半径全部加密存储）+明文兜底落盘；登出不清 aux 键（PKCE verifier 残留）；ImagesClient 下载重定向跨主机任意跟随（对比聊天线同主机限跳）；isOfficialOpenAI contains 判定；deeplink 参数无字符白名单；分享入站/日志出站均无体积上限；ProviderTransfer 凭据 base64 随包导出（iOS 冻结格式，UI 明示即可） |
| 流式/传输细节 | OpenAI 方言 index 缺省恒 0 并行调用拼坏 JSON；Responses added-无增量收尾空参；工具 id 清洗折叠碰撞面；SSE 不合并多行 data；Transcript 渲染 O(N²) 目录扫描；Responses 回退实例旗标与注释矛盾；TransportCall cancel 竞窗；qwenBudget 分支与注释相反返回全额；BundledSkillSeed 字符串序版本比较；MessagePreviews 图片链接清洗顺序错 |
| 学习/文档链 | learning_start 先持久化后 validate 留「运行中无任务」窗口；单坏回执使学习状态不可读且报裸 JSONException；moduleEditFingerprint 基于实体 toString 跨版本失配作废全部计划；DOCX 嵌套表格错乱；文档修订重复全量哈希（1MB 文档 ~80MB 哈希量） |
| 文档漂移 | ExchangeLab「仅供实验」注释 vs 规格自称唯一规范且是正式导出唯一通道；Theme.kt 注释论证 iOS 蓝代码却是绿；UI_CLEANUP_HANDOFF 待回填 |
| 测试卫生 | 环境变量门控活测试 CI 恒跳过（NOVEX_QA_REDUCTION）；2 个非测试 fixture 计入统计；UiMessagesSubListCmeTest 诚实的同义反复（文档价值非回归价值）；93 文件 runBlocking vs 11 文件 runTest 风格分裂 |

## 4. 与既有审计的增量

- **9-30 架构审计复核**：runtime→旧世界三跳链经复核已收敛为「有账本的受控环」（AgentForegroundService 是 64 行 Manifest 壳；TaskDoneNotifier 构造注入缝），降级为两处一行级改接（AgentKeepAlive 直连 crashguard：`AgentKeepAlive.kt:11-13`）+一处注入化（KeepAliveStatusLine as-cast MinisApp：`KeepAliveStatusLine.kt:117-121`）；`NovexMainDatabase` 进口旧实体的绞杀缝现状维持。ui/cards 取色飞地（P1-33）是审计未覆盖的新发现。
- **10-10 寻址审计交叉**：本审查在工具执行链侧独立复核了「收到错误令牌时的行为」——错误回执本身是正确的拒绝响应（各 Router 均如此），与该审计结论一致；但 Router 兜底误标（P1-5）会在存储故障时把模型引向错误自救方向，属其未覆盖面。
- **外部独立评审简报**：P0-2（指南矛盾）与简报任务 5「提示词—行为漂移」直接相关，且规模大于其已知的 browser_use/PROPOSE 案例，建议并入其证据池。

## 5. 好实践（防止只报忧）

- **测试面**：零 mock 框架、手写注入（`NovexModelStreamRecoveryTest` 的 `waitSecond = {}`）、真环境验证（本地 HttpServer 分块 SSE、子进程持文件锁、TemporaryFolder 重开存储）、虚拟时间看门狗、架构守护测试（源码扫描禁 import）、测试文件头写 SCOPE 边界。
- **幂等与恢复**：NovexToolResult 统一信封（ok/code/summary/allowed_values/next_actions）贯穿 11 工具零例外；CardStore 提交协议（revision 先落+head 原子发布+commitId 重试核对）；学习链「回执先于笔记落盘+accountFor 恰好一次」；执行计划内容寻址 ID+漂移确定性拦截；恢复从不自动重启付费任务。
- **并发器械**（chat 集群内）：streamJob stale 身份比对+完整事故注释；T145 同步 claim；dbWriteSerial「行复活」防护；纪元复验+install 对账回退；PreSendContract 影子装配双真相源。
- **事故档案文化**：几乎每个防御写法旁钉着编号案底（GH#147、T-android-safemode-lateinit-crash、Pixel 4a/6 重组事故、XIN 2026-06-12 序列、vivo 自由窗实录）——决策留痕密度是我方审查过安卓仓里的顶流；冻结面/迁移账本注释纪律（每个门面写明谁钉住了名字、为什么、何时拆）。
- **流式管线**：FlatChatItem 指纹 equals（把 3.7s equals 事故固化为类型设计）；rememberTranscriptRows 冻结前缀+80ms 采样+Default 调度+CME 防御注释；自适应节流阶梯。
- **取证能力闭环**：LaunchCycleBeacon 三分类归因→HangDetector 多帧采样双级断路→MemorySnapshot 内核 RSS 口径（保留误诊档案）→ModelRequestAudit 三段正则脱敏+「请求体绝不入档」红线→ProcessExitEvidence tmp+rename 原子取证。
- **规格-实现对**：card-format.md 是罕见的零漂移规格，且配往返对账测试。

## 6. 附录：方法与覆盖地图

- **覆盖**：12 分片 = chat ViewModel 集群 / chat Compose 层 / novex-core 模块 / conversation-runtime+core+model-transport / 持久化三层 / 工具执行权限链 / novex.android 数据侧 / novex.android 界面配套 / app-ui 非 chat / app 外围包 / 测试有效性 / 文档漂移与横切一致性。全仓 7 模块 1306 主源码文件均在射程内；深读密度按热点加权（chat 集群、工具链、持久化逐行；外围包抽样+全量结构扫描）。
- **证据纪律**：每条发现要求 文件:行号+代码摘录+证据深度标注（确证=读懂完整上下文 / 待核=模式线索）；主审对 4 条 P0 全部亲验，P1 抽验 8 条（ContentPaths、file_write 分发、标题静态、默认组劫持、导出 TODO、粘性重启、0 测试实例化、0 timedOut 生产者）全部属实。
- **已知局限**：纯静态（并发触发的实际频率、性能数字未运行验证）；审查期间在途改动可能使行号漂移；测试判定基于断言强度非执行结果；P2 台账为主题归并非逐条罗列（分片原始报告含全部条目）。
