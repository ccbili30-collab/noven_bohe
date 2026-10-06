package com.openminis.app.ui.chat

import android.annotation.SuppressLint
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import novex.android.ui.NovexIcons
import novex.core.PlaythroughState
import novex.core.PlaythroughValue
import org.json.JSONObject

// HTML 岛：消息流里的 ```html 代码块渲成活界面。
//
// 沙箱规则：JS 开（界面可点可动），但断网（blockNetworkLoads + CSP
// connect-src 'none' + 请求拦截）、禁文件/内容访问、禁 DOM 存储、无
// JavascriptInterface——岛里的脚本只能自嗨，摸不到宿主数据。
//
// 数据桥：NOVEN_FRAME 以 JSON 形式内嵌在包装页头（解析期即可用），
// 桥数据变化（如剧情变量更新）时经 evaluateJavascript 热更新并派发
// 'noven:frame' 事件，界面可自行监听刷新。
//
// 高度自量：包装页注入 ResizeObserver，经 console.log 回报内容高
// （console 通道不是 JS 桥，页内脚本拿不到宿主对象）——岛随内容长高，
// 封顶 [IslandMaxHeightDp] 后内部滚动（同代码块先例）。

internal class HtmlIslandBridge(
    val cardName: String?,
    val cardKind: String?,
    val state: PlaythroughState?,
)

internal val LocalHtmlIslandBridge = compositionLocalOf<HtmlIslandBridge?> { null }

private const val IslandBaseUrl = "https://novex.island/"
private const val IslandMaxHeightDp = 520
private const val IslandDefaultHeightDp = 160
private const val IslandHeightSignal = "__novex_h__"

/** 语言标记判定：`html`/`xhtml` 代码块都走岛渲染。 */
internal fun isHtmlIslandLanguage(language: String): Boolean =
    language.trim().lowercase() in setOf("html", "xhtml")

@Composable
internal fun HtmlIsland(html: String) {
    val colors = currentMdColors()
    val bridge = LocalHtmlIslandBridge.current
    val dark = colors.text.luminance() > 0.5f
    val frameJson = remember(bridge, dark, colors) { buildIslandFrameJson(bridge, dark, colors) }

    var showSource by remember(html) { mutableStateOf(false) }
    var measuredCssHeight by remember(html) { mutableIntStateOf(0) }
    var islandError by remember(html) { mutableStateOf<String?>(null) }
    // 渲染进程死亡自疗计数：onRenderProcessGone 销毁死 view 后 bump，
    // key(reloadTick) 触发 AndroidView 重建+整页重载（sync 看到
    // loadedHtml==null 会重新 loadDataWithBaseURL）。
    var reloadTick by remember { mutableIntStateOf(0) }
    val renderer = remember { IslandRenderer() }
    renderer.onHeight = { measuredCssHeight = it }
    renderer.onCrash = { reloadTick++ }
    renderer.onError = { islandError = it }

    val shape = RoundedCornerShape(10.dp)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp)
            .clip(shape)
            .border(1.dp, colors.divider, shape),
    ) {
        if (showSource) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = 400.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp, vertical = 4.dp),
            ) {
                Text(
                    text = html,
                    fontSize = BaseFontSize * 0.85f,
                    fontFamily = colors.codeFont,
                    color = colors.codeText,
                    lineHeight = BaseLineHeight * 0.9f,
                )
            }
        } else {
            Column(Modifier.fillMaxWidth()) {
                islandError?.let { err ->
                    // 页面没画出内容的兜底条：主文档错误 / 空 body / 脚本
                    // 崩溃都落这里——岛不再静默黑/白，用户能直接看源码。
                    Text(
                        text = "界面未渲染：$err · 点右上角 </> 看源码",
                        fontSize = BaseFontSize * 0.78f,
                        color = colors.blockquote,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    )
                }
                val islandHeight = (measuredCssHeight.takeIf { it > 0 }
                    ?: IslandDefaultHeightDp).coerceAtMost(IslandMaxHeightDp)
                androidx.compose.runtime.key(reloadTick) {
                    AndroidView(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(islandHeight.dp),
                        factory = { ctx -> renderer.create(ctx) },
                        update = { wv -> renderer.sync(wv, html, frameJson) },
                        onRelease = { wv -> runCatching { wv.destroy() } },
                    )
                }
            }
        }
        // 源码/界面切换：右上角半透明小图标，不占独立抬头行（沉浸优先）。
        Icon(
            imageVector = if (showSource) NovexIcons.Visibility else NovexIcons.Code,
            contentDescription = if (showSource) "查看界面" else "查看源码",
            tint = Color.White.copy(alpha = 0.65f),
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(6.dp)
                .clip(RoundedCornerShape(10.dp))
                .clickable { showSource = !showSource }
                .background(Color.Black.copy(alpha = 0.28f))
                .padding(4.dp)
                .size(14.dp),
        )
    }
}

