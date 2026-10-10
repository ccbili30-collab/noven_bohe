# 模型寻址与快速演进债务审计（2026-10-10）

> 触发：①用户投诉「资料学习」弹窗自动弹出且关闭无效果；②外接会话导出 `_-880ea966`（deepseek-v4.1-flash），用户反馈"旧版能读卡建卡，新版不行"。
> 方法：只读静态审计 + 会话导出取证。本轮只记账，不改码。
> 分级：P0 = 功能不可用级；P1 = 每次改动都在付利息级；P2 = 记账级。
> 关联文件：`docs/ARCHITECTURE_QUALITY_AUDIT.md`（9-30 结构审计，本文不重复其内容）。

## 0. 结论一句话

**工具链的隐含契约是"调用方会逐字符回传服务端下发的令牌"——LLM 不是这种客户端。** 外接会话里该模型把所有 UUID（36 字符）和 64hex 全部替换为规范格式全零占位（`doccard-00000000-0000-4000-8000-000000000000`、`novex://documents/000…0`），却正确抄写了 20 位 hex 和普通路径段。这不是单个工具坏了，是整条寻址体系假设了一个不存在的保真度。

## 1. P0-类：寻址假设违反（模型抄不动不透明长串）

### 取证（导出 `_-880ea966`，会话 880ea966，28 条消息）

| 模型实发参数 | 真实值 | 结果 |
|---|---|---|
| `document_inspect` 的 `document_ref` | `novex://documents/000…0`（应为 `…e3852539…`） | document.not_found |
| `read_card`/`read_text_block` 的 `root_id`/`target_id` | `doccard-00000000-0000-4000-8000-000000000000` | 对象不在当前管理范围 |
| `workspace_read` 的 `workspace_ref` | `workspaces/00000000-…/branches/00000000-…/…` | not_found ×4 |
| `workspace_inspect` 返回值里的真实 ref | 下一轮被零化重发 | — |
| 同一路径中 20 位 hex `e741c83c01bf1135373a142a` | **抄写正确** | 阈值在 20–36 字符之间 |
| `workspace_search`/`skill_read`/`inspect_story_images`（可自造参数） | 正常 | 工具本身没坏 |

投递标签本身含真实编号（`<novex-document-card ref="…e3852539…" card="doccard-4941d60b-…">`，见 `AttachmentPromptMetadata.kt:74`）——**信息送到了，模型复述不出来**。所有失败回执都是正确的拒绝响应。

### 波及参数全表（凡"不透明长串须逐字符回传"者）

| 参数 | 出现面 | 串形态 | 严重度 |
|---|---|---|---|
| `root_id`/`target_id` | 全部卡工具（~19 处定义） | `doccard-<uuid>` | P0 |
| `draft_version` | 每次编辑+每次分页读取必传 | uuid/`saved:n` | P0 |
| `expected_sha256` | `workspace_edit` 必传 | 64hex | P0 |
| `workspace_ref` | workspace_read/edit/compute | 路径内嵌 2×UUID+64hex | P0 |
| `document_ref`/`collection_ref`/`note_ref` | document_*、learning_* | `novex://…/64hex` | P0 |
| `content_ref`/`next_cursor`/`preflight_id`/`proposal_id` | 分页游标与两阶段令牌 | base64/json 或 hash | P0 |
| `card_id`/`project_id`/`world_id`/`subject_id`/`source_id`/`reference_id`/`entry_id`/`image_id`/`resource_id`/`module_id`/`block_id`/`before_id`/`after_id` | NovexCardFileTools / Worldbook / Management / 卡编辑组 | 混合：编号可能短可读也可能 uuid | P1（须逐类核对实际形态） |
| `expected_revision`/`source_revision` | worldbook defaults/current | 修订串 | P1 |

模块编号当前形态安全（`mod-outline`、`mod-sec-3-1` 人类可读），但依赖约定而非约束——新卡生成器若换成 uuid 立即踩同类坑。**建议把"模型可见标识符 ≤16 字符、可朗读"写成系统不变量。**

