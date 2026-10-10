# 独立评审上下文简报（2026-10-10）

> 用途：交给一个未参与本项目的 AI 审查者做独立审查。本文件刻意只给事实与地图，不给先前调查的结论——审查者应独立取证、独立下结论。若与既有判断相悖，以你找到的证据为准并明确指出分歧。

## 你将审查什么

Novex Android 仓，一次「整体链路 + 缺陷定位」级审查（重级）。仓库根：
`/Users/noven/Documents/Codex/CodexProduct/1/Novex-Development/ui-work`
Android 主模块在 `src/android/`（Kotlin 多模块：`novex-core`、`conversation-runtime`、`app` 等）。

产品形态：AI 对话应用。模型经工具循环调用本地工具（文档读取、卡片读写、会话工作区文件、资料学习等）。接入的模型能力参差：既有能稳定函数调用的强模型，也有经第三方中继接入的弱模型。

## 已观察到的用户症状（原始事实）

1. 有用户在聊天中突然看到「资料学习」模态弹窗（文案含"正在分批通读资料。输入词元 0/216610"），用户称未主动发起；关掉后感觉"没有影响"。
2. 一份外接会话导出位于 `/tmp/novex-feedback-880ea966/`（`session.json` + `messages.json`，共 28 条消息）。其中模型为 deepseek-v4.1-flash，存在多次工具调用失败。用户反馈"旧版本能正常调用工具、读卡建卡，新版本不行"。请逐条核对导出中模型发出的工具参数值形态与工具返回。
3. 本项目近期刚上线"附件文档分级投递"：小文档正文内联、大文档物化为会话资料卡（目录常驻+章节关键词注入+读卡工具回读），此前路径为"回执+模型主动调 document_inspect/document_read"。

## 代码地图（入口线索，不限于此）

- 工具目录：`src/android/novex-core/src/main/kotlin/novex/core/NovexToolCatalog.kt`
- 卡片工具协议/读取/协调：`src/android/conversation-runtime/src/main/kotlin/novex/runtime/`（`CardToolProtocol.kt`、`CardToolReader.kt`、`CardToolCoordinator.kt`、`CardEditingTools.kt`）
- 卡集成层与绑定：`src/android/app/src/main/java/com/openminis/app/cards/`（`IntegratedCards.kt`、`CardBinding`、`CardToolReader` 判定链）
- 附件投递：`app/.../ui/chat/AttachmentPromptMetadata.kt`、`ChatViewModelAttachmentExt.kt`、`cards/NovexDocumentAttachmentCard.kt`
- 学习管线：`novex-core/.../NovexLearning*.kt`（Session、ReviewRunner、Tools、ControlPolicy、Prompt、Preflight）、`app/.../ui/chat/ChatViewModelLearningExt.kt`、`NovexLearningWorkspaceBinding.kt`、`ui/chat/NovexLearningDetailsDialog.kt`、`ChatScreenOverlays.kt`
- 系统提示词工具指南：`app/src/main/java/novex/core/NovexProductToolGuide.kt`
- 执行权限门：`app/src/main/java/novex/core/NovexToolExecution.kt`、`NovexExecutionMode.kt`
- 其余工具组：`app/src/main/java/com/openminis/app/tools/`（Novex*Tools、File/Shell 工具等）

## 审查任务

1. **寻址鲁棒性**：枚举要求模型原样回传服务端生成的不透明标识符（ref/uuid/hash/cursor/版本令牌）的参数；判定每处收到错误/占位值时的行为；评估"模型能复述长串"这一假设在全工具面的依赖深度。
2. **学习任务生命周期**：触发→持久化→UI 投影→关闭/暂停/取消→重启/重进恢复。回答：弹窗为何出现、为何"关掉没影响"、关闭实际做了什么、什么条件下会再弹。
3. **同意与计费边界**：会发起后续付费调用的工具（如 learning_start）在各执行模式下的实际门槛；提示词/回执中关于"用户确认"的表述与真实行为是否一致。
4. **两阶段持久化**：凡"主存储写成功后再更新二级索引/绑定/投影"的点，二级写失败后数据的可达性。已知点位（须独立验证其现状，且自行再找同类）：建卡→管理范围登记；附件物化→会话绑定写回；学习 checkpoint→工作区投影。
5. **提示词—行为漂移**：工具描述/指南中可被证伪的承诺。
6. **附带发现**：途中遇到的任何其他缺陷，记账并给严重度。

## 输出要求

- 开头两段：①任务理解（交付物一句话）；②你的假设与可能偏差。
- 正文按缺陷条目：现象/证据（文件:行号）/根因/严重度（P0 功能不可用、P1 持续付息、P2 记账）/建议方向。
- 与已观察症状相符或相悖都要写明证据链。全程只读，不修改任何文件。
- 参考：仓库评审协议 `docs/review/README.md`；先前审计报告 `docs/diagnostics/2026-10-10-model-addressing-debt-audit.md`（注意：该报告是待你独立核对的既有结论，不要预设其正确）。
