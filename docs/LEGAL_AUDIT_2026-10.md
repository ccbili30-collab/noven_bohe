# Novex「上游出局」法律工程终验报告（存档）

- 审计基线：next @ c3fa7700（= noven_bohe 首提交的源树）
- 上游基线：旧仓根提交 82c2eb0（OpenMinis 整包导入）
- 审计性质：工程级自检（独立审计 agent 干净上下文取证），供公司法务终审；
  全部数字为审计时实测，非汇报采信。
- 审计时间：2026-10-02

## 总裁决：PASS with conditions

3.x 全源公开口径下「上游出局」达标；4.0 闭源口径的四条件中
C1-C3 已在本仓首提交前完成，C4 由本仓的存在形式与旧仓归档履行。

## 一、血统终验（实测数字）

| 指标 | 数值 |
|---|---|
| 上游未动桶 | 0 件 0 行 |
| 死代码 | 0 件 |
| 相似度 ≥80% | 仅 service/ToolOutcome.kt（100%，22 行；五枚举常量名+次序即跨层 FQN 事实面，P3.5b 记档） |
| 半血档 40-80% | 79 件 / 15,665 行（C1 轮后；三分类记档见 docs/UPSTREAM_EXIT_PLAN.md） |
| 基本自有 <40% | 85 件 / 26,915 行 |

半血档三分类：①冻结面/契约/文案主导 37 件；②语言/API 样板主导 42 件；
③真衍生候选 5 件——**已全部在 C1 轮真重写压入基本自有档**
（StreamingFade 64.4→34.5、SessionListViewModel 52.4→30.0、MarkdownParser
44.0→20.0、FileMentionIndex 53.7→34.5、ImageBudget 54.6→31.6）。

## 二、GPL 分发物与存量义务

- 含 GPL 的已分发版本：v3.0.0～v3.0.5 + beta.95/96/97（tag 树逐个实测，
  jniLibs 含 libproot-loader*.so）；**beta.98 起至今 jniLibs=0，GPL=0**。
- 源码可得性：旧仓 novex-android 公开（GPL-3.0，544 commits，git 历史
  含全部上游源码与构建脚本）——历史版本接收者可取得完整对应源码，
  义务履行中。**旧仓永久归档保留（只读），不可撤**。
- 4.0 起闭源分发无新增 GPL 义务（分发物 GPL=0，代码面经半血件三分类
  甄别 + C1 轮重写）。

## 三、许可与商标

- Gradle 依赖与 vendored 二进制（poishadow/VAD）逐项对账无误；
  Apache-2.0 全文 + MIT 参考文本 + NOTICE 随 APK 分发
  （assets/third_party_licenses/）。
- 供应商 logo（智谱/DeepSeek）为指名使用（nominative use），有异议移除承诺。
- applicationId = com.noven.player，应用名/图标自有，无上游商标性使用残留。
- 历史版本源码指引：README 与 LICENSE 第 3 条注明旧仓归档地址。

## 四、保留的运行时契约（非侵权，技术冻结面）

- 包名/namespace com.openminis.app（JNI 符号、ACRA SPI、Manifest 组件链）
- /var/minis/* 运行时路径、minis-model-use CLI 命令名
- prefs 名键与资源键名（用户不可见）

## 五、剩余事项（非阻塞）

1. LICENSE 公司主体名占位待补；
2. 新仓签名 secrets 待配置（出包前）；
3. 半血档三分类结论建议公司法务书面认可后归档本报告终版。
