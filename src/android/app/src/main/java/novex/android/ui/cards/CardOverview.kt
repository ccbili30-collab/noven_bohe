package novex.android.ui.cards

import android.content.Context
import com.openminis.app.data.repository.ProviderRepository
import com.openminis.app.provider.LLMProvider
import novex.android.data.model.LLMMessage
import novex.android.data.model.ThinkingLevel
import novex.android.data.model.ModelEntry
import com.openminis.app.ui.sessions.isTextCapable
import novex.content.CardKind
import novex.content.ContentDocument
import novex.content.ContentModule
import org.json.JSONObject

/** [T008] 总览提纯元数据，存卡级扩展槽 lore-overview-meta（JSON 文本）。 */
data class OverviewMeta(
    val purifiedAtMs: Long? = null,
    val coveredModules: Int = 0,
    val approxTokens: Int = 0,
) {
    fun toJson(): String = JSONObject().apply {
        purifiedAtMs?.let { put("purifiedAt", it) }
        put("coveredModules", coveredModules)
        put("approxTokens", approxTokens)
    }.toString()

    companion object {
        fun parse(text: String?): OverviewMeta? = text?.let { raw ->
            runCatching {
                val json = JSONObject(raw)
                OverviewMeta(
                    purifiedAtMs = if (json.has("purifiedAt")) json.getLong("purifiedAt") else null,
                    coveredModules = json.optInt("coveredModules", 0),
                    approxTokens = json.optInt("approxTokens", 0),
                )
            }.getOrNull()
        }
    }
}

/**
 * 设定总览提纯协作者（SessionTitleRegenerator 同款形态）：只依赖仓库与
 * 应用上下文，不碰 CardSessionModel 状态。候选阶梯也与其一致——标题子模型
 * 优先，其余文本模型兜底；绝不自动调用，只响应用户点击。
 */
internal object OverviewPurifier {
    internal const val META_EXTENSION_KEY = "lore-overview-meta"

    private const val PER_MODULE_CHAR_CAP = 6000
    private const val TOTAL_CHAR_CAP = 42000
    private const val OUTPUT_MAX_CHARS = 1600

    private val SYSTEM_PROMPT = """
        你是设定集编辑。任务：把一张互动文游卡的设定模块提纯成"设定总览"——它将作为默认背景注入每轮对话。
        要求：
        - 只依据给定材料，不虚构、不新增设定、不臆测因果
        - 保留：身份与外貌性格核心、关键关系、能力与装备要点、世界基本盘、重要约束与边界
        - 丢弃：过场细节、重复表述、与主线无关的枝蔓
        - 篇幅 600~1200 字，分 3~6 段，每段一个主题
        - 每段末尾单独一行标注来源，格式精确为 [来源: 模块名]（只写给定材料里出现过的模块名）
        - 直接输出总览正文；不要前言、标题、代码块或解释
    """.trimIndent()

    /** 收集提纯来源：递归模块树，文本块按预算读取；返回 模块名→正文（可能截断）。 */
    suspend fun collectSources(card: ContentDocument, reader: suspend (novex.content.ContentRef, Long, Int) -> String): List<Pair<String, String>> {
        val sources = mutableListOf<Pair<String, String>>()
        var budget = TOTAL_CHAR_CAP
        suspend fun visit(modules: List<ContentModule>) {
            for (module in modules) {
                if (budget <= 0) return
                val text = StringBuilder()
                for (block in module.blocks) {
                    if (block !is novex.content.ContentBlock.Text) continue
                    if (budget <= 0) break
                    val want = minOf(PER_MODULE_CHAR_CAP, budget)
                    val part = runCatching { reader(block.content, 0, want) }.getOrNull().orEmpty()
                    if (part.isNotEmpty()) {
                        text.append(part)
                        budget -= part.length
                    }
                }
                val name = module.name.ifBlank { "未命名模块" }
                if (text.isNotEmpty()) sources.add(name to text.toString())
                visit(module.children)
            }
        }
        visit(card.bodyModules())
        return sources
    }

    fun buildPrompt(card: ContentDocument, sources: List<Pair<String, String>>): String = buildString {
        append(if (card.kind == CardKind.WORLD) "世界卡" else "角色卡")
        append("「").append(card.name.ifBlank { "未命名" }).append("」的全部设定模块如下。\n\n")
        for ((name, text) in sources) {
            append("== 模块：").append(name).append(" ==\n")
            append(text)
            if (text.length >= PER_MODULE_CHAR_CAP) append("\n（本模块过长，已截断）")
            append("\n\n")
        }
    }

    /** 候选模型有序尝试（标题子模型优先）；返回首个产出非空总览的模型结果。 */
    suspend fun purify(context: Context, card: ContentDocument, sources: List<Pair<String, String>>): Result<String> {
        if (sources.isEmpty()) return Result.failure(IllegalStateException("卡里还没有可提纯的模块内容"))
        val repository = ProviderRepository(context)
        val prompt = buildPrompt(card, sources)
        val candidates = orderedCandidates(repository)
        if (candidates.isEmpty()) return Result.failure(IllegalStateException("没有可用的文本模型，请先在设置里配置供应商"))
        var lastError: Exception? = null
        for (entry in candidates) {
            val provider = com.openminis.app.ui.sessions.buildProvider(repository, entry, context, "OverviewPurify")
                ?: continue
            try {
                val response = provider.sendMessage(
                    messages = listOf(LLMMessage(role = LLMMessage.Role.USER, content = prompt)),
                    systemPrompt = SYSTEM_PROMPT,
                    // 推理模型留思考余量；总览成品有字数上限，4096 足够。
                    maxTokens = if (entry.model.supportsReasoning == true) 8192 else 4096,
                    temperature = null,
                    thinkingLevel = ThinkingLevel.OFF,
                )
                val text = response.text.trim()
                    .removePrefix("```markdown").removePrefix("```").removeSuffix("```").trim()
                if (text.isNotEmpty()) return Result.success(text.take(OUTPUT_MAX_CHARS * 2))
                lastError = IllegalStateException("模型 ${entry.model.displayName} 返回了空总览")
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                lastError = e
            }
        }
        return Result.failure(lastError ?: IllegalStateException("全部候选模型都不可用"))
    }

    private fun orderedCandidates(repository: ProviderRepository): List<ModelEntry> {
        val textCapable = repository.allVisibleEntries().filter { it.isTextCapable() }
        val sub = repository.resolveTitleSubEntry()?.takeIf { s -> textCapable.any { it == s } }
        return listOfNotNull(sub) + textCapable.filter { it != sub }
    }
}

/** [T008] 段落级来源角标：提纯稿段落尾的 `[来源: 模块名]` 解析成 (正文, 来源?)。 */
internal fun overviewParagraphs(text: String): List<Pair<String, String?>> {
    val marker = Regex("\\s*\\[来源[:：]\\s*([^\\]]{1,40})]\\s*$")
    return text.split(Regex("\\n\\s*\\n")).mapNotNull { raw ->
        val paragraph = raw.trim()
        if (paragraph.isEmpty()) null
        else {
            val match = marker.find(paragraph)
            if (match != null) paragraph.removeRange(match.range) to match.groupValues[1].trim() else paragraph to null
        }
    }
}
