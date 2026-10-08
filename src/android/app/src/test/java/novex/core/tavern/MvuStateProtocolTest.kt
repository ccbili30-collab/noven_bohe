package novex.core.tavern

import novex.core.PlaythroughValue
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** MVU 协议解释器：<UpdateVariable> JSON Patch → 本局状态扁平键更新。 */
class MvuStateProtocolTest {

    private fun msg(patch: String) = "正文。<UpdateVariable><Analysis>分析</Analysis>" +
        "<JSONPatch>$patch</JSONPatch></UpdateVariable>"

    private fun values() = mutableMapOf<String, PlaythroughValue>()

    @Test
    fun `extract pulls patch and strips block`() {
        val out = MvuStateProtocol.extract(msg("""[{"op":"replace","path":"/a","value":1}]""") + "尾巴")
        assertEquals(1, out.patches.size)
        assertFalse("<UpdateVariable" in out.text)
        assertEquals("正文。尾巴", out.text)
        assertTrue(MvuStateProtocol.extract("没有块").patches.isEmpty())
    }

    @Test
    fun `strip removes unclosed streaming tail`() {
        assertEquals("前文", MvuStateProtocol.stripBlocks("前文<UpdateVariable><Analysis>未完"))
    }

    @Test
    fun `replace scalar flattens pointer to dot key`() {
        val patch = JSONArray("""[{"op":"replace","path":"/月宫绾音/好感度","value":85}]""")
        val updates = MvuStateProtocol.applyPatch(patch, values())
        assertEquals(1, updates.length())
        val item = updates.getJSONObject(0)
        assertEquals("月宫绾音.好感度", item.getString("key"))
        assertEquals(85.0, item.getDouble("value"), 0.001)
    }

    @Test
    fun `delta reads current number and keeps max`() {
        val vs = values()
        vs["绘里奈.好感度"] = PlaythroughValue.Number(100.0, 400.0)
        val updates = MvuStateProtocol.applyPatch(
            JSONArray("""[{"op":"delta","path":"/绘里奈/好感度","value":-5}]"""), vs)
        assertEquals(95.0, (vs["绘里奈.好感度"] as PlaythroughValue.Number).value, 0.001)
        assertEquals(400.0, updates.getJSONObject(0).getDouble("max"), 0.001)
    }

    @Test
    fun `insert object flattens leaves and minus appends`() {
        val vs = values()
        vs["a.list.0"] = PlaythroughValue.Text("旧项")
        val patch = JSONArray("""[
            {"op":"insert","path":"/a/list/-","value":"新项"},
            {"op":"insert","path":"/x/obj","value":{"k1":"v1","k2":{"n":2}}}
        ]""")
        MvuStateProtocol.applyPatch(patch, vs)
        assertEquals("新项", (vs["a.list.1"] as PlaythroughValue.Text).value)
        assertEquals("v1", (vs["x.obj.k1"] as PlaythroughValue.Text).value)
        assertEquals(2.0, (vs["x.obj.k2.n"] as PlaythroughValue.Number).value, 0.001)
    }

    @Test
    fun `remove takes whole subtree move relocates it`() {
        val vs = values()
        vs["a.x"] = PlaythroughValue.Text("1")
        vs["a.y"] = PlaythroughValue.Number(2.0)
        vs["keep"] = PlaythroughValue.Flag(true)
        MvuStateProtocol.applyPatch(JSONArray("""[
            {"op":"move","from":"/a","path":"/b"},
            {"op":"remove","path":"/keep"}
        ]"""), vs)
        assertEquals("1", (vs["b.x"] as PlaythroughValue.Text).value)
        assertNull(vs["a.x"])
        assertNull(vs["keep"])
    }

    @Test
    fun `replace object clears stale leaves`() {
        val vs = values()
        vs["o.old"] = PlaythroughValue.Text("残留")
        MvuStateProtocol.applyPatch(JSONArray(
            """[{"op":"replace","path":"/o","value":{"fresh":"新"}}]"""), vs)
        assertNull(vs["o.old"])
        assertEquals("新", (vs["o.fresh"] as PlaythroughValue.Text).value)
    }

    @Test
    fun `invalid ops are skipped not thrown`() {
        val skipped = intArrayOf(0)
        val updates = MvuStateProtocol.applyPatch(JSONArray("""[
            {"op":"bogus","path":"/a","value":1},
            {"op":"delta","path":"/missing","value":1},
            {"op":"replace","value":1},
            "not-an-object",
            {"op":"replace","path":"/ok","value":"好"}
        ]"""), values(), skipped)
        assertEquals(4, skipped[0])
        assertEquals(1, updates.length())
    }
}
