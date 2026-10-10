package com.openminis.app.tools

import org.json.JSONObject

/**
 * [T-alias-resolver] 模型工具参数的容错解析层。
 *
 * 模型是有损复印机：36-64 位不透明编号（doccard-UUID、64hex 文档/工作区
 * 引用）常被抄成全零占位或部分错抄，导致参数完全合法、对象却永远找不到。
 * 本层在工具执行前把"可唯一判定"的错抄参数解析回真实编号——解析不动
 * 就原样放行，交给下层既有校验报错。
 *
 * 安全边界：只做唯一确定的修正（精确/唯一前缀/唯一名称命中，或占位值
 * +范围内唯一候选），绝不在多个候选里替模型挑一个；不跨会话范围解析——
 * 候选全部由调用方按当前会话作用域提供。
 */
internal object NovexToolArgResolver {

    /** id=真实编号；label=可命中名称（卡名/文件相对路径）；group=归属（target 的 root）。 */
    data class Candidate(val id: String, val label: String? = null, val group: String? = null)

    /**
     * 会话级解析范围。全部 provider 惰性求值：参数真的出现且需要比对时才
     * 触到底层查询，避免每次工具调用都枚举工作区/卡库。
     */
    class Scope(
        /** 会话可读卡的根候选：id=rootId，label=卡名。 */
        val scopeRoots: () -> List<Candidate> = { emptyList() },
        /** 会话可读目标候选：id=targetId，group=rootId，label=目标名。 */
        val scopeTargets: () -> List<Candidate> = { emptyList() },
        /** 全库卡候选（card_id/world_id/subject_id 等应用层参数）。 */
        val libraryCards: () -> List<Candidate> = { emptyList() },
        /** 文档引用候选：id=novex://documents/...，label=文档标题。 */
        val documents: () -> List<Candidate> = { emptyList() },
        /** 资料集引用候选。 */
        val collections: () -> List<Candidate> = { emptyList() },
        /** 工作区文件候选：id=完整 workspace_ref，label=相对路径。 */
        val workspaceRefs: () -> List<Candidate> = { emptyList() },
        /** 当前版本令牌：草稿版本或 saved:<revision>。 */
        val draftToken: (rootId: String) -> String? = { null },
        /** 指定卡（root, target?）的模块候选：id=模块编号，label=模块名。 */
        val modules: (rootId: String, targetId: String?) -> List<Candidate> = { _, _ -> emptyList() },
        /** 指定模块的内容块候选。 */
        val blocks: (rootId: String, targetId: String?, moduleId: String) -> List<Candidate> = { _, _, _ -> emptyList() },
    )

    private val RESOLVABLE_PARAMS = setOf(
        "root_id", "target_id", "card_id", "world_id", "subject_id",
        "document_ref", "collection_ref", "workspace_ref", "input_refs",
        "draft_version", "module_id", "block_id",
    )

    fun hasResolvableParams(argsJson: String): Boolean =
        runCatching { JSONObject(argsJson) }.getOrNull()
            ?.let { args -> RESOLVABLE_PARAMS.any(args::has) } == true

