package com.openminis.app.tools

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-alias-resolver] 弱模型回放契约：外接反馈会话（880ea966）里模型发出的
 * 全零占位/错抄参数，逐一验证解析层能接回真实编号；歧义场景必须不猜。
 */
class NovexToolArgResolverTest {

    private val cardRoot = "doccard-4941d60b-b768-35a5-bd90-ad1269103db2"
    private val docRef = "novex://documents/e38525394b727e01b13c765d56cc87c6d4fc5905151c7037792249ed35570b0e"
    private val collRef = "novex://source-collections/205631fdca596427f6e8a9615e1c3e5fad6786b2e32e2aabf84847dc2ce904ef"
    private val wsRef = "novex://workspaces/880ea966-0705-4297-ad37-450240004b3c/branches/94d51a05-3ed4-4924-9040-16f0b425accf/sources/learning/e741c83c01bf1135373a142a/%E6%B2%A7%E5%B2%9A%E9%97%B4-%E8%AE%BE%E5%AE%9A%E9%9B%86-e3852539.txt"
    private val wsRelative = "sources/learning/e741c83c01bf1135373a142a/沧岚间-设定集-e3852539.txt"

    private fun scope(
        targets: List<NovexToolArgResolver.Candidate> = listOf(
            NovexToolArgResolver.Candidate(cardRoot, label = "沧岚间世界卡", group = cardRoot),
        ),
        roots: List<NovexToolArgResolver.Candidate> = listOf(
            NovexToolArgResolver.Candidate(cardRoot, label = "沧岚间世界卡"),
        ),
        docs: List<NovexToolArgResolver.Candidate> = listOf(
            NovexToolArgResolver.Candidate(docRef, label = "沧岚间-设定集.txt"),
        ),
        colls: List<NovexToolArgResolver.Candidate> = listOf(
            NovexToolArgResolver.Candidate(collRef),
        ),
        ws: List<NovexToolArgResolver.Candidate> = listOf(
            NovexToolArgResolver.Candidate(wsRef, label = wsRelative),
        ),
        draft: String = "draft-9f8e7d6c",
    ) = NovexToolArgResolver.Scope(
        scopeRoots = { roots },
        scopeTargets = { targets },
        documents = { docs },
        collections = { colls },
        workspaceRefs = { ws },
        draftToken = { draft },
    )

    // ---------- 会话回放：全零占位 ----------

    @Test
    fun `all-zero document ref resolves to the only attached document`() {
        val args = """{"document_ref":"novex://documents/0000000000000000000000000000000000000000000000000000000000000000"}"""
        val resolved = JSONObject(NovexToolArgResolver.resolve(args, scope()))
        assertEquals(docRef, resolved.getString("document_ref"))
    }

    @Test
    fun `all-zero card target resolves to the only scope target`() {
        val args = """{"root_id":"doccard-00000000-0000-4000-8000-000000000000","target_id":"doccard-00000000-0000-4000-8000-000000000000"}"""
        val resolved = JSONObject(NovexToolArgResolver.resolve(args, scope()))
        assertEquals(cardRoot, resolved.getString("root_id"))
        assertEquals(cardRoot, resolved.getString("target_id"))
    }

    @Test
    fun `workspace ref with zeroed uuid segments resolves by filename tail`() {
        val corrupted = "novex://workspaces/00000000-0000-4000-8000-000000000000/branches/00000000-0000-4000-8000-000000000000/sources/learning/e741c83c01bf1135373a142a/%E6%B2%A7%E5%B2%9A%E9%97%B4-%E8%AE%BE%E5%AE%9A%E9%9B%86-e3852539.txt"
        val args = """{"workspace_ref":"$corrupted"}"""
        val resolved = JSONObject(NovexToolArgResolver.resolve(args, scope()))
        assertEquals(wsRef, resolved.getString("workspace_ref"))
    }

    @Test
    fun `workspace ref with raw utf8 filename tail also resolves`() {
        val corrupted = "novex://workspaces/00000000-0000-4000-8000-000000000000/branches/00000000-0000-4000-8000-000000000000/sources/learning/e741c83c01bf1135373a142a/沧岚间-设定集-e3852539.txt"
        val args = JSONObject().put("workspace_ref", corrupted).toString()
        val resolved = JSONObject(NovexToolArgResolver.resolve(args, scope()))
        assertEquals(wsRef, resolved.getString("workspace_ref"))
    }

