package com.openminis.app.ui.chat

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
// 槽位 slot：top / bottom 钉在会话顶/底（不属于任何单条消息）；inline
// 画在 use 所在消息流位置。归属规则 = 每个 id 以最后一次 use 的 slot 为准，
// 所以模型每轮都能改位置（投影是就地新画的实例，不是把旧 WebView 拖走）。
//
// 占位符 `{{key}}`：只替换 data 里存在的键——未命中键原样保留，Vue 等
// 前端框架自己的双花括号语法不会被误伤。

internal const val NOVEX_DEF_LANGUAGE = "novex-def"
internal const val NOVEX_USE_LANGUAGE = "novex-use"

internal fun isNovexDefLanguage(language: String): Boolean =
    language.trim().lowercase() == NOVEX_DEF_LANGUAGE

internal fun isNovexUseLanguage(language: String): Boolean =
    language.trim().lowercase() == NOVEX_USE_LANGUAGE

internal const val NOVEX_SLOT_TOP = "top"
internal const val NOVEX_SLOT_BOTTOM = "bottom"
internal const val NOVEX_SLOT_INLINE = "inline"

/** 一件已注册的前端件：模板+元信息。 */
internal data class NovexArtifactDef(
    val id: String,
    val kind: String,
    val name: String?,
    val defaultSlot: String?,
    val template: String,
)

/** 一次调用：目标 id + 槽位（空 = 用 def 默认槽位/inline）+ 本轮数据。 */
internal data class NovexArtifactUse(
    val id: String,
    val slot: String?,
    val data: Map<String, String>,
) {
    fun resolvedSlot(def: NovexArtifactDef?): String =
        slot ?: def?.defaultSlot ?: NOVEX_SLOT_INLINE
}

/** 会话级场景：defs 注册表 + 顶/底槽当前各自被哪些件占据。 */
internal class NovexArtifactScene(
    val defs: Map<String, NovexArtifactDef>,
    /** (def, use) 按最近 use 排序——后写的排后面（视觉上更靠槽位尾端）。 */
    val topPins: List<Pair<NovexArtifactDef, NovexArtifactUse>>,
    val bottomPins: List<Pair<NovexArtifactDef, NovexArtifactUse>>,
) {
    companion object {
        val EMPTY = NovexArtifactScene(emptyMap(), emptyList(), emptyList())
    }
}

internal val LocalNovexArtifactDefs =
    compositionLocalOf<Map<String, NovexArtifactDef>> { emptyMap() }

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
    val slot = meta.optString("slot").trim().lowercase()
        .let { if (it in setOf(NOVEX_SLOT_TOP, NOVEX_SLOT_BOTTOM, NOVEX_SLOT_INLINE)) it else null }
    return NovexArtifactDef(
        id = id,
        kind = kind,
        name = meta.optString("name").trim().ifEmpty { null },
        defaultSlot = slot,
        template = lines.drop(metaIdx + 1).joinToString("\n").trim('\n'),
    )
}

/**
 * use 围栏体：整段 JSON。`{"id":…, "slot":…, "data":{…}}`；
 * 无 `data` 对象时，id/slot 以外的散键全部视作数据。
 */
internal fun parseNovexUse(code: String): NovexArtifactUse? {
    val obj = runCatching { JSONObject(code.trim()) }.getOrNull() ?: return null
    val id = obj.optString("id").trim()
    if (id.isEmpty()) return null
    val slot = obj.optString("slot").trim().lowercase()
        .let { if (it in setOf(NOVEX_SLOT_TOP, NOVEX_SLOT_BOTTOM, NOVEX_SLOT_INLINE)) it else null }
    val data = mutableMapOf<String, String>()
    obj.optJSONObject("data")?.let { d ->
        for (k in d.keys()) data[k] = d.opt(k)?.toString() ?: ""
    }
    for (k in obj.keys()) {
        if (k == "id" || k == "slot" || k == "data") continue
        data.putIfAbsent(k, obj.opt(k)?.toString() ?: "")
    }
    return NovexArtifactUse(id, slot, data)
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
 * 从消息列表扫出当前场景：defs 后者覆盖前者（允许模型改模板），
 * 槽位归属按每个 id 最后一次 use 的 resolvedSlot。
 * [seedDefs] 为会话级预铺（卡内置前端件）——消息里的同名 def 可覆盖。
 */
internal fun collectNovexArtifactScene(
    messages: List<ChatMessage>,
    seedDefs: Map<String, NovexArtifactDef> = emptyMap(),
): NovexArtifactScene {
    val defs = LinkedHashMap<String, NovexArtifactDef>().also { it.putAll(seedDefs) }
    // 每个 id 保留最后一次 use（及其出现序），槽位归属用它裁定。
    val lastUseById = LinkedHashMap<String, Pair<Int, NovexArtifactUse>>()
    var order = 0
    for (msg in messages) {
        for (m in artifactFenceRegex.findAll(msg.content)) {
            when (m.groupValues[1].lowercase()) {
                NOVEX_DEF_LANGUAGE ->
                    parseNovexDef(m.groupValues[2])?.let { defs[it.id] = it }
                NOVEX_USE_LANGUAGE ->
                    parseNovexUse(m.groupValues[2])?.let { lastUseById[it.id] = order++ to it }
            }
        }
    }
    val top = mutableListOf<Pair<NovexArtifactDef, NovexArtifactUse>>()
    val bottom = mutableListOf<Pair<NovexArtifactDef, NovexArtifactUse>>()
    lastUseById.entries
        .sortedBy { it.value.first }
        .forEach { (id, pair) ->
            val def = defs[id] ?: return@forEach
            when (pair.second.resolvedSlot(def)) {
                NOVEX_SLOT_TOP -> top.add(def to pair.second)
                NOVEX_SLOT_BOTTOM -> bottom.add(def to pair.second)
            }
        }
    return NovexArtifactScene(defs, top, bottom)
}

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
        else -> HtmlIsland(rendered)
    }
}

/** 消息流里的 use 块：inline 槽就地投影；顶/底槽由 ChatScreen 钉位负责。 */
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
    if (use.resolvedSlot(def) != NOVEX_SLOT_INLINE) return
    RenderNovexArtifact(def, use.data)
}

/** def 块在消息流里的样子：一枚低调注册提示，模板本身不展示。 */
@Composable
internal fun RenderNovexDefChip(code: String) {
    val def = parseNovexDef(code)
    val label = when {
        def == null -> "前端件注册格式有误"
        else -> buildString {
            append("前端件「").append(def.name ?: def.id).append("」已注册 · ").append(def.kind)
            def.defaultSlot?.let {
                append(" · 默认").append(
                    when (it) {
                        NOVEX_SLOT_TOP -> "钉顶部"
                        NOVEX_SLOT_BOTTOM -> "钉底部"
                        else -> "随文"
                    }
                )
            }
        }
    }
    Text(
        label,
        fontSize = 11.sp,
        color = currentMdColors().blockquote,
        modifier = Modifier.padding(vertical = 2.dp),
    )
}
