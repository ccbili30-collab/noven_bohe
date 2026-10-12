package com.openminis.app.ui.chat

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import novex.core.PlaythroughValue
import org.json.JSONObject

// 舞台协议 v0.3 的岛帧（window.NOVEN_FRAME）与出站 URL 解析。
// 依据：local/design/shaosong-ui/STUDIO-ALIGNMENT-v0.3.md（r2 冻结）+
// STUDIO-ALIGNMENT-PC-v0.3.md（PC 回执）。帧字段分批落地：本批下发
// card/session/env/state/max/theme/messages(弃用)/assets/reason/turn/
// sendResult；transcript/choices/cast/deltas/archive 在后续批次补齐，
// 未落地的字段不下发（不下发空数组冒充能力）。

/** 协议版本：r2 §B3——v0.3 帧 bridgeVersion=2（体检岛按此对账）。 */
internal const val IslandBridgeVersion = 2

/** 制作上限基线：PC 模拟器按 111 标红；手机如实上报 env.webview，
 *  低于基线只提示不拦截（r2 §A——不是运行门槛）。 */
internal const val IslandWebviewBaseline = 111

/** send 回执：reason:"send" 帧携带，作品据此显示 已发/排队/没发出去。
 *  [seq] 供作品去重——同一回执可能随后续帧残留。 */
internal data class IslandSendResult(
    val result: String, // accepted | queued | dropped
    val why: String? = null, // rate | gesture | not-own | length | null
    val seq: Int = 0,
)

// v0.3 B2 门控参数：一次触摸手势最多发一条（窗口 1.5s），发送最小间隔
// 1s，文本上限 8000 字（原生输入栏无显式上限，此为合理保护值）。
internal const val IslandSendGestureWindowMs = 1500L
internal const val IslandSendMinIntervalMs = 1000L
internal const val IslandSendMaxChars = 8000

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

/** 出站意图：shouldOverrideUrlLoading 拦到的三条 scheme（r2 B1/B2）。 */
internal sealed interface IslandOutbound {
    data class Query(val domain: String, val entity: String, val from: String?) : IslandOutbound
    data class Compose(val text: String) : IslandOutbound
    data class Send(val text: String) : IslandOutbound
}

private fun decodeIslandComponent(value: String): String =
    runCatching { java.net.URLDecoder.decode(value, "UTF-8") }.getOrDefault(value)

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
    val rest = url.substringAfter("://", "")
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
    // assets 映射表（v0.2 B2）：收割期 remoteUrl→id 反转为 id→平台 URL；
    // 岛上 src 一律经 NOVEN_FRAME.asset(id) 取，不手写 scheme。
    bridge?.remoteResources?.takeIf { it.isNotEmpty() }?.let { map ->
        val assets = JSONObject()
        map.values.distinct().forEach { id -> assets.put(id, "novex-asset://$id") }
        root.put("assets", assets)
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
            .put("why", r.why ?: JSONObject.NULL)
            .put("seq", r.seq))
    }
    root.put("theme", JSONObject()
        .put("dark", dark)
        .put("accent", colors.link.toHexString())
        .put("text", colors.text.toHexString())
        .put("surface", colors.codeBg.toHexString()))
    return root.toString()
}

private fun Color.toHexString(): String = "#%06X".format(toArgb() and 0xFFFFFF)
