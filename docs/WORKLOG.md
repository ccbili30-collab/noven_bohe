# Novex 工作表

> **定位**：表=记忆本体，对话=临时界面。聊完可丢，沉淀全在这。
> **分工哲学**：按任务纵向切（一个任务贯通前后端），不按职能横切。多 agent 并行，各自 worktree，门控合并。

## 0. 使用协议

**给你（派活方）**：
- 想到哪说到哪，不需要结构——AI 负责把话翻译成任务条目并跟你确认
- 想并行：说「这几件事各开一个 agent」即可
- 打开表只看三处：①「决策待办」（只有你能拍板的事）②「当前流水」（正在发生什么）③「挂账池」（欠账）

**给执行者（AI/子 agent，含新会话冷启动）**：
1. **冷启动**：接到任务编号 → 读该条目（自包含任务书）→ 读 §4 冻结面 → 查 §2 活跃区确认文件不冲突 → 开工。不需要追问背景，条目即全部上下文；若条目信息不足以开工，先把缺口补进条目再动
2. **开工时**：流水登记（状态=进行中）+ 活跃区登记将要动的文件/目录
3. **完成时**：回填结果（改动文件/验证方式/产物链接），活跃区清除，状态流转；「完成」的定义包含回填本表
4. **跨领域任务**：流水一行 + 各领域小节挂指向行
5. **完结任务**：流水移入按版本归档文件，领域树指向行保留

**任务条目模板（自包含任务书字段）**：
```
### T0XX · 标题（日期）
- 背景：为什么做（一段话，含前置依赖）
- 范围：动什么、不动什么（涉及文件/目录 + 明确排除项）
- 验收：怎样算完（测试/审查/验证方式，明确到可执行）
- 冻结面引用：本任务触碰的 §4 条目
- 领域：§x.x
- 执行记录：worktree/分支/PR/验证结果/留痕
```

---

## 1. 当前流水（只放进行中；完结即归档）

| 编号 | 日期 | 任务 | 状态 | 领域 |
|---|---|---|---|---|
| T008 | 2026-10-07 | 卡类编辑器 1:1 复刻（阅读器主页/设定总览/注入管理/新模块/AI 修订待审；设计稿+规格见仓库外 design/card-ui-2026-10/） | P1+P2 完成·模拟器实机验证通过·待净眼+CI | 3.1/3.2 |

## 2. 活跃区（并发协调板：进行中任务的文件占用）