### 解法方向（设计层，待拍板后实施）

1. **会话级别名寻址**：`卡1`/`文1`/`集1`/`仓N`，提示面与工具结果印代号、长 id 不下发；单一解析 chokepoint 包住所有路由。
2. **容错解析降级链**：别名 → 唯一前缀（git 式 ≥8）→ 名称命中 → 范围内唯一候选 → 最近操作对象；错误消息列出合法代号。
3. **乐观锁令牌弱化**：`draft_version`/`expected_sha256` 接受 `latest`/省略/前缀（模型是唯一写者，令牌防的是陈旧结构不是并发），回执注明"未校验版本"。

## 2. 「资料学习」管线考古与处置建议

### 设计动机（git 史）

- 9-05 `a1bb6210` 起建：工具契约+预检 → `60192b66` 批读核心 → `a357cf56` "确认后分批通读"（**原生确认 UI 是设计的一部分**）→ 10-02 `a45a6afd` 4.0.0 全自主底座时 `awaitCompletion`/模型自启动路径已并入。
- 设计目标：超出单轮预算的资料 → 分批模型通读 → 来源锚定笔记 + 总览 + 通读账本（read_coverage，防模型虚报"已读全文"）。

### 独有场景（卡片投递路径不覆盖）

1. **跨文档蒸馏**：多份巨型资料 → 一份综合笔记（卡路径是逐文档注入，不做综合）。
2. **联网采集**：`NovexMediaWikiSourceConnector` 支持 MediaWiki 站点发现/抓取入资料集——附件管线完全不覆盖。
3. **产物而非上下文**：笔记持久化、可分页回读、经 `prepareNovexLearningFiles` 发布成工作区文件供用户浏览导出。
4. **内部调用零工具依赖**：批读走 `provider.sendMessage`（`ChatViewModelLearningExt.kt:497-537`），不吃函数调用——对 DeepSeek 这类工具链弱的 provider 反而是**唯一确定能跑完的深读路径**。

### 结构性缺陷（与价值并存）

- **笔记不进上下文**：notes 只在 `learning_read` 工具结果里出现（`NovexLearningTools.kt`），材料流/上下文编排完全不消费——模型唯一能拿到笔记的通道恰是弱模型最弱的通道。
- **源文件进工作区依赖本管线**：外接会话里 `workspace_inspect` 能找到原件（`sources/learning/…/originals/…txt`）只因为用户点过资料文件入口触发 `prepareNovexLearningFiles` 投影（`ChatViewModelLearningExt.kt:363-389`）。**能力是否可用取决于用户是否点开过某个界面**——非确定性能力面。
- **弹窗机制**：任务一持久化即 `onState`→`_novexLearningTask`→模态（`ChatScreenOverlays.kt:386`）；`refreshNovexLearningTaskProjection` 在会话加载/每轮重建时调用，`restore()` 恢复一切非终态任务——**关闭=暂停（PAUSED 非终态）→ 下次复活**。只有"取消整理"进终态。
- **同意面**：`learning_start` 走 `novexToolExecution.execute` 全局门（APPROVAL 模式有逐项弹窗，FREE 直接跑）。FREE 模式下模型一句话烧 21 万词元无确认，而内部批读 prompt 自称"经过用户确认"（`NovexLearningPrompt.kt:9`）——文案在 FREE 模式下是谎言。
- **引导文本陈旧**：`NovexProductToolGuide.kt:43` 仍写"大量或超长资料先用 learning_prepare…learning_start"——卡投递上线后未更新，等于官方推荐把单文档阅读导入最贵路径。

### 处置建议（不急于拆）

引擎有独有场景（跨文档蒸馏、联网采集、通读账本、产物化），全删是丢资产。**建议处置：模型目录撤 `learning_start`/`learning_prepare`（或 learning_start 强制落入原生预检确认框而非直接执行）；进行中状态不再模态、改非阻断状态条；`NovexProductToolGuide` 的学习路由改写为"用户要求整理笔记/批量采集时"；引擎与详情 UI 保留为用户主动功能。**

