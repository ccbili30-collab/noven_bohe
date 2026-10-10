# 任务：模型寻址加固 + 学习管线收编（2026-10-10）

> 依据：`docs/diagnostics/2026-10-10-model-addressing-debt-audit.md`（证据与分级全表）+ 外接会话导出 `_-880ea966` 取证。
> 已定产品决策（用户拍板）：①学习管线保留，弹窗改为确认后后台静默跑、不干扰用户；②`novex-android` 仓 next 线已报废，发货只用出货仓（bohe），不存在合并债。
> 本轮性质：P0 病灶修复。审计清单只查不改的部分另行消化。

## 问题总账（合并版）

| # | 问题 | 级别 | 证据 |
|---|---|---|---|
| 1 | 全工具面依赖"模型逐字符复述不透明长串"：uuid/64hex/sha/cursor 一族参数在弱模型上系统性失败（全零占位实证） | P0 | 导出会话 msg[1/3/5/9/14/20-26]；审计 §1 |
| 2 | `draft_version`/`expected_sha256` 等乐观锁令牌每次编辑必回传，弱模型下编辑面实质不可用 | P0 | `CardToolProtocol.kt` 各 readTool/tool 定义 |
| 3 | `learning_start` 模型可自启动付费批读；FREE 模式零确认；指南仍把超长资料引向学习 | P0 | `NovexProductToolGuide.kt:43`、`NovexToolCatalog.kt:86`、`NovexExecutionMode.kt` |
| 4 | 学习任务模态弹窗打断对话；关闭=暂停（非终态）→ 重进会话复活 | P0 | `ChatScreenOverlays.kt:386`、`NovexLearningSession.restore`、`ChatViewModelLearningExt.kt:540` |
| 5 | 附件物化卡绑定写回失败 → 孤儿卡（不进日志兜底） | P1 | `ChatViewModelAttachmentExt.kt:425-451` |
| 6 | learning `saveCheckpoint` 内 save→publish 两步：publish 失败会把已落库进度的任务标暂停 | P1 | `ChatViewModelLearningExt.kt:443-446` |
| 7 | 学习笔记不进上下文，唯一消费口 `learning_read` 是弱模型最弱的通道 | P1 | `NovexLearningTools.kt`；材料流无 notes 消费 |
| 8 | 源文件进工作区依赖学习投影路径，能力可用性取决于用户是否点开过特定界面 | P1 | `prepareNovexLearningFiles`、`NovexLearningWorkspaceBinding.kt` |
| 9 | 内部批读 prompt 自称"经过用户确认"——FREE 模式下为假 | P1 | `NovexLearningPrompt.kt:9` |
| 10 | 双栈卡工具并存：专用族（`create_world`/`novex_write_module`，`card_id`/`module_id`）与通用族（`create_card`/`read_card`，`root_id`/`target_id`）两套寻址 | P1 | `NovexCardFileTools.kt` vs `CardToolProtocol.kt` |
| 11 | `novex-android` 仓 next 重构线（733 提交分叉、无岛无文档投递）已报废——只剩确认标注，无合并工作 | P2 | git 取证（bohe main vs origin next） |
| 12 | **重载丢能力（独立评审新增）**：`novexDocumentRefsInPrompt` 只在含 `<novex-document-receipts>` 时解析 ref——CARD/INLINE 文档重进会话后 `activeNovexDocumentRefs` 为空 → 文档工具下线，快照还在库里却 `not_found` | P0 | `AttachmentPromptMetadata.kt:101-106` + `ChatViewModelSessionExt.kt:596` |
| 13 | **学习续跑饿读（同上）**：重载后 collection ref 恢复但 doc ref 丢失 → `readSource` 拿不到来源 → 恢复的任务 REVIEWING 但词元恒为 0——与截图"0/216610"吻合 | P0 | `ChatViewModel.kt:1131-1136` + #12 |
| 14 | workspace 报错文案指路已退役的 `file_read`（死胡同指引） | P1 | `NovexConversationWorkspace.kt:112` vs `AgentTools.kt:37` |
| 15 | `cardBindingJson` 在 `saveConversationSettings` 的 `ifBlank` 重建路径下可被静默丢掉（仅当 baseline 非空时才受冲突校验保护） | P1 | `ChatViewModel.kt:4171-4181` + `NovexConversationSettingsStore.kt:60-88` |
| 16 | 投递标签只暴露 `card=` 属性，与工具参数（`root_id`/`target_id`）无映射说明——弱模型只能猜造编号 | P1 | `AttachmentPromptMetadata.kt:74-80` |

## 修复方案（分层）

### 层 A：寻址加固（病灶切除）

