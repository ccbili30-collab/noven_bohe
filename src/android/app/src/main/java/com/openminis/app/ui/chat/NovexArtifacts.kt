package com.openminis.app.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import novex.android.ui.NovexIcons
import org.json.JSONObject

// 前端件机制：```novex-def 注册"前端件"（模板只出现一次，随消息持久化），
// ```novex-use 每轮只发 {id, slot, 数据}——客户端拿注册表里的模板就地投影，
// 模型不重写代码，也就不会每轮写出新 bug。
//
// 三类 kind：
//   math  — 模板是 LaTeX，代入后走 KaTeX 位图管线
//   table — 模板是 markdown（表格为主），代入后走块渲染
//   html  — 模板是完整 HTML 页，代入后装沙箱岛
//
// 件的位置 = use 块在消息流里的位置，就地投影。历史协议里的 top/bottom
// 钉位槽已退役——前端件属于叙事流，不是屏幕镶边；解析仍认得 slot 字段
// （旧消息不报格式错误），但一律按 inline 处理。
//
// 占位符 `{{key}}`：只替换 data 里存在的键——未命中键原样保留，Vue 等
// 前端框架自己的双花括号语法不会被误伤。

internal const val NOVEX_DEF_LANGUAGE = "novex-def"
internal const val NOVEX_USE_LANGUAGE = "novex-use"
internal const val NOVEX_SKIN_LANGUAGE = "novex-skin"

internal fun isNovexDefLanguage(language: String): Boolean =
    language.trim().lowercase() == NOVEX_DEF_LANGUAGE

internal fun isNovexUseLanguage(language: String): Boolean =
    language.trim().lowercase() == NOVEX_USE_LANGUAGE

internal fun isNovexSkinLanguage(language: String): Boolean =
    language.trim().lowercase() == NOVEX_SKIN_LANGUAGE

internal const val NOVEX_SLOT_INLINE = "inline"

/** 一件已注册的前端件：模板+元信息。 */
internal data class NovexArtifactDef(
    val id: String,
    val kind: String,
    val name: String?,
    val defaultSlot: String?,
    val template: String,
    /** 显示期替换标记：消息文本里出现该字面量时在该处投影此件
     *  （等价于酒馆"占位标记→正则换页面"的语义，但是我们的协议）。 */
    val marker: String? = null,
    /** 酒馆「每回合自动注入」标记：卡里有同 findRegex 的空替换伴生脚本时
     *  由转译器置位。显示期在整条消息末尾补插该标记再展开——对应酒馆框架
     *  把 <StatusPlaceHolderImpl/> 之类占位符自动附加到每条回复的行为。 */
    val autoMarker: Boolean = false,
    /** 模板来自导入的酒馆前端资产：岛渲染时注入 tavern-compat 适配层
     *  （getVariables→NOVEN_FRAME.state、vendor 的 jQuery/lodash）。 */
    val tavernCompat: Boolean = false,
    /** display:"fullscreen" 声明整页舞台：消息流里只有首次 use 渲真岛
     *  （点击放大进舞台），其余 use 一律渲轻量入口卡——整页前端件不再
     *  每轮内联重复渲染。缺省规则：compat:tavern 的资产本就是整页，一律
     *  全屏；display:"inline" 可显式关掉。 */
    val fullscreen: Boolean = false,
    /** 舞台协议 v0.3 B2：input:"own" 声明作品自带输入层——App 隐藏原生
     *  输入栏、放行 send 通道（是模式切换不是授权，门控仍在宿主）。 */
    val ownInput: Boolean = false,
)

/** 一次调用：目标 id + 本轮数据（slot 字段已退役，仅存解析兼容）。 */
internal data class NovexArtifactUse(
    val id: String,
    val data: Map<String, String>,
)

/** 全屏件「首次 use」锚点：仅这一处渲真岛（点击放大），其余 use 渲入口卡。 */
internal data class NovexFullscreenAnchor(val messageId: String, val code: String)

/** 会话级场景：defs 注册表（seed 在前，消息内同名 def 覆盖）。 */
internal class NovexArtifactScene(
    val defs: Map<String, NovexArtifactDef>,
    /** defId → 全屏件首次 use 的位置（锚点处渲真岛，其余渲入口卡）。 */
    val fullscreenAnchors: Map<String, NovexFullscreenAnchor> = emptyMap(),
    /** defId → 最近一次 use 数据：舞台打开后用最新一轮数据投影。 */
    val latestUseData: Map<String, Map<String, String>> = emptyMap(),
) {
    companion object {
        val EMPTY = NovexArtifactScene(emptyMap())
    }
}