## 3. P1-类：两阶段写入缝（commit 成功 + 二级登记失败 → 数据存在但不可达）

| 位置 | 缝 | 现状 |
|---|---|---|
| `IntegratedCards.execute` create→`updateBinding` | 卡已 saved 但 managed 未登记 → read 报越界 | **已修**（日志兜底 `sessionCreatedTargets`） |
| `ChatViewModelAttachmentExt.kt:425-451` doccard 物化→binding 写回 | 物化已 `store.save`，`novexSettingsStore.update` 失败 → **孤儿卡留在库里**（不在 backgrounds/managed/日志兜底范围——doccard 不走工具日志） | 未修（本次新引入，幂等重试可救但会留孤儿） |
| `runNovexLearning` saveCheckpoint：`repository.save` → `projection.publish`（`ChatViewModelLearningExt.kt:443-446`） | publish 抛异常 → 外层 catch 把任务置 PAUSED+记失败——**进度其实已落库** | 未修 |
| 卡激活后 `activatedKey/activatedModules` 回写（`ChatViewModel.kt:1489-1494`） | 激活完成但关闸标记未落 → 重进会话行为分叉 | 待详查 |
| 世界书/草稿 `applyDraftManagedSubjects`、图片 `mediaStore` 登记等 | 同型待逐点核对 | 待详查 |

## 4. P1-类：持久化状态 → UI 投影复活

- 学习任务：唯一一个**从持久态自恢复的模态**（`restore()` 捞回一切非终态任务）。关闭 dismiss=PAUSE 而非终态 → 必复活。其余 overlay（preflight/详情/存档/导出）均为显式打开，不随会话加载复活。同类扫描结论：本类当前仅此一例，但**"持久任务"清单要随新功能每次重查**。

## 5. P1-类：提示词承诺漂移

| 位置 | 承诺 | 实际 |
|---|---|---|
| `NovexLearningPrompt.kt:9` | "经过用户确认的资料通读" | FREE 模式模型自启动零确认 |
| `NovexProductToolGuide.kt:43` | 超长资料走 learning_prepare→start | 卡投递已是更便宜的主路径，指南未同步 |
| `NovexToolCatalog.kt:86` learning_start | "无文字口令或二次批准" | 与上条"经过用户确认"在同一子系统内自相矛盾 |
| `IntegratedCards` 管理目录提示 | "新建成功的卡片会加入当前管理范围" | 已修行为对齐；提示类承诺今后每次行为变更都需回扫 |

## 6. 结构债白话解释（双仓分叉）

- **发货线**：`bohe`（noven_bohe）仓库的 `main`/`next`——v4.0.x 全部正式与预览包从这里出，含岛、文游、卡工具、文档投递等全部功能线。
- **平行重构线**：`origin`（novex-android）的 `next`——净眼重构（ChatViewModel 14k 行拆扩展、批量重写），**领先/落后发货线约 733 个提交，且完全没有岛与文档投递等后来功能**。
- **白话**：同一套房子在两个工地各盖了一半，图纸已经不兼容。每个 bug 修复要打两遍或烂一边；时间越久合并成本指数增长，最终必然一选一。**严重度 P1 恶化级**——不立即致命，但每发一版都在加深。

## 7. 建议处置顺序

1. **本轮病灶修复**（待拍板后实施）：短代号寻址层 + 容错解析 + 版本令牌弱化；`learning_start` 撤出模型目录或落确认框；任务进行中不弹模态。
2. **回归测试**：把导出会话的全零参数串做成 fixture——修复前必败、修复后必过的端到端契约测试。
3. **孤儿卡清理**：doccard 物化绑定失败的孤儿行回收。
4. **提示词回扫**：凡承诺性行为文案与实现对账（一次专项）。
5. **双仓**：排期决策，不建议继续拖。
