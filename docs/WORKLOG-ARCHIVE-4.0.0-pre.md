# 工作表归档 · 4.0.0-pre 线（2026-10-02）

> 三役完成即 4.0 切换前夜的收尾。详细过程见旧仓 docs/UPSTREAM_EXIT_PLAN.md 与新仓 docs/LEGAL_AUDIT_2026-10.md。

## T005 · P4 骨架收口 + CI 崩溃根因修复
- 五件半血骨架重写：MainActivity 62.9→38.6 / MinisApp 47.5→34.9 / LLMProvider 52.5→38.2（签名面 token 级不动）/ FileBrowserViewModel 61.0→36.6 / MinisTextKitSelection 60.5→30.2
- 冻结面零触碰（净眼逐项验证：Manifest 零 diff / 初始化序逐段等价 / Room 未动）；行为钉 37+12 例
- THIRD_PARTY_LICENSES.md 重写为实际依赖（补 6 漏项）
- CI 崩溃：exit 10 → 方法级+类级探针 → 排除实验实锤 FileBrowserViewModelTest（真实 IO+轮询在 CI 慢盘拖死执行器）→ 调度器注入修复 + 构造期重入 NPE（nameOrder 改函数）
- PR#85 门控合并；beta.110 发布

## T006 · 法律审计条件轮 C1-C3
- 五件真衍生候选重写（数字见 WORKLOG 3.2）；52 例行为钉零改动全绿
- Apache-2.0 全文 + MIT 参考文本 + NOTICE 随包入 APK（真实分发物 unzip 验证）
- TPL 补 skills 自研声明；净眼 pass 高置信；PR#86 门控合并；beta.111 发布

## T007 · 4.0 切换 + noven_bohe 开仓 + 4.0.0-beta.2 发布
- 切换件：版本 4.0.0 / 专有 LICENSE（主体占位=D-01）/ README 全自主口径（后已撤回）/ 落地页去上游引导
- 新仓：noven_bohe（私有）；首提交=树快照（next@c3fa7700+切换件，无历史）；next+main 双分支；CI 绿
- 签名破案：release 密钥=远端 debug.keystore（指纹 cab4226b 比对实锤）→ 本地 local/keys（600）+钥匙串+新仓 secrets 三处落位
- 发布：4.0.0-beta.2（新仓首产包；签名指纹一致=老用户原地升级；SHA256 三方一致 d5a5d3d5；update.json preview；公告「全新自主底座」推荐升级，该口径后已撤回）
- 旧仓 novex-android 永久归档（GPL 存量义务）；hub 补历史源码指引