internal val LocalNovexArtifactDefs =
    compositionLocalOf<Map<String, NovexArtifactDef>> { emptyMap() }

internal val LocalNovexFullscreenAnchors =
    compositionLocalOf<Map<String, NovexFullscreenAnchor>> { emptyMap() }

/** 舞台宿主入口：ChatScreen 提供；为 null 时入口卡渲染成不可点。 */
internal val LocalNovexStageOpener =
    compositionLocalOf<((NovexArtifactDef) -> Unit)?> { null }

// ─── 解析 ───────────────────────────────────────────────────────────────────

private val artifactPlaceholder = Regex("\\{\\{\\s*([A-Za-z0-9_.\\-]+)\\s*\\}\\}")

/** `{{key}}` 代入：只动 data 里存在的键，其余原样保留。 */
internal fun substituteNovexArtifact(template: String, data: Map<String, String>): String {
    if (data.isEmpty() || '{' !in template) return template
    return artifactPlaceholder.replace(template) { m ->
        data[m.groupValues[1]] ?: m.value
    }
}

/**
 * def 围栏体：首个非空行是 JSON meta `{id, kind, name?, slot?}`，
 * 其余行整体为模板（保留原始空白）。
 */
internal fun parseNovexDef(code: String): NovexArtifactDef? {
    val lines = code.lines()
    val metaIdx = lines.indexOfFirst { it.isNotBlank() }
    if (metaIdx < 0) return null
    val meta = runCatching { JSONObject(lines[metaIdx].trim()) }.getOrNull() ?: return null
    val id = meta.optString("id").trim()
    if (id.isEmpty()) return null
    val kind = meta.optString("kind").trim().lowercase()
        .ifEmpty { "html" }
        .let { if (it in setOf("math", "html", "table")) it else "html" }
    // slot 字段不再影响落点（件总在 use 处就地投影），仅为向后兼容保留读取。
    val slot = meta.optString("slot").trim().lowercase()
        .ifEmpty { null }
    return NovexArtifactDef(
        id = id,
        kind = kind,
        name = meta.optString("name").trim().ifEmpty { null },
        defaultSlot = slot,
        template = lines.drop(metaIdx + 1).joinToString("\n").trim('\n'),
        marker = meta.optString("marker").trim().ifEmpty { null },
        autoMarker = meta.optBoolean("autoMarker", false),
        tavernCompat = meta.optString("compat") == "tavern",
        fullscreen = when (meta.optString("display").trim().lowercase()) {
            "fullscreen" -> true
            "inline" -> false
            else -> meta.optString("compat") == "tavern"
        },
        ownInput = meta.optString("input").trim().lowercase() == "own",
    )
}

/**
 * use 围栏体：整段 JSON。`{"id":…, "data":{…}}`；
 * 无 `data` 对象时，id/slot 以外的散键全部视作数据（slot 为历史字段）。
 */
internal fun parseNovexUse(code: String): NovexArtifactUse? {
    val obj = runCatching { JSONObject(code.trim()) }.getOrNull() ?: return null
    val id = obj.optString("id").trim()
    if (id.isEmpty()) return null
    val data = mutableMapOf<String, String>()
    obj.optJSONObject("data")?.let { d ->
        for (k in d.keys()) data[k] = d.opt(k)?.toString() ?: ""
    }
    for (k in obj.keys()) {
        if (k == "id" || k == "slot" || k == "data") continue
        data.putIfAbsent(k, obj.opt(k)?.toString() ?: "")
    }
    return NovexArtifactUse(id, data)
}

// 围栏体扫描：要求闭合 ```（流式中未闭合的半截块不算数，写完整才生效）。
private val artifactFenceRegex = Regex(
    "```\\s*(novex-def|novex-use)[^\\n`]*\\n(.*?)```",
    setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE),
)

/** 从任意文本扫出全部 def 围栏定义（卡模块预铺用）。 */
internal fun collectDefsFromText(text: String): List<NovexArtifactDef> =
    artifactFenceRegex.findAll(text).mapNotNull { m ->
        if (m.groupValues[1].lowercase() == NOVEX_DEF_LANGUAGE) {
            parseNovexDef(m.groupValues[2])
        } else null
    }.toList()

/**
 * 从消息列表扫出当前场景：defs 后者覆盖前者（允许模型改模板）。
 * use 块不再参与场景归集——件的投影在消息流内就地发生。
 * [seedDefs] 为会话级预铺（卡内置前端件）——消息里的同名 def 可覆盖。
 */
