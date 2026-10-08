package com.openminis.app.tools

import android.content.Context
import com.openminis.app.data.repository.WebAccessSettingsRepository
import novex.android.data.model.AgentToolDefinition
import novex.android.data.model.AgentToolParam
import novex.android.repo.WebSearchClient
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * web_fetch：通用单页抓取工具。与搜索后端无关——任何模型都能用，
 * 抓到 HTML 后剥离标签给正文。护栏：仅 http/https、单次响应体上
 * 限、超时收紧；失败按「网络不通 / HTTP 错误 / 非网页」分开报。
 */
object WebFetchTool {
    const val NAME = "web_fetch"
    private const val MAX_BODY_BYTES = 512 * 1024
    private const val MAX_RETURN_CHARS = 20_000

    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    fun definition(): AgentToolDefinition = AgentToolDefinition(
        name = NAME,
        description = "Fetch one web page and return its main text. Use after web_search to open a result, " +
            "or when the user gives you a URL to read. Only http/https pages; no logins or APIs that need credentials.",
        parameters = mapOf(
            "tool_title" to AgentToolParam("string", "A concise 5-10 word summary of what this tool call does, shown to the user. Use the same language as the user."),
            "url" to AgentToolParam("string", "The http(s) page URL to fetch."),
        ),
        required = listOf("tool_title", "url"),
        propertyOrdering = listOf("tool_title", "url"),
    )

    fun execute(argsJson: String): ToolExecutionResult {
        val args = runCatching { JSONObject(argsJson) }.getOrNull()
            ?: return ToolExecutionResult("Error: invalid JSON arguments", false, toolTitle = NAME)
        val title = args.optString("tool_title", NAME)
        val url = args.optString("url", "").trim()
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            return ToolExecutionResult(
                "Error: web_fetch only accepts http(s) URLs — got: $url",
                false, toolTitle = title,
            )
        }
        return try {
            http.newCall(Request.Builder().url(url).get().build()).execute().use { response ->
                if (!response.isSuccessful) {
                    return ToolExecutionResult(
                        "Error: fetch failed (HTTP ${response.code}) for $url",
                        false, toolTitle = title,
                    )
                }
                val body = response.body ?: return ToolExecutionResult(
                    "Error: empty response body", false, toolTitle = title,
                )
                // 限流读：超长页面只取前段，不整包进内存。
                val raw = body.byteStream().use { stream ->
                    val buffer = java.io.ByteArrayOutputStream()
                    val chunk = ByteArray(8 * 1024)
                    var total = 0
                    while (total < MAX_BODY_BYTES) {
                        val read = stream.read(chunk)
                        if (read <= 0) break
                        buffer.write(chunk, 0, read)
                        total += read
                    }
                    buffer.toString("UTF-8")
                }
                val contentType = response.header("Content-Type").orEmpty()
                val text = if (contentType.contains("html") || raw.trimStart().startsWith("<")) {
                    htmlToText(raw)
                } else {
                    raw
                }
                if (text.isBlank()) {
                    ToolExecutionResult("Error: page produced no readable text", false, toolTitle = title)
                } else {
                    ToolExecutionResult(text.take(MAX_RETURN_CHARS), true, toolTitle = title)
                }
            }
        } catch (e: Exception) {
            ToolExecutionResult(
                "Error: network failure for $url (${e.javaClass.simpleName}: ${e.message})",
                false, toolTitle = title,
            )
        }
    }

    /** 极简 HTML→文本：去 script/style、标签、常见实体。给模型读足够。 */
    internal fun htmlToText(html: String): String {
        var t = html
        t = t.replace(Regex("(?is)<(script|style|noscript|svg|head)[^>]*>.*?</\\1>"), " ")
        t = t.replace(Regex("(?is)<br\\s*/?>"), "\n")
        t = t.replace(Regex("(?is)</(p|div|li|tr|h[1-6]|section|article|blockquote)>"), "\n")
        t = t.replace(Regex("(?is)<[^>]+>"), " ")
        t = t.replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<")
            .replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'")
        return t.lines()
            .map { it.replace(Regex("\\s+"), " ").trim() }
            .filter { it.isNotEmpty() }
            .joinToString("\n")
    }
}

/**
 * web_search：搜索工具。本身不做请求——把查询转给设置里已配置的
 * 后端（智谱 Coding Plan / 智谱按量 / Tavily / Brave，按优先级取第
 * 一家有 key 的）。没配 key 时 schema 里不声明它（模型不会见到），
 * 这里再兜一次防御性报错。
 */
object WebSearchTool {
    const val NAME = "web_search"

    fun definition(): AgentToolDefinition = AgentToolDefinition(
        name = NAME,
        description = "Search the web for current information. Returns title/URL/snippet list. " +
            "Open promising results with web_fetch to read the full page.",
        parameters = mapOf(
            "tool_title" to AgentToolParam("string", "A concise 5-10 word summary of what this tool call does, shown to the user. Use the same language as the user."),
            "query" to AgentToolParam("string", "The search query in the language most likely to yield good results."),
            "count" to AgentToolParam("integer", "Desired number of results (default 5, max depends on backend)."),
        ),
        required = listOf("tool_title", "query"),
        propertyOrdering = listOf("tool_title", "query", "count"),
    )

    suspend fun execute(argsJson: String, context: Context): ToolExecutionResult {
        val args = runCatching { JSONObject(argsJson) }.getOrNull()
            ?: return ToolExecutionResult("Error: invalid JSON arguments", false, toolTitle = NAME)
        val title = args.optString("tool_title", NAME)
        val query = args.optString("query", "").trim()
        if (query.isEmpty()) {
            return ToolExecutionResult("Error: empty query", false, toolTitle = title)
        }
        val repo = WebAccessSettingsRepository(context)
        val active = repo.activeBackend()
            ?: return ToolExecutionResult(
                "Error: web_search is not configured. Ask the user to add a search backend key in Settings → 联网.",
                false, toolTitle = title,
            )
        val count = args.optInt("count", 5).coerceAtLeast(1)
        return when (
            val outcome = WebSearchClient().search(active.first, active.second, query, count)
        ) {
            is WebSearchClient.SearchOutcome.Ok -> {
                if (outcome.hits.isEmpty()) {
                    ToolExecutionResult("No results for: $query", true, toolTitle = title)
                } else {
                    val body = buildString {
                        appendLine("Search results ($query, via ${active.first.displayName}):")
                        outcome.hits.forEachIndexed { i, hit ->
                            appendLine("${i + 1}. ${hit.title}")
                            if (hit.url.isNotEmpty()) appendLine("   ${hit.url}")
                            if (hit.snippet.isNotEmpty()) appendLine("   ${hit.snippet.take(300)}")
                        }
                    }
                    ToolExecutionResult(body.trim(), true, toolTitle = title)
                }
            }
            is WebSearchClient.SearchOutcome.Http ->
                ToolExecutionResult(
                    "Error: search backend ${active.first.displayName} returned HTTP ${outcome.code} (key invalid or quota exhausted?)",
                    false, toolTitle = title,
                )
            is WebSearchClient.SearchOutcome.Net ->
                ToolExecutionResult(
                    "Error: search backend unreachable (${outcome.detail})",
                    false, toolTitle = title,
                )
            is WebSearchClient.SearchOutcome.Malformed ->
                ToolExecutionResult(
                    "Error: unexpected response from search backend (${outcome.detail})",
                    false, toolTitle = title,
                )
        }
    }
}