**A1 会话级别名注册表 + 统一解析 chokepoint**
- 每会话对模型可见实体发短代号：`卡N`（卡/目标）、`文N`（文档）、`集N`（资料集）、`仓N`（工作区文件）、`图N`（图片）、`块N`/`模块N`（卡内）。
- 下发面（标签 `card=`、管理目录、工具结果里的 id 字段、错误信息）印代号；长 id 不下发或少下发。
- 解析集中在单个 resolver，包住现有各路由：参数先过 resolver（代号→真 id），再进原逻辑；代号表随会话持久化（重进会话代号不漂移）。

**A2 容错降级链（resolver 内部）**
收到非代号值时依次试：全等 id → 唯一前缀（≥8 位，git 式）→ 标题/名称命中 → 范围内唯一候选 → 本会话最近创建/读取对象。全部落空 → 错误信息列出合法代号清单（不是干巴巴的"不在范围"）。

**A3 乐观锁令牌弱化**
- `draft_version`：接受 `latest`/省略/无法解析 → 用当前版本执行，回执注明"本次未校验版本"（模型是唯一写者，令牌防陈旧知识不防并发；防陈旧交给"版本不符→重读重试"循环）。
- `expected_sha256`/`content_ref`/`cursor`：接受唯一前缀；无法解析时给最新值+告警而非死路。
- 保留：校验值**格式**合法但语义过期时仍报冲突（防覆盖）。

### 层 B：学习管线收编（保留引擎、收窄角色）

**B1 触发收编**：`learning_start`/`learning_prepare` 从模型工具目录移除；保留用户主动入口（资料详情/附件菜单"整理成笔记"）与 `learning_read`（已存在笔记的回读）。模型侧指南同步改写（超长资料→卡投递已覆盖；整理笔记由用户发起）。

**B2 弹窗降噪（用户已拍板方向）**：任务进度不再模态。
- 进行中/暂停等非决策状态 → 聊天区非阻断状态条（可点开详情、可暂停/取消）；
- 需决策态（预算到达/部分失败/完成）→ 一次性可关闭提示；
- "关闭状态条"≠暂停——关闭只隐藏 UI，任务继续；要停用显式"暂停/取消"。**修正当前 dismiss=暂停的隐性语义。**
- `restore()` 恢复的非终态任务只进状态条，不弹模态。

**B3 消费通道补齐（可选，往后排）**：学习笔记挂成资料集级材料（关键词/常驻注入），否则产物只能经工具回读——弱模型吃不到。

### 层 C：持久化缝与一致性（小修）

- **C1** doccard 物化：binding 写回失败时把孤儿卡纳入"本会话资料卡"持久登记（复用会话日志兜底思路或登记簿），重试幂等已有。
- **C2** learning `saveCheckpoint`：publish 失败降级为警告记日志，不再把已落库进度的任务标暂停。
- **C3** 提示词承诺回扫：批读 system 的"经过用户确认"按实际权限改述；扫描其余"承诺型文案"与实现对账。
- **C4** 重载能力恢复：`novexDocumentRefsInPrompt` 放开 receipts 门禁（正则已校验 ref 格式，直接对 inline/card 标签同样解析）——修问题 #12/#13。
- **C5** workspace 报错文案改指真实可用工具；`CardToolReader` 在 root 不存在时也列出可用 `root_id→卡名` 清单（与既有"目标列表"提示同构）。
- **C6** `saveConversationSettings` 空白 `novexConfigurationJson` 路径保留 `cardBindingJson`（防静默丢绑定）。

### 层 D：验证与周边

- **D1 弱模型回放测试**：导出会话的全零参数串做 fixture → 契约测试（修复前全失败、修复后全通过）；覆盖卡读/文档读/工作区读三条链。
- **D2** `docs/diagnostics` 审计文档归档维持；`novex-android` 仓在发货文档里标注"已报废，发货线为 bohe 仓"，不再做任何合并。
- **D3** 发布：P0 修复走既定预览→正式链。

## 任务编排建议

```
T1（A1+A2 别名与解析层）——核心，先行；改动集中在 resolver+各提示面
T2（A3 版本令牌弱化）——依赖 T1 的 resolver 落点
T3（B1+B2 学习收编+弹窗降噪）——独立成线，可与 T1 并行
T4（C1+C2 持久化缝）——小修，顺手
T5（C3 承诺回扫）——文档级
T6（D1 弱模型回放契约测试）——随 T1/T2 完成补
T7（D2 标注 + D3 发布）
```

## 待定项（执行前需再确认的小点）

- 代号空间是否全局唯一还是按类型分桶（建议分桶：`卡1`/`文1` 比 `C1`/`D1` 对中文模型更友好）
- `learning_read` 是否保留在模型目录（建议保留：读已存在笔记是只读且便宜）
- 非阻断状态条挂在聊天顶部横幅还是输入框上方（UI 细节，实施时按设计系统定）
