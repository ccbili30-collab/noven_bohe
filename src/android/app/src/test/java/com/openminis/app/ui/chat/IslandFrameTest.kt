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
        transcriptJson: String = "[]",
        castJson: String? = null,
        choicesJson: String? = null,
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
        transcriptJson = transcriptJson,
        castJson = castJson,
        choicesJson = choicesJson,
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
    fun `sendResult rides the frame`() {
        val f = frame(bridge(sendResult = IslandSendResult("queued")))
        val r = f.getJSONObject("sendResult")
        assertEquals("queued", r.getString("result"))
        assertTrue(r.isNull("why"))
    }

    @Test
    fun `transcript cast choices pass through and absent when null`() {
        val transcript = """[{"id":"m1#0","kind":"narration","text":"hi"}]"""
        val cast = """[{"id":"c1","name":"阿七","avatar":"a7.png","color":null}]"""
        val choices = """{"turnId":"m1","items":[{"id":"走","label":"走"}]}"""
        val f = frame(bridge(transcriptJson = transcript, castJson = cast, choicesJson = choices))
        assertEquals("narration", f.getJSONArray("transcript").getJSONObject(0).getString("kind"))
        assertEquals("阿七", f.getJSONArray("cast").getJSONObject(0).getString("name"))
        assertEquals("m1", f.getJSONObject("choices").getString("turnId"))

        val bare = frame(bridge())
        assertFalse("cast 为空不应下发", bare.has("cast"))
        assertFalse("choices 为空不应下发", bare.has("choices"))
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
    fun `send gate - own required before anything else`() {
        val r = evaluateIslandSend(
            ownInput = false, text = "hi",
            gestureUptimeMs = 1000, consumedGestureUptimeMs = 0,
            lastSendAtMs = 0, nowUptimeMs = 1100,
        )
        assertEquals("dropped" to "not-own", r)
    }

    @Test
    fun `send gate - gesture freshness and one-per-gesture`() {
        // 从未触摸（uptime=0）或超出 1.5s 窗口 → dropped/gesture。
        assertEquals("dropped" to "gesture", evaluateIslandSend(
            ownInput = true, text = "hi",
            gestureUptimeMs = 0, consumedGestureUptimeMs = 0,
            lastSendAtMs = 0, nowUptimeMs = 1000,
        ))
        assertEquals("dropped" to "gesture", evaluateIslandSend(
            ownInput = true, text = "hi",
            gestureUptimeMs = 100, consumedGestureUptimeMs = 0,
            lastSendAtMs = 0, nowUptimeMs = 2000,
        ))
        // 同一手势二次使用 → dropped/gesture（一手势一发）。
        assertEquals("dropped" to "gesture", evaluateIslandSend(
            ownInput = true, text = "hi",
            gestureUptimeMs = 1000, consumedGestureUptimeMs = 1000,
            lastSendAtMs = 0, nowUptimeMs = 1100,
        ))
    }

    @Test
    fun `send gate - length and rate limits`() {
        assertEquals("dropped" to "length", evaluateIslandSend(
            ownInput = true, text = "",
            gestureUptimeMs = 1000, consumedGestureUptimeMs = 0,
            lastSendAtMs = 0, nowUptimeMs = 1100,
        ))
        assertEquals("dropped" to "length", evaluateIslandSend(
            ownInput = true, text = "x".repeat(IslandSendMaxChars + 1),
            gestureUptimeMs = 1000, consumedGestureUptimeMs = 0,
            lastSendAtMs = 0, nowUptimeMs = 1100,
        ))
        // 限频 1s：间隔不足即使换了新手势也 dropped/rate。
        assertEquals("dropped" to "rate", evaluateIslandSend(
            ownInput = true, text = "hi",
            gestureUptimeMs = 1500, consumedGestureUptimeMs = 1000,
            lastSendAtMs = 1100, nowUptimeMs = 1600,
        ))
        // 全通过 → accept（queued/accepted 由调用方按回合态映射）。
        assertEquals("accept" to null, evaluateIslandSend(
            ownInput = true, text = "hi",
            gestureUptimeMs = 2100, consumedGestureUptimeMs = 1000,
            lastSendAtMs = 1000, nowUptimeMs = 2200,
        ))
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
