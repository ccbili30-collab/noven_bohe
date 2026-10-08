package novex.android.repo

import com.openminis.app.data.repository.WebAccessSettingsRepository.SearchBackend
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * 联网搜索后端客户端：web_search 工具的真正执行层。
 *
 * 后端形态两种：
 *  - REST 直查（智谱按量 / Tavily / Brave）——一次 HTTP 调用拿结果；
 *  - 智谱 Coding Plan 的 MCP（streamable HTTP）——initialize 握手拿
 *    mcp-session-id，再 notifications/initialized，最后 tools/call；
 *    应答可能是 application/json 或 text/event-stream，两种都解。
 *
 * 所有后端归一化成 [SearchHit] 列表抛给工具层，模型不感知差异。
 */
class WebSearchClient {

    data class SearchHit(val title: String, val url: String, val snippet: String)

    /** 搜索失败定性：配置缺失 / 网络层 / HTTP / 应答体不合法。 */
    sealed interface SearchOutcome {
        class Ok(val hits: List<SearchHit>) : SearchOutcome
        class Http(val code: Int) : SearchOutcome
        class Net(val detail: String) : SearchOutcome
        class Malformed(val detail: String) : SearchOutcome
    }

    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .build()

    suspend fun search(
        backend: SearchBackend,
        apiKey: String,
        query: String,
        count: Int,
    ): SearchOutcome = when (backend) {
        SearchBackend.ZHIPU_PAYG -> zhipuPayg(apiKey, query, count)
        SearchBackend.ZHIPU_CODING_PLAN -> zhipuCodingPlanMcp(apiKey, query)
        SearchBackend.TAVILY -> tavily(apiKey, query, count)
        SearchBackend.BRAVE -> brave(apiKey, query, count)
    }

    // ── 智谱按量：POST /api/paas/v4/web_search ─────────────────────────

    private fun zhipuPayg(apiKey: String, query: String, count: Int): SearchOutcome {
        val body = JSONObject()
            .put("search_query", query)
            .put("search_engine", "search_std")
            .put("count", count.coerceIn(1, 50))
            .put("search_domain_filter", "")
            .toString()
            .toRequestBody(JSON_MEDIA)
        return postJson(
            url = "https://open.bigmodel.cn/api/paas/v4/web_search",
            body = body,
            headers = mapOf("Authorization" to "Bearer $apiKey"),
        ) { json ->
            val items = json.optJSONArray("results") ?: return@postJson null
            buildList {
                for (i in 0 until items.length()) {
                    val it = items.optJSONObject(i) ?: continue
                    val link = it.optString("link").ifEmpty { it.optString("url") }
                    add(
                        SearchHit(
                            title = it.optString("title"),
                            url = link,
                            snippet = it.optString("content").ifEmpty { it.optString("summary") },
                        ),
                    )
                }
            }
        }
    }

    // ── 智谱 Coding Plan：web_search_prime MCP（streamable HTTP）───────

