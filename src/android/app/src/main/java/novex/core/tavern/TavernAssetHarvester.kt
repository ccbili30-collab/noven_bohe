package novex.core.tavern

import novex.content.*
import novex.storage.*
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI
import java.util.UUID

/**
 * 导入素材收割器：把前端件页面里的远程 http(s) 引用抓回存为卡资源，
 * 正文改写为 novex-asset://<资源ID>，url→资源映射与收割报告写进卡扩展。
 *
 * 收割分两类：
 * - novex-def meta 的 remotePage：整页抓回后回填围栏正文（meta 摘除标记）
 * - 正文中的远程 URL：下载为 CardResource 并把字面引用替换为本地资源
 *
 * 全程操作草稿位：未完成导入的收割落在导入草稿上；用户已确认入库后
 * 草稿消失，收割器改为在已存卡上开填充草稿并直接提交（后台补齐）。
 * 卡已被丢弃时静默收场。中断（abort）只停抓取，已抓部分照常落盘。
 */
class TavernAssetHarvester(private val store: CardStore) {

    data class Progress(
        val total: Int,
        val done: Int,
        val failed: Int,
        val current: String?,
        val running: Boolean,
        /** 预算/时限耗尽或取消而未收割、仍保留远程引用的条目数。 */
        val deferred: Int = 0,
    )

    @Volatile private var aborted = false
    @Volatile private var active: HttpURLConnection? = null

    /** 中断抓取：断开当前连接，未抓条目记为 deferred。幂等。 */
    fun abort() {
        aborted = true
        runCatching { active?.disconnect() }
    }

    /** 当前卡里是否还有待收割的远程引用/远程页（收割前预估进度用）。 */
    fun pending(cardId: String): Int {
        val content = CardDrafts(store).read(cardId)?.content ?: store.open(cardId)?.content ?: return 0
        return collect(content).let { it.urls.size + it.pages.size - mappedUrls(content).size }
    }

