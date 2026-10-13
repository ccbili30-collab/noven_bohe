package com.openminis.app.ui.chat

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import novex.core.PlaythroughValue
import org.json.JSONObject

// 舞台协议 v0.3 的岛帧（window.NOVEN_FRAME）与出站 URL 解析。
// 依据：local/design/shaosong-ui/STUDIO-ALIGNMENT-v0.3.md（r2 冻结）+
// STUDIO-ALIGNMENT-PC-v0.3.md（PC 回执）。帧字段分批落地：本批下发
// card/session/env/state/max/theme/messages(弃用)/assets/reason/turn/
// sendResult；deltas/archive 由 StageDeltaResolver 产出，
// 未落地的字段不下发（不下发空数组冒充能力）。

/** 协议版本：r2 §B3——v0.3 帧 bridgeVersion=2（体检岛按此对账）。 */
internal const val IslandBridgeVersion = 2

/** 制作上限基线：PC 模拟器按 111 标红；手机如实上报 env.webview，
 *  低于基线只提示不拦截（r2 §A——不是运行门槛）。 */
internal const val IslandWebviewBaseline = 111

/** send 回执：reason:"send" 帧携带，作品据此显示 已发/排队/没发出去。
 *  形状与 r2 B2 冻结一致（{result, why}），不加协议外字段——同值回执
 *  的"又一次发生"由 noven:frame 派发本身表达。 */
internal data class IslandSendResult(
    val result: String, // accepted | queued | dropped
    val why: String? = null, // rate | gesture | not-own | length | null
)

// v0.3 B2 门控参数：一次触摸手势最多发一条（窗口 1.5s），发送最小间隔
// 1s，文本上限 8000 字（原生输入栏无显式上限，此为合理保护值）。
internal const val IslandSendGestureWindowMs = 1500L
internal const val IslandSendMinIntervalMs = 1000L
internal const val IslandSendMaxChars = 8000

// v0.3 B7 舞台生存门槛：ready() 超时 8s；连续崩溃（60s 窗内）2 次退回。
internal const val IslandReadyTimeoutMs = 8000L
internal const val IslandCrashWindowMs = 60_000L
internal const val IslandCrashFallbackCount = 2

/**
 * send 门控判定（纯函数，可单测）：v0.3 B2——只认作品声明 input:"own"
 * 之后的真实触摸：手势窗口内 + 一手势一发 + 限频 + 长度。返回值
 * ("accept", null) 由调用方按回合态映射成 accepted/queued。
 */
internal fun evaluateIslandSend(
    ownInput: Boolean,
    text: String,
    gestureUptimeMs: Long,
    consumedGestureUptimeMs: Long,
    lastSendAtMs: Long,
    nowUptimeMs: Long,
): Pair<String, String?> = when {
    !ownInput -> "dropped" to "not-own"
    gestureUptimeMs <= 0L ||
        nowUptimeMs - gestureUptimeMs > IslandSendGestureWindowMs ||
        gestureUptimeMs <= consumedGestureUptimeMs -> "dropped" to "gesture"
    text.isBlank() || text.length > IslandSendMaxChars -> "dropped" to "length"
    lastSendAtMs > 0 && nowUptimeMs - lastSendAtMs < IslandSendMinIntervalMs -> "dropped" to "rate"
    else -> "accept" to null
}

/**
 * 保序子序列判定：活动路径分叉检测用——纯追加/窗口顶扩/截窗都是
 * 子序列关系（不算分叉），只有中段改写的两列路径才互为非子序列。
 */
internal fun isIdSubsequence(sub: List<String>, full: List<String>): Boolean {
    if (sub.isEmpty()) return true
    var i = 0
    for (id in full) {
        if (id == sub[i]) {
            i++
            if (i == sub.size) return true
        }
    }
    return false
}

/** 出站意图：shouldOverrideUrlLoading 拦到的三条 scheme（r2 B1/B2）。 */
internal sealed interface IslandOutbound {
    data class Query(val domain: String, val entity: String, val from: String?) : IslandOutbound
    data class Compose(val text: String) : IslandOutbound
    data class Send(val text: String) : IslandOutbound
}

internal fun decodeIslandComponent(value: String): String =
    runCatching { java.net.URLDecoder.decode(value, "UTF-8") }.getOrDefault(value)

private fun encodeIslandComponent(value: String): String =
    java.net.URLEncoder.encode(value, "UTF-8")

private fun islandQueryParam(rest: String, key: String): String? =
    rest.substringAfter('?', "")
        .split('&')
        .firstOrNull { it.substringBefore('=') == key }
        ?.substringAfter('=')
        ?.let(::decodeIslandComponent)

/**
 * 出站 URL 解析（纯 Kotlin，可单测）：
 * `novex-query://<domain>/<entity>?from=<scene>` /
 * `novex-compose://?text=<urlenc>` / `novex-send://?text=<urlenc>`。
 * helper 端 encodeURIComponent 编码，'/''%' 都不会裸出现。
 */
