package com.openminis.app.ui.chat

import android.annotation.SuppressLint
import android.content.Context
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
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import java.net.URLDecoder
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
    val sessionId: String? = null,
    /** 最近消息 JSON 数组 [{role,name,text}]——皮肤覆盖层（弹幕等）吃这个。 */
    val messagesJson: String = "[]",
    /** 卡收割资源：资源ID → (字节流, MIME)。导入收割的素材由此进岛。 */
    val cardResourceLoader: ((String) -> Pair<java.io.InputStream, String>?)? = null,
    /** 收割期留下的 远程URL→资源ID 映射：JS 动态拼的已知素材也能本地命中。 */
    val remoteResources: Map<String, String> = emptyMap(),
)

internal val LocalHtmlIslandBridge = compositionLocalOf<HtmlIslandBridge?> { null }

private const val IslandBaseUrl = "https://novex.island/"
// 距上次装载不足此间隔的重载请求合并为一帧（见 IslandRenderer.sync）。
private const val IslandReloadCoalesceMs = 160L
// 模型在岛 HTML 里直接写 src="/var/minis/..." 时，WebView 按 base URL
// 展开成这个前缀的 https 请求——拦截后按 minis 资源路径解析。
private const val IslandResourcePrefix = "https://novex.island/var/minis/"
private const val IslandMaxHeightDp = 520
private const val IslandDefaultHeightDp = 160
private const val IslandHeightSignal = "__novex_h__"

/**
 * tavern-compat 适配资产（assets/island-compat/）：vendor 的 jQuery/lodash
 * 只注入显式声明 compat:tavern 的 def——模型自己写的 ```html 岛保持纯净。
 * 读取一次进程内缓存；资产缺失时降级为不注入（岛仍渲，脚本可能报缺库）。
 */
private object IslandCompatAssets {
    private val files = listOf(
        "island-compat/jquery-3.7.1.min.js",
        "island-compat/lodash-4.17.21.min.js",
        "island-compat/tavern-shim.js",
    )
    @Volatile private var cached: List<String>? = null
    fun load(context: Context): List<String> = cached ?: synchronized(this) {
        cached ?: files.mapNotNull { path ->
            runCatching { context.assets.open(path).bufferedReader().use { it.readText() } }.getOrNull()
        }.also { cached = it }
    }
}

/** 语言标记判定：`html`/`xhtml` 代码块都走岛渲染。 */
internal fun isHtmlIslandLanguage(language: String): Boolean =
    language.trim().lowercase() in setOf("html", "xhtml")

/**
 * @param fillParent 覆盖层模式：填满父容器（皮肤 overlay 整盖转录区用），
 *   不再按内容自量高度、无边框无内边距。
 */