    /**
     * 收割一张卡的全部远程素材。返回最终进度。
     * 在 IO 线程同步执行；abort() 可随时中断。
     */
    fun harvest(cardId: String, onProgress: (Progress) -> Unit = {}): Progress {
        aborted = false
        val drafts = CardDrafts(store)
        val initial = drafts.read(cardId)?.content ?: store.open(cardId)?.content ?: return Progress(0, 0, 0, null, false)
        val found = collect(initial)
        val seen = (mappedUrls(initial) + found.pages).toMutableSet()
        val queue = ArrayDeque(found.urls.filter { seen.add(it) }.take(MAX_URLS))
        var total = queue.size + found.pages.size
        if (total == 0) return Progress(0, 0, 0, null, false)
        emit(onProgress, total, 0, 0, null)

        val deadline = System.currentTimeMillis() + DEADLINE_MS
        val fetched = linkedMapOf<String, CardResource>()              // url → 资源（id/引用/mime）
        val pageBodies = linkedMapOf<String, String>()                 // url → html 正文
        var bytes = 0L
        var done = 0
        var failed = 0

        fun fetch(url: String): Pair<ByteArray, String?>? {
            if (aborted || System.currentTimeMillis() > deadline) return null
            return runCatching {
                val conn = (URI(url).toURL().openConnection() as HttpURLConnection).also { active = it }
                conn.connectTimeout = CONNECT_MS; conn.readTimeout = READ_MS
                conn.instanceFollowRedirects = true
                try {
                    if (conn.responseCode !in 200..299) return@runCatching null
                    conn.inputStream.use { readCapped(it, PER_FILE_BYTES) }
                        ?.let { it to conn.contentType?.substringBefore(';') }
                } finally {
                    conn.disconnect(); active = null
                }
            }.getOrNull()
        }

        // 先收割远程引导页（页本身是内容载体）；页内新发现的引用并入素材队列
        for (url in found.pages) {
            if (aborted || System.currentTimeMillis() > deadline) break
            emit(onProgress, total, done, failed, url)
            val body = fetch(url)?.first?.let { runCatching { String(it, Charsets.UTF_8) }.getOrNull() }
            if (body != null) {
                pageBodies[url] = body; done++
                URL_LITERAL.findAll(body).forEach { u ->
                    if (seen.add(u.value) && queue.size < MAX_URLS) { queue += u.value; total++ }
                }
            } else { if (aborted) break; failed++ }
        }
        while (queue.isNotEmpty()) {
            val url = queue.removeFirst()
            if (aborted || System.currentTimeMillis() > deadline || bytes >= TOTAL_BYTES) break
            emit(onProgress, total, done, failed, url)
            val body = fetch(url)
            if (body == null) { if (aborted) break; failed++; continue }
            val ref = store.contents.allocator()()
            runCatching { store.contents.receive(listOf(ContentTransfer(ContentRef("harvest"), ref))) { body.first.inputStream() } }
                .onSuccess {
                    fetched[url] = CardResource(UUID.randomUUID().toString(), ref, body.second ?: mimeOf(url))
                    bytes += body.first.size
                    done++
                }
                .onFailure { failed++ }
        }
        val deferred = total - done - failed
        if (fetched.isEmpty() && pageBodies.isEmpty()) return Progress(total, done, failed, null, false, deferred)

        // 最终一次性落盘：回填远程页 + 改写引用 + 加资源 + 写映射与报告扩展。
        // 草稿可能已随「确认导入」提交消失——改为在已存卡上开填充草稿并直接提交。
        var fillIn = false
        val draft = drafts.read(cardId) ?: run {
            if (store.open(cardId) == null) return Progress(total, done, failed, null, false, deferred)
            fillIn = true
            runCatching { drafts.begin(cardId) }.getOrNull() ?: return Progress(total, done, failed, null, false, deferred)
        }
        val rewritten = rewrite(draft.content, fetched.mapValues { it.value.id }, pageBodies)
        // url→资源映射：保留既有条目，并入本轮成果
        val mergedMap = JSONObject()
        existingMap(draft.content).forEach { (u, id) -> mergedMap.put(u, id) }
        fetched.forEach { (u, res) -> mergedMap.put(u, res.id) }
        val resources = fetched.values.toList()
        val report = JSONObject()
            .put("total", total).put("done", done).put("failed", failed)
            .put("deferred", deferred).put("aborted", aborted)
        fun text(value: String): ContentRef {
            val ref = store.contents.allocator()()
            store.contents.receive(listOf(ContentTransfer(ContentRef("text"), ref))) { value.byteInputStream() }
            return ref
        }
        val content = rewritten.copy(
            resources = rewritten.resources + resources,
            extensions = rewritten.extensions +
                (URL_MAP_EXT to text(mergedMap.toString())) +
                (HARVEST_EXT to text(report.toString())),
        ).validate()
        return runCatching {
            val updated = drafts.update(cardId, draft.version, content, draft.position, ChangeSource.IMPORT)
            if (fillIn) runCatching { drafts.commit(cardId, updated.version) }
            Progress(total, done, failed, null, false, deferred)
        }.getOrElse { Progress(total, done, failed, null, false, deferred) }
    }

    private fun emit(onProgress: (Progress) -> Unit, total: Int, done: Int, failed: Int, current: String?) =
        onProgress(Progress(total, done, failed, current, true))

    /** 扫描 novex-frontend 模块正文：远程页标记 + 全部 http(s) 字面引用。 */
    private data class Found(val urls: LinkedHashSet<String>, val pages: LinkedHashSet<String>)

    private fun collect(content: ContentDocument): Found {
        val urls = LinkedHashSet<String>()
        val pages = LinkedHashSet<String>()
        fun visit(modules: List<ContentModule>) {
            modules.forEach { m ->
                if ("novex-frontend" in m.tags) {
                    m.blocks.filterIsInstance<ContentBlock.Text>().forEach { b ->
                        val text = runCatching { store.contents.open(b.content).use { it.readBytes().toString(Charsets.UTF_8) } }.getOrNull() ?: return@forEach
                        DEF_FENCE.findAll(text).forEach { f ->
                            runCatching { JSONObject(f.groupValues[1]).optString("remotePage") }
                                .getOrNull()?.takeIf(String::isNotBlank)?.let { pages += it }
                        }
                        URL_LITERAL.findAll(text).forEach { urls += it.value }
                    }
                }
                visit(m.children)
            }
        }
        visit(content.modules)
        content.internalCharacters.forEach { visit(it.modules) }
        pages.forEach { urls.remove(it) }
        return Found(urls, pages)
    }

    /** 已收割过的 URL（映射表键），重复收割时跳过。 */
    private fun existingMap(content: ContentDocument): Map<String, String> =
        content.extensions[URL_MAP_EXT]?.let { ref ->
            runCatching {
                val json = JSONObject(store.contents.open(ref).use { it.readBytes().toString(Charsets.UTF_8) })
                json.keys().asSequence().associateWith { json.getString(it) }
            }.getOrNull()
        } ?: emptyMap()

