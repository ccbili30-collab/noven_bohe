# 界面岛（```html 交互界面）

回复里写 ` ```html ` 围栏 → 渲染成气泡内的可交互界面（沙箱 WebView），不是代码文本。

## 写法

- 写片段即可：不带 `<!DOCTYPE>/<html>`，可含 `<style>`/`<script>`。结构、样式、脚本必须全部收在同一块内，块内不得出现 ` ``` ` 开头的行。
- 移动端画布 ≈320–380px，单列布局；禁 `position:fixed`/`100vh`/`100vw`（高度随内容自适应）。

## 沙箱红线

无网络（CDN/外链图/fetch 全失败）、无 localStorage、无弹窗。**图片例外**：本会话图片文件可进岛当素材（用户上传图、生图、工作区文件），`src` 写 `/var/minis/attachments/...`、`minis://attachments/...` 或 `novex-res://attachments/...` 等价（仅图片扩展名，路径禁 `..`）；其余资源仍须 `data:` 内联或内联 SVG/CSS 渐变。

## 数据桥 window.NOVEN_FRAME

脚本执行前就绪：`card={name,kind}`、`state`（本局变量）、`max`（上限）、`theme={dark,accent,text,surface}`。`state` 空时按"尚无数据"渲染，不编数字；配色吃 theme 跟随应用深浅色。

实时：`update_playthrough_state` 改动会派发 `noven:frame` 事件，`addEventListener('noven:frame', 重绘)` 让常驻面板自更新。

视觉细节配方读 `island-craft` 技能。

## 出问题怎么办

岛渲染失败会显示"界面未渲染：原因 · 点右上角 </> 看源码"——让用户把源码发回来排查，不要假装界面已出。排错细节见 `troubleshooting.md`。