    private fun zhipuCodingPlanMcp(apiKey: String, query: String): SearchOutcome {
        val endpoint = "https://open.bigmodel.cn/api/mcp/web_search_prime/mcp"
        val baseHeaders = mapOf(
            "Authorization" to "Bearer $apiKey",
            "Accept" to "application/json, text/event-stream",
        )
        // 1) initialize —— 握手拿会话 id。
        val initRequest = Request.Builder().url(endpoint).post(
            JSONObject()
                .put("jsonrpc", "2.0").put("id", 1)
                .put("method", "initialize")
                .put(
                    "params",
                    JSONObject()
                        .put("protocolVersion", "2024-11-05")
                        .put("capabilities", JSONObject())
                        .put("clientInfo", JSONObject().put("name", "novex").put("version", "1.0")),
                )
                .toString().toRequestBody(JSON_MEDIA),
        ).apply { baseHeaders.forEach { (k, v) -> header(k, v) } }.build()
        val sessionId = try {
            http.newCall(initRequest).execute().use { response ->
                if (!response.isSuccessful) return SearchOutcome.Http(response.code)
                response.header("mcp-session-id") ?: ""
            }
        } catch (e: IOException) {
            return SearchOutcome.Net(e.message ?: e.javaClass.simpleName)
        }
        val sessionHeaders = if (sessionId.isEmpty()) {
            baseHeaders
        } else {
            baseHeaders + ("mcp-session-id" to sessionId)
        }
        // 2) initialized 通知（无 id；失败不致命，继续 tools/call 试）。
        runCatching {
            http.newCall(
                Request.Builder().url(endpoint).post(
                    JSONObject()
                        .put("jsonrpc", "2.0")
                        .put("method", "notifications/initialized")
                        .toString().toRequestBody(JSON_MEDIA),
                ).apply { sessionHeaders.forEach { (k, v) -> header(k, v) } }.build(),
            ).execute().close()
        }
        // 3) tools/call。
        val callBody = JSONObject()
            .put("jsonrpc", "2.0").put("id", 2)
            .put("method", "tools/call")
            .put(
                "params",
                JSONObject()
                    .put("name", "web_search_prime")
                    .put("arguments", JSONObject().put("search_query", query)),
            )
            .toString().toRequestBody(JSON_MEDIA)
        return try {
            http.newCall(
                Request.Builder().url(endpoint).post(callBody)
                    .apply { sessionHeaders.forEach { (k, v) -> header(k, v) } }.build(),
            ).execute().use { response ->
                if (!response.isSuccessful) return SearchOutcome.Http(response.code)
                val payload = readJsonRpcPayload(response.body?.string() ?: "")
                    ?: return SearchOutcome.Malformed("empty MCP response")
                parseMcpToolResult(payload)
            }
        } catch (e: IOException) {
            SearchOutcome.Net(e.message ?: e.javaClass.simpleName)
        }
    }

    /**
     * streamable HTTP 应答可能是单个 JSON，也可能是 SSE（`data:` 行序
     * 列）。从所有 data 载荷里找带 result/error 的 JSON-RPC 消息。
     */
    private fun readJsonRpcPayload(raw: String): JSONObject? {
        val trimmed = raw.trim()
        if (trimmed.startsWith("{")) {
            return runCatching { JSONObject(trimmed) }.getOrNull()
        }
        var last: JSONObject? = null
        for (line in trimmed.lineSequence()) {
            val data = line.removePrefix("data:").trim()
            if (data.isEmpty() || !data.startsWith("{")) continue
            val obj = runCatching { JSONObject(data) }.getOrNull() ?: continue
            if (obj.has("result") || obj.has("error")) last = obj
        }
        return last
    }

    private fun parseMcpToolResult(rpc: JSONObject): SearchOutcome {
        rpc.optJSONObject("error")?.let {
            return SearchOutcome.Malformed("MCP error ${it.optInt("code")}: ${it.optString("message")}")
        }
        val content = rpc.optJSONObject("result")?.optJSONArray("content")
            ?: return SearchOutcome.Malformed("MCP result has no content")
        val hits = mutableListOf<SearchHit>()
        val extras = StringBuilder()
        for (i in 0 until content.length()) {
            val item = content.optJSONObject(i) ?: continue
            if (item.optString("type") != "text") continue
            val text = item.optString("text")
            hits += parseSearchHitsFromText(text)
            if (hits.isEmpty()) extras.append(text).append('\n')
        }
        return when {
            hits.isNotEmpty() -> SearchOutcome.Ok(hits)
            extras.isNotEmpty() -> SearchOutcome.Ok(
                // 工具返回的是自由文本（部分引擎给 markdown 列表）——原样
                // 交给模型好过丢成空结果。
                listOf(SearchHit(title = "web_search_prime", url = "", snippet = extras.toString().trim())),
            )
            else -> SearchOutcome.Malformed("MCP result content was empty")
        }
    }