| 任务 | 执行者 | 占用文件/目录 | 分支/worktree |
|---|---|---|---|
| T008 卡类编辑器复刻 | ZCode | novex/android/ui/cards/*、novex/content(ContentModule/Document)*、novex/android/ui/Novex* 组件、NavCardDestinations.kt、ReadingViewPrefs.kt（注：ui-work 树另有在途改动占用 AgentTools/CharacterModels/strings.xml/ChatScreen 域，T008 避开，合并时协调） | feat/card-lore-editor @ worktrees/tasks/card-lore |

> 派活前查此表；占用冲突时改派其他文件域或排队。

## 3. 领域树（事后归位索引；子域映射源码路径）

### 3.1 应用前端（UI）— `com.openminis.app.ui.*`
> 纪律：界面行为零漂移，行为钉测试兜底；UI 大改走独立分支（她的战场习惯）。
> 子域：屏幕与导航(`ui/navigation`) / 聊天界面(`ui/chat`) / 卡片创作(`ui/cards`) / 工作台(`ui/workbench`*,待建) / 设置页(`ui/settings`) / 落地页(`ui/sessions`) / Markdown 渲染(`ui/markdown`)

| 日期 | 条目 | 流水 |
|---|---|---|
| 2026-10-02 | 落地页去上游引导（Star Minis 按钮移除、致谢改中性，中英 strings + SessionListLanding.kt） | →T007 |

### 3.2 应用后端（核心）— `novex.android.*` + `provider`/`tools`
> 纪律：动工前必读 §4；接口签名面 token 级冻结。
> 子域：传输层(`novex/android/transport` + `model-transport`) / 思考座次(`novex/android/thinking`) / 数据层(`novex/android/data` + Room) / 运行时(`novex/android/runtime`) / 工具层(`tools`) / 仓库层(`novex/android/repo`)

| 日期 | 条目 | 流水 |
|---|---|---|
| 2026-10-02 | FileBrowserViewModel 调度器可注入（CI 崩溃根因修复+构造期重入 NPE） | →T005 |
| 2026-10-02 | 五件真衍生重写入基本自有档（34.5/30.0/20.0/34.5/31.6） | →T006 |

### 3.3 发布与分发 — workflows + hub 仓 + `local/keys`
> 纪律：出包必验签名指纹（cab4226b）+ SHA256 三方；预览版公告带警告（清洗期）/ 推荐措辞（功能版）。
> 子域：构建管线(`.github/workflows`) / 签名(`local/keys`+secrets) / 发布站(hub: gitee+github 双源) / 公告与 update.json

| 日期 | 条目 | 流水 |
|---|---|---|
| 2026-10-02 | 4.0.0-beta.2 发布（首个新仓自产包，签名一致老用户原地升级） | →T007 |
| 2026-10-02 | 签名链收口（密钥=远端 debug.keystore 破案；本地/钥匙串/新仓 secrets 三处落位） | →T007 |
| 2026-10-02 | CI 崩溃定位方法论（方法级→类级探针→排除实验） | →T005 |

### 3.4 供应商接入 — `NovexProviderPresets` + transport 方言
> 子域：官方通道(智谱/DeepSeek/自定义) / 兼容端点 / 思考席位

| 日期 | 条目 | 流水 |
|---|---|---|
| 2026-10-02 | 三卡引导上线；净眼拦下中转 o 系 token 键回归（模型族信号补发） | →T005 前序 |

### 3.5 基建与合规 — 仓库 / 许可 / 审计 / 文档
> 子域：仓库管理 / 许可合规 / 审计工具(`scripts/upstream_audit.py`) / 文档(`docs/`)

| 日期 | 条目 | 流水 |
|---|---|---|
| 2026-10-02 | noven_bohe 开仓（私有）；旧仓永久归档；hub 源码指引；审计报告存档 docs/LEGAL_AUDIT_2026-10.md | →T007 |
| 2026-10-02 | 法律审计 PASS with conditions；C1-C3 清账；Apache/MIT/NOTICE 随包 | →T006 |

## 4. 冻结面与雷区（动工前必读）

| 类别 | 内容 | 后果 |
|---|---|---|
| 包名/namespace | `com.openminis.app` 不可改（JNI 符号/ACRA SPI/Manifest/437 文件路径） | 改=编译崩+升级断 |
| Room schema | 表/列名、identityHash 字节不变 | 改=用户数据丢 |
| prefs/JSON 契约 | 全部 prefs 名键、env-vars.json/models-cache/文件名格式 | 改=用户配置丢 |
| /var/minis 路径 | ContentPaths 布局与 SKILL.md 内路径 | 改=agent 文件操作断 |
| 签名 | 密钥 `local/keys/`（密码在钥匙串 novex-release-signing）；指纹 cab4226b | 丢=老用户全断 |
| 合并纪律 | PR 必须：净眼 pass + **CI checks=pass 显式核实**；任务分支不直推 next/main | 违=红提交进主干 |
| KaTeX 注入协议 | renderMath 四参串 + AndroidBridge.onRendered + katex-render.html | 改=公式渲染断 |
| LLMProvider 签名面 | 接口成员 token 级冻结（~290 消费方） | 改=全仓改接 |

## 5. 决策待办（只有你能拍板的事）

| 编号 | 日期 | 事项 | 影响领域 |
|---|---|---|---|
| D-01 | 2026-10-02 | LICENSE 公司主体名（给全名即填，2 分钟） | 3.5 |
| D-02 | 2026-10-02 | keystore 异地备份（2.6KB 一个文件，拷到加密 U 盘/公司网盘） | 3.3 |
| D-03 | 2026-10-02 | 法务书面认可审计报告+半血三分类（新仓 docs/LEGAL_AUDIT_2026-10.md） | 3.5 |
| D-04 | 历史遗留 | C 类三问号：悬浮胶囊 / 崩溃风暴页 / models.dev 数据保留与否 | 3.2 |

## 6. 挂账池（工程待办，AI 可主动建议排序）

| 编号 | 日期 | 事项 | 领域 | 前置条件 |
|---|---|---|---|---|
| P-04 | 历史遗留 | LegacyUiBridge 拆除 | 3.1 | 她 25 处旧 import 改接完成（**体检项：确认进度**） |
| P-05 | 历史遗留 | SkillRepository.setSessionOverride 孤儿清理 | 3.2 | 无 |
| P-06 | 历史遗留 | 39 个孤儿 string 键清理 | 3.1 | P-04 同期做省一轮 |
| P-07 | 历史遗留 | 六语言新键本地化 | 3.1 | 用户裁定文案口径 |
| P-09 | 历史遗留 | D11+D19 锁窗口优化 | 3.2 | 无 |

> **挂账体检纪律**：每版本发布前过一遍——前置条件还成立吗？代码漂移了吗？不成立的重新定性或关闭。

## 7. 归档索引

- `docs/WORKLOG-ARCHIVE-4.0.0-pre.md` — T005-T007（清洗收官/审计条件/4.0 切换发布三役，2026-10-02）

---

### T008 · 卡类编辑器 1:1 复刻（2026-10-07）
- 背景：对标特盐级 OC/世界观档案功能（功能全要、界面自有设计）。核心三层架构：素材层（模块全量，可几十万字）→ 提纯层（设定总览=默认喂 AI）→ 注入层（模块自定义注入）+ AI 反向编辑（一切 AI 写入先进修订待审，采纳才落档）。设计稿 15 张+交互规格：Novex-Development/design/card-ui-2026-10/（仓库外，勿提交）。
- 范围：A 线集成卡（novex/android/ui/cards + novex/content 文件存储）；阅读器主页（头图+模块 tab+HorizontalPager+总览第一页+页码点+连续阅读开关）；模块管理/添加抽屉；设定总览（数据+AI 提纯+diff）；注入管理页；新模块类型（关系/轨迹/多形态完整版/力量体系/宗教政治/文化美学等）编辑器；AI 修订待审流。**不动**：B 线 Room content_modules、LLMProvider、包名、/var/minis、KaTeX、签名链；避开 ui-work 在途文件（AgentTools.kt/CharacterModels.kt/strings.xml/ChatScreen*）。
- 验收：模拟器截图逐页对照设计稿（visual-frontend-replica 方法：≥2 轮修正）；行为钉测试；CI checks=pass；净眼 pass 后方可合并。
- 冻结面引用：§4 全表（尤其 prefs 只增键、导出契约、合并纪律）。
- 领域：3.1 应用前端 / 3.2 应用后端
- 执行记录：worktree worktrees/tasks/card-lore，分支 feat/card-lore-editor（源 bohe/next@553f5adf）。
  - 575da09c 任务书登记；ddb18422 P1 阅读器（总览钉首页/页码点/FAB/⋯面板）；5f85acbe P2 总览（SetExtension 扩展槽+提纯待审链路+总览页）；daf1f37c 注入管理页；ee12b083 页铬叠钮修复（模拟器实测发现）。
  - 验证：:content-storage/:content-core 测试绿（含扩展槽行为钉）；:app NovexCard* 绿；模拟器(emulator-5554)实机走查 阅读器/编辑总览/保存流/模块面板/总览页/注入管理页 全通过，截图 design/card-ui-2026-10/shots/08~12。
  - 遗留：①tab 条 ⋯ 在 cards 域已接（onManageModules=面板）；完整重排/显隐抽屉待 P3；②提纯链路已就绪但模拟器无供应商未跑 LLM 实调；③发现 App 存在 A/B 两线卡系统（首页空态新建=A线集成卡；另有路径产出 B 线目录卡走旧 UI）——归属与统一是产品决策，已在会话记录。
