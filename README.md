# Novex

Novex 是一款面向中文文游的 Android（安卓）应用。它以连续的人工智能对话为主界面，让用户创建、导入并长期游玩自己的世界。

**本仓库以 GPL-3.0 开源**：Novex 派生自 GPL-3.0 授权的 OpenMinis 项目（上游血缘详见 [NOTICE](NOTICE)），按 copyleft 条款整体以 GPLv3 发布。第三方组件及其许可见 [THIRD_PARTY_LICENSES.md](THIRD_PARTY_LICENSES.md)。去上游化（自有重写）工程仍在进行，进度与度量见 [docs/UPSTREAM_EXIT_PLAN.md](docs/UPSTREAM_EXIT_PLAN.md)。

早期原型与 3.x 版本的对应源码归档于 [novex-android（只读）](https://github.com/ccbili30-collab/novex-android)，以履行对历史分发版本的源码可得性义务。

## 主要能力

- 官方供应商接入：智谱（按量 / Coding Plan 套餐）、深度求索开箱即用（填 API Key 即连），以及任意 OpenAI 兼容自定义端点；
- 世界卡 / 角色卡体系：模块化卡片编辑器、卡间引用、随卡导出分享；
- 界面岛与前端件：回复里的 ` ```html ` 渲染成可交互界面；`novex-def`/`novex-use` 注册持久前端件；
- 全屏舞台作品：声明 `display:"fullscreen"` 的自绘界面接管消息区，走 NOVEN_FRAME 数据帧协议（叙事流 / 名册 / 选项 / 增量 + 出站通道）；
- 消息分支：重试/编辑产生兄弟版本，回复下方恒显版本切换器；
- 应用内检查更新（稳定 / 预览双通道）。

## 构建

Android（安卓）工程位于 `src/android`。需要 JDK 21（Java 开发工具包第 21 版）与 Android SDK（安卓软件开发工具包）。

```bash
cd src/android
./gradlew :app:assembleStableDebug
```