/** 单个岛的 WebView 生命周期 + 文档/帧同步。 */
private class IslandRenderer {
    private var loadedHtml: String? = null
    private var pageReady = false
    private var frameJson = "{}"
    private var boundView: WebView? = null
    var onHeight: ((Int) -> Unit)? = null
    var onCrash: (() -> Unit)? = null
    var onError: ((String?) -> Unit)? = null

    fun create(context: android.content.Context): WebView = WebView(context).apply {
        configure(this)
        webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String) {
                pageReady = true
                // 页面可能声明了全新 document 上下文；重注当前帧保证脚本
                // 读到的是最新数据而非装载时的快照。
                view.evaluateJavascript(frameUpdateJs(frameJson), null)
                // 空渲染检测：脚本崩溃/CSP 拦截后 body 会空无一物——透明
                // WebView 透出气泡底色就是"纯黑/纯白"的来源。显式报出来
                // 让岛走失败占位，而不是一块死颜色。
                view.evaluateJavascript(
                    "(document.body?(document.body.innerText||'').trim().length" +
                        "+document.body.childElementCount:0)",
                ) { count ->
                    if (count?.trim()?.trim('"') == "0") {
                        onError?.invoke("页面无内容（脚本可能已中断）")
                    }
                }
            }

            override fun onReceivedError(
                view: WebView,
                request: WebResourceRequest,
                error: android.webkit.WebResourceError,
            ) {
                if (request.isForMainFrame) {
                    onError?.invoke(error.description?.toString() ?: "文档加载失败")
                }
            }

            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: WebResourceRequest,
            ): Boolean = true // 链接一律吞掉——岛不允许导航出自身。

            override fun shouldInterceptRequest(
                view: WebView,
                request: WebResourceRequest,
            ): WebResourceResponse? {
                val scheme = request.url.scheme?.lowercase()
                if (scheme == "data" || scheme == "blob") return null
                if (request.url.toString() == IslandBaseUrl) return null
                // 空响应掐断一切网络子资源（blockNetworkLoads 之上的双保险）。
                return WebResourceResponse("text/plain", "utf-8", "".byteInputStream())
            }

            override fun onRenderProcessGone(view: WebView, detail: android.webkit.RenderProcessGoneDetail): Boolean {
                // 契约：返回 true 就必须销毁死 view——但销毁不等于修复，
                // 还得让 Compose 重建一个 WebView 并把整页重新装上。
                runCatching { view.destroy() }
                loadedHtml = null
                pageReady = false
                boundView = null
                onCrash?.invoke()
                return true
            }
        }
        webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(message: ConsoleMessage): Boolean {
                val text = message.message()
                if (text.startsWith(IslandHeightSignal)) {
                    text.removePrefix(IslandHeightSignal).toIntOrNull()?.let { onHeight?.invoke(it) }
                    // 有高度=body 已画出内容——清掉 onPageFinished 时点对
                    // 异步建 DOM 的岛可能的"空页面"误报。
                    onError?.invoke(null)
                    return true
                }
                return false
            }
        }
    }

    /** html 变了重载整页；只是帧数据变了走热更新。新 view 必重载——
     *  loadedHtml 不随 view 重建转移（切源码往返、渲染进程重建的 blank 根因）。 */
    fun sync(wv: WebView, html: String, frame: String) {
        frameJson = frame
        if (boundView !== wv || loadedHtml != html) {
            boundView = wv
            loadedHtml = html
            pageReady = false
            onError?.invoke(null)
            wv.loadDataWithBaseURL(IslandBaseUrl, wrapIslandDocument(html, frame), "text/html", "utf-8", null)
        } else if (pageReady) {
            wv.evaluateJavascript(frameUpdateJs(frame), null)
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
private fun configure(wv: WebView) {
    wv.settings.javaScriptEnabled = true
    wv.settings.blockNetworkLoads = true
    wv.settings.allowFileAccess = false
    wv.settings.allowContentAccess = false
    // DOM 存储放开：localStorage/sessionStorage 在 domStorageEnabled=false
    // 下访问即抛 SecurityError，岛脚本一碰就整段崩——页面空渲染透出
    // 气泡底色（纯黑/纯白的第一成因）。存储落在 WebView 私有目录且
    // 断网+CSP connect-src 'none'，无泄漏面。
    wv.settings.domStorageEnabled = true
    // 暗色模式下 WebView 的算法暗化会把浅色岛面整体反色成近黑——岛页
    // 自己经 NOVEN_FRAME.theme 管主题，宿主不替它改色。
    if (android.os.Build.VERSION.SDK_INT >= 29) {
        wv.settings.forceDark = android.webkit.WebSettings.FORCE_DARK_OFF
    }
    if (android.os.Build.VERSION.SDK_INT >= 33) {
        wv.settings.isAlgorithmicDarkeningAllowed = false
    }
    wv.settings.mediaPlaybackRequiresUserGesture = true
    wv.setBackgroundColor(0x00000000)
}

/**
 * 把岛内容包成完整文档：CSP 断网声明 + 透明底 + NOVEN_FRAME 初始帧 +
 * 高度回报 ResizeObserver。`</` 序列转义防 JSON 里出现 "</script>"
 * 提前截断内嵌脚本。
 */
/** internal 供包装页契约测试。 */
internal fun wrapIslandDocument(html: String, frameJson: String): String = buildString {
    append("<!DOCTYPE html><html><head>")
    append("<meta charset=\"utf-8\">")
    append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">")
    append("<meta http-equiv=\"Content-Security-Policy\" content=\"default-src 'none'; script-src 'unsafe-inline'; style-src 'unsafe-inline'; img-src data: blob:; font-src data:; connect-src 'none'; media-src 'none'; frame-src 'none'; object-src 'none'; base-uri 'none'; form-action 'none'\">")
    append("<style>html,body{margin:0;padding:0;background:transparent}*{box-sizing:border-box}</style>")
    append("<script>window.NOVEN_FRAME=")
    append(frameJson.replace("</", "<\\/"))
    append(";</script>")
    // 本脚本在 <head> 执行、document.body 尚不存在——观察器挂接必须等
    // DOMContentLoaded；load 报一次高，定时回报兜底 JS 异步建 DOM。
    append("<script>(function(){var r=function(){var b=document.body,e=document.documentElement;var h=b?Math.max(b.scrollHeight,b.offsetHeight,e.scrollHeight,e.offsetHeight):0;if(h>0)console.log('")
    append(IslandHeightSignal)
    append("'+Math.ceil(h))};var ro=null;function a(){if(ro||!window.ResizeObserver)return;ro=new ResizeObserver(r);ro.observe(document.documentElement);if(document.body)ro.observe(document.body)}if(document.readyState==='loading')document.addEventListener('DOMContentLoaded',a);else a();window.addEventListener('load',r);setInterval(r,1200);r()})();</script>")
    append("</head><body>")
    append(html)
    append("</body></html>")
}

private fun frameUpdateJs(frameJson: String): String =
    "window.NOVEN_FRAME=$frameJson;window.dispatchEvent(new Event('noven:frame'));void 0;"

/** 桥载荷：卡信息 + 剧情变量扁平表（Number 另带 max 表）+ 主题色。 */
private fun buildIslandFrameJson(bridge: HtmlIslandBridge?, dark: Boolean, colors: MdColors): String {
    val root = JSONObject()
    if (bridge?.cardName != null) {
        root.put("card", JSONObject()
            .put("name", bridge.cardName)
            .put("kind", bridge.cardKind.orEmpty()))
    }
    val state = JSONObject()
    val maxes = JSONObject()
    bridge?.state?.values?.forEach { (key, value) ->
        when (value) {
            is PlaythroughValue.Text -> state.put(key, value.value)
            is PlaythroughValue.Flag -> state.put(key, value.value)
            is PlaythroughValue.Number -> {
                state.put(key, if (value.value % 1.0 == 0.0) value.value.toLong() else value.value)
                value.max?.let { maxes.put(key, it) }
            }
        }
    }
    root.put("state", state)
    root.put("max", maxes)
    root.put("theme", JSONObject()
        .put("dark", dark)
        .put("accent", colors.link.toHexString())
        .put("text", colors.text.toHexString())
        .put("surface", colors.codeBg.toHexString()))
    return root.toString()
}

private fun Color.toHexString(): String = "#%06X".format(toArgb() and 0xFFFFFF)