@Composable
internal fun HtmlIsland(html: String, fillParent: Boolean = false, tavernCompat: Boolean = false) {
    val colors = currentMdColors()
    val bridge = LocalHtmlIslandBridge.current
    val context = androidx.compose.ui.platform.LocalContext.current
    val compatScripts = if (tavernCompat) remember { IslandCompatAssets.load(context) } else emptyList()
    val dark = colors.text.luminance() > 0.5f
    val frameJson = remember(bridge, dark, colors) { buildIslandFrameJson(bridge, dark, colors) }
    val islandSessionId = bridge?.sessionId

    var showSource by remember(html) { mutableStateOf(false) }
    // 数据热更新会把新值替换进 html（substituteNovexArtifact）——测量
    // 值与揭开态不随 html 键控重置，否则每次数据更新都闪回占位、重走
    // 绸缎展开，高频数据件（血条/HUD）缩个不停。占位只给首次装载；
    // 后续换页保持当前窗高，量到新值后同曲线补扫。
    var measuredCssHeight by androidx.compose.runtime.saveable.rememberSaveable { mutableIntStateOf(0) }
    var islandError by remember(html) { mutableStateOf<String?>(null) }
    // rememberSaveable：LazyColumn 回收重挂不重播揭开动画（页会重载，
    // 但窗直接停在终高，不再闪占位）。
    var revealed by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    // 渲染进程死亡自疗计数：onRenderProcessGone 销毁死 view 后 bump，
    // key(reloadTick) 触发 AndroidView 重建+整页重载（sync 看到
    // loadedHtml==null 会重新 loadDataWithBaseURL）。
    var reloadTick by remember { mutableIntStateOf(0) }
    val renderer = remember { IslandRenderer() }
    renderer.onHeight = {
        measuredCssHeight = it
        if (it > 0) revealed = true
    }
    renderer.onCrash = { reloadTick++ }
    renderer.onError = { islandError = it }
    renderer.sessionId = islandSessionId
    renderer.cardBridge = bridge

    val shape = RoundedCornerShape(10.dp)
    Box(
        modifier = Modifier
            .then(if (fillParent) Modifier.fillMaxSize() else Modifier.fillMaxWidth())
            .then(
                if (fillParent) Modifier
                else Modifier
                    .padding(bottom = 8.dp)
                    .clip(shape)
                    .border(1.dp, colors.divider, shape)
            ),
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
                if (fillParent) {
                    androidx.compose.runtime.key(reloadTick) {
                        AndroidView(
                            modifier = Modifier.fillMaxSize(),
                            factory = { ctx -> renderer.create(ctx) },
                            update = { wv -> renderer.sync(wv, html, frameJson, compatScripts) },
                            onRelease = { wv -> runCatching { wv.destroy() } },
                        )
                    }
                } else {
                    // 绸缎展开：外窗高度是唯一动画对象——装载期固定在占位高，
                    // 量出真实高后以 350ms 缓出让窗下沿扫到终高。WebView 本体
                    // 从一开始就是终高终排版（顶对齐、被窗裁切），不缩放不
                    // 淡入；后续内容长高的每次回报也走同一条曲线——增长
                    // 不再跳变。
                    val windowHeight by androidx.compose.animation.core.animateDpAsState(
                        targetValue = if (revealed) islandHeight.dp else IslandLoadingHeightDp.dp,
                        animationSpec = androidx.compose.animation.core.tween(
                            durationMillis = IslandRevealDurationMs,
                            easing = androidx.compose.animation.core.FastOutSlowInEasing,
                        ),
                        label = "island-reveal",
                    )
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(windowHeight)
                            .clipToBounds(),
                    ) {
                        androidx.compose.runtime.key(reloadTick) {
                            AndroidView(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(islandHeight.dp)
                                    .align(Alignment.TopStart),
                                factory = { ctx -> renderer.create(ctx) },
                                update = { wv -> renderer.sync(wv, html, frameJson, compatScripts) },
                                onRelease = { wv -> runCatching { wv.destroy() } },
                            )
                        }
                        // 装载占位：量出前盖在窗上；揭开瞬间淡出，留下已
                        // 排好的真实内容。
                        androidx.compose.animation.AnimatedVisibility(
                            visible = !revealed,
                            exit = androidx.compose.animation.fadeOut(
                                androidx.compose.animation.core.tween(180),
                            ),
                            modifier = Modifier.fillMaxWidth().height(IslandLoadingHeightDp.dp),
                        ) {
                            IslandLoadingPlaceholder()
                        }
                    }
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
    private var loadedCompat: List<String> = emptyList()
    private var pageReady = false
    private var frameJson = "{}"
    private var boundView: WebView? = null
    private var pushedFrame: String? = null
    // 重载合并：html 高频变化（数据热更新投影）时，距上次装载不足阈值的
    // 重载挂起为"待装"，onPageFinished 时只补装最新一版——连灌整页正是
    // 流式末尾"白底→黑掉"的窗口之一。
    private var pendingHtml: String? = null
    private var pendingCompat: List<String> = emptyList()
    private var lastLoadAtMs = 0L
    var sessionId: String? = null
    var cardBridge: HtmlIslandBridge? = null
    var onHeight: ((Int) -> Unit)? = null
    var onCrash: (() -> Unit)? = null
    var onError: ((String?) -> Unit)? = null

    fun create(context: android.content.Context): WebView = WebView(context).apply {
        configure(this)
        webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String) {
                pageReady = true
                // 装载期间积下的新 html 只装最终版（重载合并）。
                pendingHtml?.let { next ->
                    if (next != loadedHtml) {
                        pendingHtml = null
                        load(view, next, pendingCompat)
                        return
                    }
                    pendingHtml = null
                }
                // 页面可能声明了全新 document 上下文；重注当前帧保证脚本
                // 读到的是最新数据而非装载时的快照。
                pushedFrame = frameJson
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
                val url = request.url.toString()
                val scheme = request.url.scheme?.lowercase()
                if (scheme == "data" || scheme == "blob") return null
                if (url == IslandBaseUrl) return null
                // 卡收割资源：novex-asset://<资源ID> → 卡本地资源字节。
                if (scheme == "novex-asset") {
                    val id = request.url.host
                        ?: url.removePrefix("novex-asset://").substringBefore('/').substringBefore('?')
                    cardBridge?.cardResourceLoader?.invoke(id)?.let { (stream, mime) ->
                        return WebResourceResponse(mime, null, stream)
                    }
                    return emptyIslandResponse()
                }
                // 岛资源口子：本会话的图片文件可进岛当素材（上传的花纹/
                // 生图/工作区产物）。三种写法同路——模型上下文里见到的
                // /var/minis/... 路径原样写进 src 也能解析。
                if (scheme == "minis" || scheme == "novex-res" ||
                    url.startsWith(IslandResourcePrefix)
                ) {
                    return islandResourceResponse(view.context, url, sessionId)
                }
                // 收割映射：JS 运行期拼出的已知素材 URL 也回本地字节；
                // 不在表里的远程请求照旧掐断（沙箱不开口子）。
                cardBridge?.let { bridge ->
                    val map = bridge.remoteResources
                    if (map.isNotEmpty()) {
                        val hit = map[url] ?: map[url.substringBefore('?')]
                            ?: map[url.substringBefore('#')]
                        hit?.let { id ->
                            bridge.cardResourceLoader?.invoke(id)?.let { (stream, mime) ->
                                return WebResourceResponse(mime, null, stream)
                            }
                        }
                    }
                }
                // 空响应掐断一切网络子资源（blockNetworkLoads 之上的双保险）。
                return WebResourceResponse("text/plain", "utf-8", "".byteInputStream())
            }

            override fun onRenderProcessGone(view: WebView, detail: android.webkit.RenderProcessGoneDetail): Boolean {
                // 契约：返回 true 就必须销毁死 view——但销毁不等于修复，
                // 还得让 Compose 重建一个 WebView 并把整页重新装上。
                runCatching { view.destroy() }
                loadedHtml = null
                loadedCompat = emptyList()
                pageReady = false
                boundView = null
                pushedFrame = null
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
    fun sync(wv: WebView, html: String, frame: String, compat: List<String> = emptyList()) {
        // LazyColumn 回收重挂的岛可能带着暂停态回来（WebView 暂停后不重绘
        // = 黑画面透底的另一来源）；绑定时总是唤醒，代价是一次空调用。
        wv.onResume()
        wv.resumeTimers()
        frameJson = frame
        if (boundView !== wv || loadedHtml != html || loadedCompat != compat) {
            // 换绑新视图（回收重挂/进程重建）必须直装：它的 pending 没有
            // 在途页面，等 onPageFinished 回补就是永久空窗。
            val freshView = boundView !== wv
            boundView = wv
            val now = android.os.SystemClock.uptimeMillis()
            if (freshView || pageReady || now - lastLoadAtMs >= IslandReloadCoalesceMs) {
                pendingHtml = null
                load(wv, html, compat)
            } else {
                // 距上次装载太近：记最新版待装，本帧不灌——页面加载完时
                // onPageFinished 检查 pending 补装最终态。
                pendingHtml = html
                pendingCompat = compat
            }
        } else if (pageReady && pushedFrame != frame) {
            // 帧去重：宿主每次重组都会走到这（bridge 是逐重组新建的值），
            // 帧内容没变就不派发——否则岛页会被 noven:frame 事件流反复
            // 热刷新，表现为整个界面抽动。
            pushedFrame = frame
            wv.evaluateJavascript(frameUpdateJs(frame), null)
        }
    }

    private fun load(wv: WebView, html: String, compat: List<String>) {
        loadedHtml = html
        loadedCompat = compat
        pageReady = false
        lastLoadAtMs = android.os.SystemClock.uptimeMillis()
        onError?.invoke(null)
        wv.loadDataWithBaseURL(IslandBaseUrl, wrapIslandDocument(html, frameJson, compat), "text/html", "utf-8", null)
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
internal fun wrapIslandDocument(html: String, frameJson: String, compatScripts: List<String> = emptyList()): String = buildString {
    append("<!DOCTYPE html><html><head>")
    append("<meta charset=\"utf-8\">")
    append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">")
    // CSP 放行 https: 形态（收割映射命中的远程 URL 子资源经拦截器回本地
    // 字节，未命中仍被拦为空响应）——真正的断网闸在 shouldInterceptRequest。
    append("<meta http-equiv=\"Content-Security-Policy\" content=\"default-src 'none'; script-src 'unsafe-inline' novex-asset: minis: https: http:; style-src 'unsafe-inline' novex-asset: minis: https: http:; img-src 'self' data: blob: minis: novex-res: novex-asset: https: http:; font-src data: novex-asset: https: http:; connect-src 'none'; media-src novex-asset: minis: blob: https: http:; frame-src 'none'; object-src 'none'; base-uri 'none'; form-action 'none'\">")
    append("<style>html,body{margin:0;padding:0;background:transparent}*{box-sizing:border-box}</style>")
    append("<script>window.NOVEN_FRAME=")
    append(frameJson.replace("</", "<\\/"))
    append(";</script>")
    // 导入资产的适配层（vendor 库+shim）：只注入 compat:tavern 的 def，
    // 在 NOVEN_FRAME 之后、页内脚本之前——脚本启动时桥和库都已就位。
    // 内嵌 JS 里的 "</script" 必须转义防提前截断标签（</ 全局转义会
    // 误伤 a</b 这类比较，故只转义 </script）。
    compatScripts.forEach { js ->
        append("<script>")
        append(js.replace(Regex("</script", RegexOption.IGNORE_CASE), "<\\/script"))
        append("</script>")
    }
    // 本脚本在 <head> 执行、document.body 尚不存在——观察器挂接必须等
    // DOMContentLoaded；load 报一次高，定时回报兜底 JS 异步建 DOM。
    // 量高量"真实内容底"（最后一个子元素的 rect.bottom+scrollY），
    // 不量 scrollHeight——documentElement/body 的 scrollHeight 会跟随
    // 视口高度，min-height:100vh 的岛会自我膨胀出大块空白底。
    append("<script>(function(){var r=function(){var b=document.body;if(!b)return;var h=b.scrollHeight;var kids=b.children,lb=0;for(var i=0;i<kids.length;i++){var bt=kids[i].getBoundingClientRect().bottom+window.scrollY;if(bt>lb)lb=bt}if(lb>0&&lb<h)h=lb;if(h>0)console.log('")
    append(IslandHeightSignal)
    append("'+Math.ceil(h))};var ro=null;function a(){if(ro||!window.ResizeObserver)return;ro=new ResizeObserver(r);ro.observe(document.documentElement);if(document.body)ro.observe(document.body)}if(document.readyState==='loading')document.addEventListener('DOMContentLoaded',a);else a();window.addEventListener('load',r);setInterval(r,1200);r()})();</script>")
    append("</head><body>")
    append(html)
    append("</body></html>")
}

private fun frameUpdateJs(frameJson: String): String =
    "window.NOVEN_FRAME=$frameJson;window.dispatchEvent(new Event('noven:frame'));void 0;"

// ─── 岛资源（本地图片进岛）──────────────────────────────────────────────────
//
// 岛断网，但本会话的图片文件是合法素材源：用户上传的花纹/头像、生图产物、
// 工作区文件。只放行图片扩展名 + 拒绝 `..` 段——脚本样式经 CSP 天然进不来，
// 这里再把非图文件挡死，岛永远读不到会话里的任意文件。
private val IslandImageMimes = mapOf(
    "png" to "image/png",
    "jpg" to "image/jpeg",
    "jpeg" to "image/jpeg",
    "gif" to "image/gif",
    "webp" to "image/webp",
    "svg" to "image/svg+xml",
    "bmp" to "image/bmp",
    "avif" to "image/avif",
    "ico" to "image/x-icon",
)

private fun emptyIslandResponse() =
    WebResourceResponse("text/plain", "utf-8", "".byteInputStream())

private fun islandResourceResponse(context: Context, url: String, sessionId: String?): WebResourceResponse {
    val minisUrl = when {
        url.startsWith("novex-res://") -> "minis://" + url.removePrefix("novex-res://")
        url.startsWith("minis://") -> url
        // https://novex.island/var/minis/<桶>/<路径> —— src="/var/minis/..."
        // 的 base-URL 展开形态。
        else -> "minis://" + url.removePrefix(IslandResourcePrefix)
    }
    val decoded = runCatching {
        URLDecoder.decode(minisUrl.substringBefore('?'), "UTF-8")
    }.getOrNull() ?: return emptyIslandResponse()
    if (decoded.split('/').any { it == ".." }) return emptyIslandResponse()
    val file = resolveMdMediaFile(context, minisUrl, sessionId) ?: return emptyIslandResponse()
    val mime = IslandImageMimes[file.extension.lowercase()] ?: return emptyIslandResponse()
    return runCatching {
        WebResourceResponse(mime, null, file.inputStream().buffered())
    }.getOrElse { emptyIslandResponse() }
}

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
    runCatching { root.put("messages", org.json.JSONArray(bridge?.messagesJson ?: "[]")) }
    root.put("theme", JSONObject()
        .put("dark", dark)
        .put("accent", colors.link.toHexString())
        .put("text", colors.text.toHexString())
        .put("surface", colors.codeBg.toHexString()))
    return root.toString()
}

private fun Color.toHexString(): String = "#%06X".format(toArgb() and 0xFFFFFF)

// ─── 装载占位 + 绸缎展开 ────────────────────────────────────────────────────
//
// 设计本意（manual/design-intent.md）：面板装载期先占一块固定小高度，
// 进度条快冲到 ~92% 再缓速渐近（真完成才跳满）；内容早已排版完毕，
// 揭开时只有外窗下沿向下扫——不缩放、不整片淡入、不瞬现。

private const val IslandLoadingHeightDp = 72
private const val IslandRevealDurationMs = 350
private const val IslandFakeProgressCeil = 0.92f

/** 表演性进度：逐帧向天花板渐近（p += (ceil-p)*k），接近 92% 后
 *  爬行变慢；真完成由外层状态切换接管，不装死也不假满。 */
@Composable
private fun rememberIslandFakeProgress(): Float {
    var progress by remember { androidx.compose.runtime.mutableFloatStateOf(0f) }
    androidx.compose.runtime.LaunchedEffect(Unit) {
        var last = androidx.compose.runtime.withFrameNanos { it }
        while (true) {
            val now = androidx.compose.runtime.withFrameNanos { it }
            val dt = ((now - last) / 16_666_667f).coerceIn(0.5f, 4f)
            last = now
            progress += (IslandFakeProgressCeil - progress) * 0.055f * dt
        }
    }
    return progress
}

/** 界面装载占位：固定小高度 + 渐近进度条。供岛自身与流式未闭合
 *  围栏（MdBlockRender liveIncremental 分支）共用——未写完的围栏
 *  不再渲原文。 */
@Composable
internal fun IslandLoadingPlaceholder(
    label: String = "界面装载中",
    modifier: Modifier = Modifier,
) {
    val colors = currentMdColors()
    val progress = rememberIslandFakeProgress()
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(IslandLoadingHeightDp.dp)
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(Modifier.fillMaxWidth()) {
            Text(
                text = label,
                fontSize = BaseFontSize * 0.78f,
                color = colors.blockquote,
            )
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp)
                    .height(3.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(colors.divider),
            ) {
                Box(
                    Modifier
                        .fillMaxWidth(progress)
                        .height(3.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(colors.link),
                )
            }
        }
    }
}