    /**
     * 返回改写后的参数 JSON；无可判定修正时原样返回。
     * @param injectDraftVersion 工具需要 draft_version 时置真——模型整体省略
     * 版本号（等同于"对最新草稿操作"）时按当前版本令牌补入。read_card 等
     * 不收该字段的工具不得传入真。
     */
    fun resolve(argsJson: String, scope: Scope, injectDraftVersion: Boolean = false): String {
        val args = runCatching { JSONObject(argsJson) }.getOrNull() ?: return argsJson
        if (RESOLVABLE_PARAMS.none(args::has) &&
            !(injectDraftVersion && args.has("root_id"))) return argsJson
        var changed = false

        // ---- 卡片根：root_id 走会话范围，card_id/world_id/subject_id 走全库 ----
        var cardRoot: String? = null
        var cardTarget: String? = null
        args.optString("root_id").takeIf(String::isNotBlank)?.let { raw ->
            pick(raw, scope.scopeRoots())?.let {
                if (it != raw) { args.put("root_id", it); changed = true }
                cardRoot = it
            }
        }
        for (key in listOf("card_id", "world_id", "subject_id")) {
            args.optString(key).takeIf(String::isNotBlank)?.let { raw ->
                pick(raw, scope.libraryCards())?.let {
                    if (it != raw) { args.put(key, it); changed = true }
                    if (cardRoot == null) cardRoot = it
                }
            }
        }

        // ---- 目标：解析出的 root 限定候选，未限定再退回全部会话目标 ----
        args.optString("target_id").takeIf(String::isNotBlank)?.let { raw ->
            val all = scope.scopeTargets()
            val withinRoot = if (cardRoot != null) all.filter { it.group == cardRoot } else all
            pick(raw, withinRoot)?.let {
                if (it != raw) { args.put("target_id", it); changed = true }
                cardTarget = it
            }
        }

        // ---- 文档/资料集 ----
        for ((key, provider) in mapOf(
            "document_ref" to scope.documents,
            "collection_ref" to scope.collections,
        )) {
            args.optString(key).takeIf(String::isNotBlank)?.let { raw ->
                pick(raw, provider())?.let {
                    if (it != raw) { args.put(key, it); changed = true }
                }
            }
        }

        // ---- 工作区：UUID 段常被抄坏但文件名能抄对，按末段文件名兜底 ----
        args.optString("workspace_ref").takeIf(String::isNotBlank)?.let { raw ->
            pickWorkspace(raw, scope.workspaceRefs())?.let {
                if (it != raw) { args.put("workspace_ref", it); changed = true }
            }
        }
        args.optJSONArray("input_refs")?.let { array ->
            for (index in 0 until array.length()) {
                val raw = array.optString(index)
                pickWorkspace(raw, scope.workspaceRefs())?.let {
                    if (it != raw) { array.put(index, it); changed = true }
                }
            }
        }

        // ---- 草稿版本：latest/占位/当前版本前缀 → 归一化成当前版本令牌；
        // 需要的工具整体省略时按"对最新草稿"补入 ----
        run {
            val root = cardRoot
            if (injectDraftVersion && !args.has("draft_version") && root != null) {
                scope.draftToken(root)?.let { args.put("draft_version", it); changed = true }
            }
        }
        args.optString("draft_version").takeIf(String::isNotBlank)?.let { raw ->
            val root = cardRoot ?: return@let
            val current = scope.draftToken(root) ?: return@let
            val resolved = when {
                raw == current || raw.startsWith("saved:") -> null
                raw.equals("latest", true) || raw.equals("current", true) || raw == "saved" -> current
                raw.length >= 8 && current.startsWith(raw) -> current
                looksPlaceholder(raw) -> current
                else -> null
            }
            if (resolved != null) { args.put("draft_version", resolved); changed = true }
        }

        // ---- 模块/块：依赖卡上下文，模块名可命中 ----
        var moduleId: String? = null
        args.optString("module_id").takeIf(String::isNotBlank)?.let { raw ->
            val root = cardRoot ?: return@let
            pick(raw, scope.modules(root, cardTarget), prefixMin = 4)?.let {
                if (it != raw) { args.put("module_id", it); changed = true }
                moduleId = it
            }
        }
        args.optString("block_id").takeIf(String::isNotBlank)?.let { raw ->
            val root = cardRoot ?: return@let
            val module = moduleId ?: return@let
            pick(raw, scope.blocks(root, cardTarget, module), prefixMin = 6)?.let {
                if (it != raw) { args.put("block_id", it); changed = true }
            }
        }

        return if (changed) args.toString() else argsJson
    }

    /**
     * 唯一命中链：精确 id → 唯一前缀 → 唯一名称 → （占位值或明显非编号串）
     * +范围内唯一候选。多候选歧义一律不猜。
     */
    private fun pick(raw: String, candidates: List<Candidate>, prefixMin: Int = 8): String? {
        if (raw.isBlank() || candidates.isEmpty()) return null
        candidates.firstOrNull { it.id == raw }?.let { return it.id }
        if (raw.length >= prefixMin) {
            candidates.filter { it.id.startsWith(raw) }.singleOrNull()?.let { return it.id }
        }
        candidates.filter { it.label != null && it.label.equals(raw, ignoreCase = true) }
            .singleOrNull()?.let { return it.id }
        if (candidates.size == 1 && (looksPlaceholder(raw) || !looksOpaqueId(raw))) {
            return candidates[0].id
        }
        return null
    }

    private fun pickWorkspace(raw: String, candidates: List<Candidate>): String? {
        if (raw.isBlank() || candidates.isEmpty()) return null
        candidates.firstOrNull { it.id == raw }?.let { return it.id }
        val providedTail = decodeSegment(raw.substringAfterLast('/'))
        if (providedTail.isNotBlank()) {
            candidates.filter { candidate ->
                val refTail = decodeSegment(candidate.id.substringAfterLast('/'))
                val pathTail = candidate.label?.substringAfterLast('/') ?: refTail
                providedTail == refTail || providedTail == pathTail
            }.singleOrNull()?.let { return it.id }
        }
        if (candidates.size == 1 && (looksPlaceholder(raw) || !raw.startsWith("novex://"))) {
            return candidates[0].id
        }
        return null
    }

    /** 全零占位探测：连续≥16个 0 的串段，或 UUID 形的全零占位。 */
    private fun looksPlaceholder(raw: String): Boolean =
        Regex("0{16,}").containsMatchIn(raw) ||
            Regex("00000000-0000-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-0{12}").containsMatchIn(raw)

    /** 形似真实不透明编号（30+位编号字符）时不用唯一候选兜底，宁可报错列合法值。 */
    private fun looksOpaqueId(raw: String): Boolean =
        raw.length >= 30 && raw.all { it.isLetterOrDigit() || it in "-_:/.%" }

    /** 与 novex-core decodeRefSegment 同语义：%XX 解码、原始 UTF-8 段原样放行。 */
    private fun decodeSegment(value: String): String = runCatching {
        val bytes = java.io.ByteArrayOutputStream()
        var index = 0
        while (index < value.length) {
            if (value[index] == '%' && index + 2 < value.length) {
                bytes.write(value.substring(index + 1, index + 3).toInt(16))
                index += 3
            } else {
                val width = Character.charCount(Character.codePointAt(value, index))
                bytes.write(value.substring(index, index + width).toByteArray(Charsets.UTF_8))
                index += width
            }
        }
        String(bytes.toByteArray(), Charsets.UTF_8)
    }.getOrDefault(value)
}