    // ---------- 合法输入必须原样放行 ----------

    @Test
    fun `correct refs pass through untouched`() {
        val args = """{"document_ref":"$docRef","collection_ref":"$collRef","workspace_ref":"$wsRef"}"""
        assertEquals(
            JSONObject(args).toString(),
            JSONObject(NovexToolArgResolver.resolve(args, scope())).toString(),
        )
    }

    @Test
    fun `unresolvable real-looking id is left for downstream errors`() {
        val other = "novex://documents/aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        val args = """{"document_ref":"$other"}"""
        val resolved = JSONObject(NovexToolArgResolver.resolve(args, scope()))
        assertEquals(other, resolved.getString("document_ref"))
    }

    // ---------- 歧义不猜 ----------

    @Test
    fun `placeholder with multiple candidates is not guessed`() {
        val twoDocs = scope(
            docs = listOf(
                NovexToolArgResolver.Candidate(docRef, label = "甲"),
                NovexToolArgResolver.Candidate(
                    "novex://documents/" + "b".repeat(64), label = "乙"),
            ),
        )
        val args = """{"document_ref":"novex://documents/${"0".repeat(64)}"}"""
        val resolved = JSONObject(NovexToolArgResolver.resolve(args, twoDocs))
        assertEquals("novex://documents/${"0".repeat(64)}", resolved.getString("document_ref"))
    }

    // ---------- 名称与前缀 ----------

    @Test
    fun `card name resolves to root id`() {
        val args = """{"root_id":"沧岚间世界卡","target_id":"$cardRoot"}"""
        val resolved = JSONObject(NovexToolArgResolver.resolve(args, scope()))
        assertEquals(cardRoot, resolved.getString("root_id"))
    }

    @Test
    fun `unique id prefix resolves`() {
        val args = """{"document_ref":"${docRef.take(40)}"}"""
        val resolved = JSONObject(NovexToolArgResolver.resolve(args, scope()))
        assertEquals(docRef, resolved.getString("document_ref"))
    }

    // ---------- draft_version ----------

    @Test
    fun `draft_version latest resolves to current token`() {
        val args = """{"root_id":"$cardRoot","target_id":"$cardRoot","draft_version":"latest"}"""
        val resolved = JSONObject(NovexToolArgResolver.resolve(args, scope()))
        assertEquals("draft-9f8e7d6c", resolved.getString("draft_version"))
    }

    @Test
    fun `placeholder draft_version resolves to current token`() {
        val args = """{"root_id":"$cardRoot","target_id":"$cardRoot","draft_version":"00000000-0000-4000-8000-000000000000"}"""
        val resolved = JSONObject(NovexToolArgResolver.resolve(args, scope()))
        assertEquals("draft-9f8e7d6c", resolved.getString("draft_version"))
    }

    @Test
    fun `stale but real draft_version is left for conflict handling`() {
        val args = """{"root_id":"$cardRoot","target_id":"$cardRoot","draft_version":"draft-deadbeef"}"""
        val resolved = JSONObject(NovexToolArgResolver.resolve(args, scope()))
        assertEquals("draft-deadbeef", resolved.getString("draft_version"))
    }

    // ---------- input_refs 数组 ----------

    @Test
    fun `input_refs array elements resolve individually`() {
        val corrupted = "novex://workspaces/00000000-0000-4000-8000-000000000000/branches/00000000-0000-4000-8000-000000000000/sources/learning/e741c83c01bf1135373a142a/沧岚间-设定集-e3852539.txt"
        val args = JSONObject().put("input_refs", org.json.JSONArray(listOf(corrupted))).toString()
        val resolved = JSONObject(NovexToolArgResolver.resolve(args, scope()))
        assertEquals(wsRef, resolved.getJSONArray("input_refs").getString(0))
    }

    // ---------- 早期判停 ----------

    @Test
    fun `hasResolvableParams skips tools without id params`() {
        assertTrue(!NovexToolArgResolver.hasResolvableParams("""{"name":"问候","text":"你好"}"""))
        assertTrue(NovexToolArgResolver.hasResolvableParams("""{"document_ref":"x"}"""))
        assertTrue(!NovexToolArgResolver.hasResolvableParams("not json"))
    }
}
