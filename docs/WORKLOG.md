# Novex 工作表

> **用途**：随意派活、有序归位。你想到哪做到哪；AI 干完活自己把留痕填到该在的位置。
> **双视角**：人看（进度/状态一目了然）+ AI 看（换会话后靠本表恢复上下文）。
> **维护协议（给所有执行者：AI/子 agent/人）**：
> 1. 接到新任务 → 在「当前流水」顶部登记一行，状态=进行中
> 2. 完成/验证后 → 回填结果（改动文件/验证方式/产物），状态流转
> 3. 跨领域任务 → 流水一行 + 在涉及的每个「工作部分」小节各挂一行指向流水编号（如 `→T007`）
> 4. 动工前 → 先读「冻结面与雷区」
> 5. 已完结任务 → 从流水挪入「归档」，领域树里的指向行保留

---

## 1. 当前流水（倒序，最新在上）

| 编号 | 日期 | 任务 | 状态 | 涉及领域 |
|---|---|---|---|---|
| T007 | 2026-10-02 | 4.0 切换 + noven_bohe 开仓 + 4.0.0-beta.2 发布 | ✅ 已完结→归档 | 2.1 / 2.3 / 2.5 |
| T006 | 2026-10-02 | 法律审计 C1-C3 条件修复 | ✅ 已完结→归档 | 2.2 / 2.5 |
| T005 | 2026-10-02 | P4 骨架五件收口 + CI 崩溃根因修复 | ✅ 已完结→归档 | 2.2 / 2.3 |

## 2. 领域树（工作部分）

### 2.1 应用前端（UI）
> 消费 Compose 界面层。改这里的纪律：界面行为零漂移，行为钉测试兜底。
> 子域：屏幕与导航 / 聊天界面 / 卡片创作 / 工作台 / 设置页 / 落地页与欢迎流

| 日期 | 条目 | 流水 |
|---|---|---|
| 2026-10-02 | 落地页去上游引导：移除「Star Minis」按钮、致谢文案改中性（中英双语 strings + SessionListLanding.kt） | →T007 |
| 2026-10-02 | beta.110/111 含 UI 层血统清洗收口（PR#82 的 33 件含 15 个 UI 件真重写） | →T005 前序 |

### 2.2 应用后端（核心）
> 传输层 / 思考座次 / 数据层(Room) / 运行时 / 工具层。改这里前必查 §3 冻结面。

| 日期 | 条目 | 流水 |
|---|---|---|
| 2026-10-02 | FileBrowserViewModel 调度器可注入（修 CI 执行器崩溃：排除实验实锤「真实 IO+轮询」组合；顺修构造期重入 NPE——nameOrder 改函数消除初始化顺序依赖） | →T005 |
| 2026-10-02 | 五件真衍生候选重写入基本自有档：StreamingFade 64.4→34.5 / SessionListViewModel 52.4→30.0 / MarkdownParser 44.0→20.0 / FileMentionIndex 53.7→34.5 / ImageBudget 54.6→31.6；52 例行为钉零改动全绿 | →T006 |

### 2.3 发布与分发
> 构建管线 / 签名 / 发布站（Gitee+GitHub 双源）/ 公告 / update.json / CI 门控。

| 日期 | 条目 | 流水 |
|---|---|---|
| 2026-10-02 | **4.0.0-beta.2 发布**（首个新仓自产包）：签名指纹与历史一致（cab4226b，老用户原地升级）；SHA256 三方一致；update.json preview 通道已指 4.0；公告「全新自主底座」推荐升级 | →T007 |
| 2026-10-02 | **签名链收口**：密钥真相=Windows 远端机 debug.keystore（指纹比对破案）；三处落位——本地 `Novex-Development/local/keys/novex-release-signing.jks`（600）+ macOS 钥匙串（服务名 novex-release-signing）+ 新仓 secrets 四件。⚠️ 待用户异地备份 | →T007 |
| 2026-10-02 | beta.109（智谱开箱即用）/ beta.110（清洗收官）/ beta.111（合规收口）三版发布，全链验证 | →T005/006/007 前序 |
| 2026-10-02 | CI 崩溃定位方法论沉淀：方法级→类级探针→排除实验三步定位（FileBrowserViewModelTest 实锤） | →T005 |

### 2.4 供应商接入
> 官方通道（智谱/DeepSeek/自定义）+ 任意 OpenAI 兼容端点 + 思考座次表。

| 日期 | 条目 | 流水 |
|---|---|---|
| 2026-10-02 | 三卡引导上线（PR#83）：智谱预设（v4 接口/静态目录 glm-5.3 系/思考席位 enabled+low 模拟关闭）、DeepSeek 预设、自定义（小鸟 logo）；净眼拦下中转 o 系/gpt-5 token 键回归（PR#63 事故形态）——模型族信号补发堵漏 | →T005 前序 |

### 2.5 基建与合规
> 仓库管理 / 许可 / 审计 / CI / 文档。