    private fun mappedUrls(content: ContentDocument) = existingMap(content).keys

    /** 回填远程页正文 + 替换素材引用为 novex-asset://<资源ID>。 */
    private fun rewrite(content: ContentDocument, fetched: Map<String, String>, pages: Map<String, String>): ContentDocument {
        fun rewriteText(text: String): String {
            var out = DEF_FENCE.replace(text) { f ->
                val meta = runCatching { JSONObject(f.groupValues[1]) }.getOrNull() ?: return@replace f.value
                val url = meta.optString("remotePage")
                val body = pages[url]
                if (url.isBlank() || body == null) return@replace f.value
                meta.remove("remotePage")
                "```novex-def\n$meta\n$body\n```"
            }
            fetched.entries.sortedByDescending { it.key.length }.forEach { (url, id) ->
                out = out.replace(url, "novex-asset://$id")
            }
            return out
        }
        fun visit(modules: List<ContentModule>): List<ContentModule> = modules.map { m ->
            if ("novex-frontend" !in m.tags && m.children.isEmpty()) m else m.copy(
                blocks = m.blocks.map { b ->
                    if (b !is ContentBlock.Text || "novex-frontend" !in m.tags) b
                    else {
                        val old = runCatching { store.contents.open(b.content).use { it.readBytes().toString(Charsets.UTF_8) } }.getOrNull()
                        if (old == null) b else {
                            val next = rewriteText(old)
                            if (next == old) b else {
                                val ref = store.contents.allocator()()
                                store.contents.receive(listOf(ContentTransfer(ContentRef("text"), ref))) { next.byteInputStream() }
                                ContentBlock.Text(b.id, ref)
                            }
                        }
                    }
                },
                children = visit(m.children),
            )
        }
        return content.copy(
            modules = visit(content.modules),
            internalCharacters = content.internalCharacters.map { it.copy(modules = visit(it.modules)) },
        )
    }

    /** 截断读取：超过上限返回 null（minSdk 26 无 InputStream.readNBytes）。 */
    private fun readCapped(input: java.io.InputStream, max: Int): ByteArray? {
        val out = java.io.ByteArrayOutputStream(minOf(max, 65536))
        val chunk = ByteArray(65536)
        var total = 0
        while (total <= max) {
            val n = input.read(chunk, 0, minOf(chunk.size, max + 1 - total))
            if (n < 0) break
            total += n
            out.write(chunk, 0, n)
        }
        return out.toByteArray().takeIf { it.size <= max }
    }

    private fun mimeOf(url: String): String =
        java.net.URLConnection.guessContentTypeFromName(url.substringBefore('?'))
            ?: EXT_MIMES[url.substringBefore('?').substringAfterLast('.', "").lowercase()]
            ?: "application/octet-stream"

    companion object {
        const val URL_MAP_EXT = "novex.tavern.url-map"
        const val HARVEST_EXT = "novex.tavern.harvest"
        private const val CONNECT_MS = 3000
        private const val READ_MS = 5000
        private const val DEADLINE_MS = 30_000L
        private const val PER_FILE_BYTES = 8 * 1024 * 1024
        private const val TOTAL_BYTES = 40L * 1024 * 1024
        private const val MAX_URLS = 300
        private val URL_LITERAL = Regex("""https?://[^\s"'<>\)\]\\}，。；]+""")
        private val DEF_FENCE = Regex("```novex-def\\s*\\n(\\{[^\\n]*\\})\\n(.*?)\\n?```", RegexOption.DOT_MATCHES_ALL)
        private val EXT_MIMES = mapOf(
            "png" to "image/png", "jpg" to "image/jpeg", "jpeg" to "image/jpeg",
            "gif" to "image/gif", "webp" to "image/webp", "svg" to "image/svg+xml",
            "avif" to "image/avif", "ico" to "image/x-icon",
            "mp3" to "audio/mpeg", "ogg" to "audio/ogg", "wav" to "audio/wav", "m4a" to "audio/mp4",
            "mp4" to "video/mp4", "webm" to "video/webm",
            "css" to "text/css", "js" to "text/javascript",
            "woff" to "font/woff", "woff2" to "font/woff2", "ttf" to "font/ttf", "otf" to "font/otf",
        )
    }
}