    /** 从 MCP 文本载荷里尽力解析结构化结果：优先 JSON 数组/对象。 */
    private fun parseSearchHitsFromText(text: String): List<SearchHit> {
        val trimmed = text.trim()
        val array = when {
            trimmed.startsWith("[") -> runCatching { JSONArray(trimmed) }.getOrNull()
            trimmed.startsWith("{") -> {
                val obj = runCatching { JSONObject(trimmed) }.getOrNull() ?: return emptyList()
                obj.optJSONArray("results") ?: obj.optJSONArray("data") ?: obj.optJSONArray("items")
            }
            else -> null
        } ?: return emptyList()
        return buildList {
            for (i in 0 until array.length()) {
                val it = array.optJSONObject(i) ?: continue
                val link = it.optString("link").ifEmpty { it.optString("url") }
                add(
                    SearchHit(
                        title = it.optString("title"),
                        url = link,
                        snippet = it.optString("content")
                            .ifEmpty { it.optString("summary") }
                            .ifEmpty { it.optString("description") },
                    ),
                )
            }
        }
    }

    // ── Tavily ───────────────────────────────────────────────────────

    private fun tavily(apiKey: String, query: String, count: Int): SearchOutcome {
        val body = JSONObject()
            .put("api_key", apiKey)
            .put("query", query)
            .put("max_results", count.coerceIn(1, 20))
            .put("search_depth", "basic")
            .toString().toRequestBody(JSON_MEDIA)
        return postJson("https://api.tavily.com/search", body) { json ->
            val items = json.optJSONArray("results") ?: return@postJson null
            buildList {
                for (i in 0 until items.length()) {
                    val it = items.optJSONObject(i) ?: continue
                    add(SearchHit(it.optString("title"), it.optString("url"), it.optString("content")))
                }
            }
        }
    }

    // ── Brave ────────────────────────────────────────────────────────

    private fun brave(apiKey: String, query: String, count: Int): SearchOutcome {
        val url = "https://api.search.brave.com/res/v1/web/search?q=" +
            java.net.URLEncoder.encode(query, "UTF-8") + "&count=${count.coerceIn(1, 20)}"
        return try {
            http.newCall(
                Request.Builder().url(url).get()
                    .header("X-Subscription-Token", apiKey)
                    .header("Accept", "application/json")
                    .build(),
            ).execute().use { response ->
                if (!response.isSuccessful) return SearchOutcome.Http(response.code)
                val json = runCatching { JSONObject(response.body!!.string()) }.getOrNull()
                    ?: return SearchOutcome.Malformed("non-JSON body")
                val items = json.optJSONObject("web")?.optJSONArray("results")
                    ?: return SearchOutcome.Malformed("missing web.results")
                SearchOutcome.Ok(
                    buildList {
                        for (i in 0 until items.length()) {
                            val it = items.optJSONObject(i) ?: continue
                            add(SearchHit(it.optString("title"), it.optString("url"), it.optString("description")))
                        }
                    },
                )
            }
        } catch (e: IOException) {
            SearchOutcome.Net(e.message ?: e.javaClass.simpleName)
        }
    }

    // ── 公共 POST/JSON 骨架 ──────────────────────────────────────────

    private fun postJson(
        url: String,
        body: okhttp3.RequestBody,
        headers: Map<String, String> = emptyMap(),
        decode: (JSONObject) -> List<SearchHit>?,
    ): SearchOutcome {
        return try {
            val builder = Request.Builder().url(url).post(body)
            headers.forEach { (k, v) -> builder.header(k, v) }
            http.newCall(builder.build()).execute().use { response ->
                if (!response.isSuccessful) return SearchOutcome.Http(response.code)
                val json = runCatching { JSONObject(response.body!!.string()) }.getOrNull()
                    ?: return SearchOutcome.Malformed("non-JSON body")
                val hits = decode(json)
                    ?: return SearchOutcome.Malformed("missing results field")
                SearchOutcome.Ok(hits)
            }
        } catch (e: IOException) {
            SearchOutcome.Net(e.message ?: e.javaClass.simpleName)
        }
    }

    private companion object {
        val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
    }
}