internal fun collectNovexArtifactScene(
    messages: List<ChatMessage>,
    seedDefs: Map<String, NovexArtifactDef> = emptyMap(),
): NovexArtifactScene {
    val defs = LinkedHashMap<String, NovexArtifactDef>().also { it.putAll(seedDefs) }
    for (msg in messages) {
        for (m in artifactFenceRegex.findAll(msg.content)) {
            if (m.groupValues[1].lowercase() == NOVEX_DEF_LANGUAGE) {
                parseNovexDef(m.groupValues[2])?.let { defs[it.id] = it }
            }
        }
    }
    // 全屏件第二遍扫描：按显示期同一份变换（剥 MVU + 标记展开 + 文末
    // autoMarker 补插）定位每个全屏件的首次 use——锚点处渲真岛、其余 use
    // 渲入口卡；顺带记录每个件的最新一轮数据供舞台投影。仅在有全屏件时
    // 付这条 O(全文) 扫描的成本。
    if (defs.values.none { it.fullscreen }) return NovexArtifactScene(defs)
    val anchors = LinkedHashMap<String, NovexFullscreenAnchor>()
    val latestData = LinkedHashMap<String, Map<String, String>>()
    for (msg in messages) {
        if (msg.role != "assistant") continue
        val display = expandNovexMarkers(
            novex.core.tavern.MvuStateProtocol.stripBlocks(msg.content),
            defs,
            appendAutoMarkers = true,
        )
        for (m in artifactFenceRegex.findAll(display)) {
            if (m.groupValues[1].lowercase() != NOVEX_USE_LANGUAGE) continue
            val code = m.groupValues[2].trim()
            val use = parseNovexUse(code) ?: continue
            if (defs[use.id]?.fullscreen != true) continue
            anchors.putIfAbsent(use.id, NovexFullscreenAnchor(msg.id, code))
            if (use.data.isNotEmpty() || use.id !in latestData) {
                latestData[use.id] = use.data
            }
        }
    }
    return NovexArtifactScene(defs, anchors, latestData)
}

/**
 * 显示期标记展开：消息文本里每个已注册 marker 字面量替换为对应的
 * novex-use 围栏——复用整套投影管线（解析/预算/渲染），原文不改。
 * 无 marker 定义时原样返回（保持 String 实例，下游 remember/缓存键不变）。
 *
 * [appendAutoMarkers] 只在「整条消息」级调用方打开（末片段/整条渲染）：
 * 把 autoMarker def 里没出现的标记补到文末再展开——对应酒馆框架每回合
 * 自动注入占位标记的行为。片段级调用保持 false，否则每段都长出一个件。
 */
internal fun expandNovexMarkers(
    text: String,
    defs: Map<String, NovexArtifactDef>,
    appendAutoMarkers: Boolean = false,
): String {
    if (defs.isEmpty()) return text
    var out = text
    if (appendAutoMarkers) {
        for (def in defs.values) {
            val marker = def.marker ?: continue
            if (def.autoMarker && marker.isNotEmpty() && marker !in out) out += "\n\n$marker"
        }
    }
    var touched = false
    for (def in defs.values) {
        val marker = def.marker ?: continue
        if (marker.isEmpty() || marker !in out) continue
        // 模型常把标记单独包进一个代码围栏（```\n<标记>\n```）——不拆开
        // 的话展开出的 novex-use 围栏会嵌套在原围栏里，整体被当成代码。
        out = Regex(
            "(?m)^```[^\\n]*\\n\\s*" + Regex.escape(marker) + "\\s*\\n```\\s*$",
        ).replace(out, marker)
        out = out.replace(marker, "\n\n```novex-use\n{\"id\":${JSONObject.quote(def.id)}}\n```\n\n")
        touched = true
    }
    return if (touched) out else text
}

// 协议围栏（def/use/skin）不属于叙事文本：气泡展示与 frame.messages 投影
// 前先剥掉——它们走各自的控制通道，原文只该出现在编辑器/复制里。
private val protocolFenceRegex = Regex(
    "```\\s*(novex-def|novex-use|novex-skin)[^\\n`]*\\n.*?```\\n?",
    setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE),
)

/** 剥掉闭合的 novex 协议围栏（未闭合的半截块保留——流式中可能还在写）。 */
internal fun stripNovexProtocolFences(text: String): String =
    protocolFenceRegex.replace(text, "").trim()

// ─── 渲染 ───────────────────────────────────────────────────────────────────

