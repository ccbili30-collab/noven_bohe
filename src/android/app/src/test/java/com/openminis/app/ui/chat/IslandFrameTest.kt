package com.openminis.app.ui.chat

import novex.core.PlaythroughState
import novex.core.PlaythroughValue
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 舞台协议 v0.3 帧与出站 URL 契约（IslandFrame.kt）。 */
class IslandFrameTest {

    private fun bridge(
        messages: String = "[]",
        sendResult: IslandSendResult? = null,
    ) = HtmlIslandBridge(
        cardName = "绍宋",
        cardKind = "world",
        state = PlaythroughState(
            branchId = "msg-1",
            values = mapOf(
                "loyalty" to PlaythroughValue.Number(92.0, max = 100.0),
                "note" to PlaythroughValue.Text("初来乍到"),
                "flag1" to PlaythroughValue.Flag(true),
            ),
        ),
        sessionId = "sess-1",
        cardId = "card-root-9",
        messagesJson = messages,
        webviewMajor = 124,
        turnStreaming = true,
        queuedCount = 2,
        imeVisible = true,
        imeHeightCssPx = 300,
        frameReason = "turn",
        sendResult = sendResult,
    )

    private val testColors = MdColors(
        text = androidx.compose.ui.graphics.Color(0xFF101010),
        codeText = androidx.compose.ui.graphics.Color(0xFF202020),
        codeBg = androidx.compose.ui.graphics.Color(0xFFF0F0F0),
        inlineCodeText = androidx.compose.ui.graphics.Color(0xFF202020),
        inlineCodeBg = androidx.compose.ui.graphics.Color(0xFFF0F0F0),
        link = androidx.compose.ui.graphics.Color(0xFF00AA66),
        blockquote = androidx.compose.ui.graphics.Color(0xFF808080),
        divider = androidx.compose.ui.graphics.Color(0xFFDDDDDD),
        tableBorder = androidx.compose.ui.graphics.Color(0xFFDDDDDD),
        tableHeaderBg = androidx.compose.ui.graphics.Color(0xFFF5F5F5),
        codeFont = androidx.compose.ui.text.font.FontFamily.Monospace,
    )

    private fun frame(bridge: HtmlIslandBridge?): JSONObject =
        JSONObject(buildIslandFrameJson(bridge, dark = true, colors = testColors))

    @Test
    fun `frame carries v0_3 skeleton fields`() {
        val f = frame(bridge())
        assertEquals("card-root-9", f.getJSONObject("card").getString("id"))
        assertEquals("绍宋", f.getJSONObject("card").getString("name"))
        assertEquals("world", f.getJSONObject("card").getString("kind"))
        assertEquals("sess-1", f.getString("session")) // v0.2 欠账：sessionId 曾不进帧
        val env = f.getJSONObject("env")
        assertEquals("android", env.getString("platform"))
        assertEquals(2, env.getInt("bridgeVersion"))
        assertEquals(124, env.getInt("webview"))
        val ime = env.getJSONObject("ime")
        assertTrue(ime.getBoolean("visible"))
        assertEquals(300, ime.getInt("height"))
        val turn = f.getJSONObject("turn")
        assertEquals("streaming", turn.getString("status"))
        assertEquals(2, turn.getInt("queued"))
        assertEquals("turn", f.getString("reason"))
        assertEquals(92, f.getJSONObject("state").getInt("loyalty"))
        assertEquals(100, f.getJSONObject("max").getInt("loyalty"))
        assertTrue(f.has("messages")) // 弃用但保留——皮肤 overlay 依赖
        assertEquals("#00AA66", f.getJSONObject("theme").getString("accent"))
    }

    @Test
    fun `frame works with null bridge`() {
        val f = frame(null)
        assertFalse(f.has("session"))
        assertTrue(f.getJSONObject("env").isNull("webview"))
        assertEquals("idle", f.getJSONObject("turn").getString("status"))
    }

    @Test
    fun `sendResult rides the frame with seq`() {
        val f = frame(bridge(sendResult = IslandSendResult("queued", seq = 3)))
        val r = f.getJSONObject("sendResult")
        assertEquals("queued", r.getString("result"))
        assertTrue(r.isNull("why"))
        assertEquals(3, r.getInt("seq"))
    }

    @Test
    fun `parseWebViewMajor handles real and junk names`() {
        assertEquals(124, parseWebViewMajor("124.0.6367.113"))
        assertEquals(111, parseWebViewMajor("111.0"))
        assertNull(parseWebViewMajor(null))
        assertNull(parseWebViewMajor(""))
        assertNull(parseWebViewMajor("x.y.z"))
    }

    @Test
    fun `outbound parse - query`() {
        val o = parseIslandOutbound("novex-query://persons/%E9%83%AD%E5%A8%98?from=stage")
        assertEquals(
            IslandOutbound.Query("persons", "郭娘", "stage"), o)
        assertNull(parseIslandOutbound("novex-query://persons"))
        assertNull(parseIslandOutbound("novex-query:///x"))
    }

    @Test
    fun `outbound parse - compose and send`() {
        assertEquals(
            IslandOutbound.Compose("你好 世界"),
            parseIslandOutbound("novex-compose://?text=%E4%BD%A0%E5%A5%BD%20%E4%B8%96%E7%95%8C"),
        )
        assertEquals(
            IslandOutbound.Send("hi"),
            parseIslandOutbound("novex-send://?text=hi"),
        )
        assertNull(parseIslandOutbound("novex-send://"))
        assertNull(parseIslandOutbound("https://example.com"))
        assertNull(parseIslandOutbound("novex-asset://abc"))
    }

    @Test
    fun `assets map inverts harvested urls`() {
        val b = HtmlIslandBridge(
            cardName = null, cardKind = null, state = null,
            remoteResources = mapOf(
                "https://cdn.example.com/a.png" to "res-a",
                "https://cdn.example.com/b.png" to "res-b",
            ),
        )
        val f = frame(b)
        val assets = f.getJSONObject("assets")
        assertEquals("novex-asset://res-a", assets.getString("res-a"))
        assertEquals("novex-asset://res-b", assets.getString("res-b"))
    }
}