internal fun parseIslandOutbound(url: String): IslandOutbound? {
    val scheme = url.substringBefore("://", "").lowercase()
    val rest = url.substringAfter("://", "").substringBefore('#')
    return when (scheme) {
        "novex-query" -> {
            val path = rest.substringBefore('?')
            val domain = decodeIslandComponent(path.substringBefore('/'))
            val entity = decodeIslandComponent(path.substringAfter('/', "").substringBefore('/'))
            if (domain.isBlank() || entity.isBlank()) {
                null
            } else {
                IslandOutbound.Query(domain, entity, islandQueryParam(rest, "from"))
            }
        }
        "novex-compose" -> islandQueryParam(rest, "text")?.let(IslandOutbound::Compose)
        "novex-send" -> islandQueryParam(rest, "text")?.let(IslandOutbound::Send)
        else -> null
    }
}

/** "124.0.6367.113" → 124；取不到为 null。 */
internal fun parseWebViewMajor(versionName: String?): Int? =
    versionName?.substringBefore('.')?.toIntOrNull()

/** 当前系统 WebView 的 Chromium 主版本（API 26+，minSdk 26 全覆盖）。 */
internal fun islandWebViewMajor(context: android.content.Context): Int? =
    runCatching {
        android.webkit.WebView.getCurrentWebViewPackage()?.versionName
            .let(::parseWebViewMajor)
    }.getOrNull()

private fun bridgePutNullable(obj: JSONObject, key: String, value: Int?) {
    if (value != null) obj.put(key, value) else obj.put(key, JSONObject.NULL)
}

/** 桥载荷 → NOVEN_FRAME JSON。字段清单见文件头注释。 */
internal fun buildIslandFrameJson(bridge: HtmlIslandBridge?, dark: Boolean, colors: MdColors): String {
    val root = JSONObject()
    if (bridge?.cardName != null || bridge?.cardId != null) {
        root.put("card", JSONObject()
            .put("id", bridge?.cardId.orEmpty())
            .put("name", bridge?.cardName)
            .put("kind", bridge?.cardKind.orEmpty()))
    }
    bridge?.sessionId?.let { root.put("session", it) }
    root.put("env", JSONObject()
        .put("platform", "android")
        .put("bridgeVersion", IslandBridgeVersion)
        .also { bridgePutNullable(it, "webview", bridge?.webviewMajor) }
        .put("ime", JSONObject()
            .put("visible", bridge?.imeVisible == true)
            .put("height", bridge?.imeHeightCssPx ?: 0)))
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
    // 弃用但保留一个版本：皮肤 overlay 依赖（v0.4 由 transcript 接替）。
    runCatching { root.put("messages", org.json.JSONArray(bridge?.messagesJson ?: "[]")) }
    // transcript 叙事流（v0.3 B1）：宿主已解析的结构化段表。
    runCatching { root.put("transcript", org.json.JSONArray(bridge?.transcriptJson ?: "[]")) }
    // cast 名册 / choices 选项数据（v0.3 B5/B4）：无则不下发（不下发
    // 空数组冒充能力——作品判字段存在即可知有无）。
    bridge?.castJson?.let { runCatching { root.put("cast", org.json.JSONArray(it)) } }
    bridge?.choicesJson?.let { runCatching { root.put("choices", org.json.JSONObject(it)) } }
    // deltas 增量流 + archive 快照（v0.3 B6「日志即真相」）：archive 是
    // 可丢弃的派生缓存——本版恒空对象，作品按序重放 deltas 物化；信封
    // 自带操作动词（replace/delta/insert/remove/move），宿主不猜语义。
    bridge?.deltasJson?.let {
        runCatching {
            root.put("deltas", org.json.JSONArray(it))
            root.put("archive", JSONObject())
        }
    }
    // assets 映射表（v0.2 B2 + S2.4）：卡本地资源 id ∪ 收割期
    // remoteUrl→id 反转值，统一写成 id→平台 URL；岛上 src 一律经
    // NOVEN_FRAME.asset(id) 取，不手写 scheme。id 编码后与拦截器
    // decode 对齐（非 ASCII/含空格 id 也能命中）。
    run {
        val ids = (bridge?.cardResourceIds ?: emptyList()) +
            (bridge?.remoteResources?.values ?: emptyList())
        if (ids.isNotEmpty()) {
            val assets = JSONObject()
            ids.distinct().forEach { id ->
                assets.put(id, "novex-asset://${encodeIslandComponent(id)}")
            }
            root.put("assets", assets)
        }
    }
    // reason 由宿主在事件发生时短暂置位（约一帧窗口后回到 "turn"）；
    // sendResult 载荷可常驻——作品按 seq 去重，不依赖 reason 判断新回执。
    root.put("reason", bridge?.frameReason ?: "turn")
    root.put("turn", JSONObject()
        .put("status", if (bridge?.turnStreaming == true) "streaming" else "idle")
        .put("queued", bridge?.queuedCount ?: 0))
    bridge?.sendResult?.let { r ->
        root.put("sendResult", JSONObject()
            .put("result", r.result)
            .put("why", r.why ?: JSONObject.NULL))
    }
    root.put("theme", JSONObject()
        .put("dark", dark)
        .put("accent", colors.link.toHexString())
        .put("text", colors.text.toHexString())
        .put("surface", colors.codeBg.toHexString()))
    return root.toString()
}

private fun Color.toHexString(): String = "#%06X".format(toArgb() and 0xFFFFFF)