| 日期 | 条目 | 流水 |
|---|---|---|
| 2026-10-02 | **noven_bohe 开仓**（私有，首提交=清洗树快照+4.0 切换件）；旧仓 novex-android 永久归档（只读，GPL 存量义务）；hub 补历史源码指引；审计报告存档 `docs/LEGAL_AUDIT_2026-10.md` | →T007 |
| 2026-10-02 | 法律审计 PASS with conditions；C1-C3 全部清账（五件重写/Apache+MIT 许可随包入 APK/TPL 补自研声明）；半血终态 79 件三分类记档 | →T006 |
| 2026-10-02 | Apache-2.0 全文 + MIT 参考文本 + NOTICE 随包（assets/third_party_licenses/，真实分发物验证入包） | →T006 |

## 3. 冻结面与雷区（动工前必读）

| 类别 | 内容 | 后果 |
|---|---|---|
| 包名/namespace | `com.openminis.app` 不可改（JNI 符号 `Java_com_openminis_app_crash_…`、ACRA SPI、Manifest 组件链、437 个文件路径） | 改=全仓编译崩+老用户升级断 |
| Room schema | 表/列名、identityHash 字节不变（老库零迁移） | 改=用户数据丢 |
| prefs/JSON 契约 | 全部 prefs 名键、env-vars.json/models-cache/文件名格式 | 改=用户配置丢 |
| /var/minis 路径 | ContentPaths 布局与 SKILL.md 内路径 | 改=agent 文件操作断 |
| 签名 | 密钥在 local/keys（密码在钥匙串 novex-release-signing）；指纹 cab4226b | 丢=所有老用户无法升级 |
| 合并纪律 | 任何 PR：净眼 pass + **CI checks=pass 显式核实**才可合并；任务分支不直推 next/main | 违=红提交进主干（有前科） |
| KaTeX 注入协议 | renderMath 四参串+AndroidBridge.onRendered+katex-render.html | 改=公式渲染断 |
| LLMProvider 签名面 | 接口成员 token 级冻结（~290 消费方） | 改=全仓改接 |

## 4. 挂账池（待办，收编自 todo）

| 编号 | 日期 | 事项 | 领域 | 状态 |
|---|---|---|---|---|
| P-01 | 2026-10-02 | LICENSE 公司主体名占位待补（用户给全名即填） | 2.5 | 待用户输入 |
| P-02 | 2026-10-02 | keystore 异地备份（2.6KB，用户侧动作） | 2.3 | 待用户 |
| P-03 | 2026-10-02 | 法务书面认可审计报告+半血三分类 | 2.5 | 待用户 |
| P-04 | 历史遗留 | LegacyUiBridge 拆除（她的 25 处旧 import 改接后） | 2.1 | 待排期 |
| P-05 | 历史遗留 | SkillRepository.setSessionOverride 孤儿清理 | 2.2 | 待排期 |
| P-06 | 历史遗留 | 39 个孤儿 string 键清理 | 2.1 | 待排期 |
| P-07 | 历史遗留 | 六语言新键本地化（zh 已有，其余五语言待补） | 2.1 | 待排期 |
| P-08 | 历史遗留 | C 类三问号（悬浮胶囊/崩溃风暴/models.dev 保留与否） | 2.2 | 待用户裁决 |
| P-09 | 历史遗留 | D11+D19 锁窗口优化 | 2.2 | 待排期 |

## 5. 归档（已完结任务详情）

### T007 · 4.0 切换 + noven_bohe 开仓 + 4.0.0-beta.2 发布（2026-10-02）
- **切换件**：版本默认 4.0.0 / 专有 LICENSE（主体占位）/ README 全自主口径+历史源码指引 / 落地页去上游引导
- **新仓**：github.com/ccbili30-collab/noven_bohe（私有）；首提交=树快照（无历史，源 next@c3fa7700+切换件）；next+main 双分支；CI 全量绿（首跑红=根提交无 HEAD^，一次性）
- **签名**：密钥破案（=远端 debug.keystore）→ 三处落位 → 新仓出包指纹一致
- **发布**：4.0.0-beta.2（Gitee Release/SHA256 三方/公告双端/update.json）+ APK 交付用户
- 验证：新仓 fast CI success / apksigner 指纹核对 / 全链发布核对清单

### T006 · 法律审计条件轮 C1-C3（2026-10-02）
- 五件真衍生重写（数字见 2.2）+ Apache/MIT/NOTICE 随包 + TPL 自研声明；净眼 pass 高置信 + CI 绿 + 门控合并（PR#86，next=c3fa7700）
- beta.111 合规收口版发布

### T005 · P4 骨架收口 + CI 崩溃修复（2026-10-02）
- 五件半血骨架重写：MainActivity 62.9→38.6 / MinisApp 47.5→34.9 / LLMProvider 52.5→38.2（签名面 token 级不动）/ FileBrowserViewModel 61.0→36.6 / MinisTextKitSelection 60.5→30.2；Manifest/初始化序/Room 零触碰（净眼逐项验证）
- THIRD_PARTY_LICENSES.md 重写为实际依赖
- CI 崩溃：探针定位→排除实验→调度器注入修复（详见 2.2/2.3）
- PR#85 门控合并；beta.110 发布