/** 按 kind 把"模板×数据"投影成界面。 */
@Composable
internal fun RenderNovexArtifact(def: NovexArtifactDef, data: Map<String, String>) {
    val rendered = remember(def.template, data) {
        substituteNovexArtifact(def.template, data)
    }
    when (def.kind) {
        "math" -> RenderMathDisplay(rendered)
        "table" -> {
            // 代入后按普通 markdown 走块渲染——模板里可以放表格之外的
            // 标题/段落，创作者得到的是一整块版面的自由度。
            val blocks = remember(rendered) { parseMarkdownBlocksBlocking(rendered) }
            Column(modifier = Modifier.fillMaxWidth()) {
                blocks.forEach { RenderBlock(it) }
            }
        }
        else -> HtmlIsland(rendered, tavernCompat = def.tavernCompat)
    }
}

/** 消息流里的 use 块：在块所在位置就地投影该件的最新数据实例。 */
@Composable
internal fun RenderNovexUse(code: String) {
    val use = parseNovexUse(code) ?: return
    val def = LocalNovexArtifactDefs.current[use.id]
    if (def == null) {
        Text(
            "未注册的前端件「${use.id}」",
            fontSize = 11.sp,
            color = currentMdColors().blockquote,
            modifier = Modifier.padding(vertical = 2.dp),
        )
        return
    }
    if (def.fullscreen) {
        // 全屏件：锚点（首次 use）渲真岛供点击放大，其余 use 渲入口卡——
        // 整页前端件不再每轮内联重复渲。
        val anchor = LocalNovexFullscreenAnchors.current[use.id]
        val isAnchor = anchor != null &&
            anchor.messageId == LocalShardId.current?.messageId &&
            anchor.code == code.trim()
        if (isAnchor) NovexStagePreview(def, use.data) else NovexStageEntryCard(def)
        return
    }
    RenderNovexArtifact(def, use.data)
}

/** 全屏件首次出现的内联预览：渲真岛，点击整块放大进舞台（顶栏输入框原位，
 *  只换消息区）。预览不吃岛交互——整页件在方块高度里没有可用交互面。 */
@Composable
private fun NovexStagePreview(def: NovexArtifactDef, data: Map<String, String>) {
    val opener = LocalNovexStageOpener.current
    Box(Modifier.fillMaxWidth()) {
        RenderNovexArtifact(def, data)
        if (opener != null) {
            Box(Modifier.matchParentSize().clickable { opener(def) })
            Text(
                text = "点击放大",
                fontSize = 11.sp,
                color = Color.White.copy(alpha = 0.85f),
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(6.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color.Black.copy(alpha = 0.32f))
                    .padding(horizontal = 8.dp, vertical = 3.dp),
            )
        }
    }
}

/** 全屏件的后续入口卡：轻量行，不再起 WebView。点击回到舞台。 */
@Composable
private fun NovexStageEntryCard(def: NovexArtifactDef) {
    val opener = LocalNovexStageOpener.current
    val colors = currentMdColors()
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(colors.codeBg)
            .clickable(enabled = opener != null) { opener?.invoke(def) }
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Icon(
            imageVector = NovexIcons.Fullscreen,
            contentDescription = null,
            tint = colors.blockquote,
            modifier = Modifier.size(16.dp),
        )
        Text(
            text = def.name ?: def.id,
            fontSize = 12.sp,
            color = colors.text,
            modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
        )
        Text(
            text = "打开舞台",
            fontSize = 11.sp,
            color = colors.link,
        )
    }
}

/** def 块在消息流里的样子：一枚低调注册提示，模板本身不展示。 */
@Composable
internal fun RenderNovexDefChip(code: String) {
    val def = parseNovexDef(code)
    val label = when {
        def == null -> "前端件注册格式有误"
        else -> "前端件「${def.name ?: def.id}」已注册 · ${def.kind}"
    }
    Text(
        label,
        fontSize = 11.sp,
        color = currentMdColors().blockquote,
        modifier = Modifier.padding(vertical = 2.dp),
    )
}

/**
 * novex-skin 块在消息流里的样子：一行低调提示。皮肤功能已退役（满幅
 * 界面由 novex-def 舞台件接替），旧消息里的皮肤围栏只被识别展示，
 * 不再驱动任何换装。
 */
@Composable
internal fun RenderNovexSkinChip(code: String) {
    Text(
        "皮肤围栏（皮肤功能已下线）",
        fontSize = 11.sp,
        color = currentMdColors().blockquote,
        modifier = Modifier.padding(vertical = 2.dp),
    )
}
