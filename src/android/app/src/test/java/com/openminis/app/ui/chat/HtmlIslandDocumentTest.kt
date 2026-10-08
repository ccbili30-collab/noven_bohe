package com.openminis.app.ui.chat

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 岛包装页契约：断网 CSP、高度回报脚本时序、NOVEN_FRAME 注入防截断。 */
class HtmlIslandDocumentTest {

    @Test
    fun `language gate accepts html and xhtml only`() {
        assertTrue(isHtmlIslandLanguage("html"))
        assertTrue(isHtmlIslandLanguage(" HTML "))
        assertTrue(isHtmlIslandLanguage("xhtml"))
        assertFalse(isHtmlIslandLanguage("svg"))
        assertFalse(isHtmlIslandLanguage(""))
        assertFalse(isHtmlIslandLanguage("javascript"))
    }

    @Test
    fun `document keeps offline sandbox`() {
        val doc = wrapIslandDocument("<p>hi</p>", "{}")
        assertTrue(doc.contains("connect-src 'none'"))
        assertTrue(doc.contains("default-src 'none'"))
        assertTrue(doc.contains("script-src 'unsafe-inline'"))
        assertTrue(doc.contains("img-src 'self' data: blob: minis: novex-res:"))
    }

    @Test
    fun `island resources only allow session image schemes`() {
        val doc = wrapIslandDocument("<p>hi</p>", "{}")
        // 本地图片可进岛当素材，但 font/media/frame/connect 面不随
        // img-src 一起放开——资源口子只服务装饰图。
        assertTrue(doc.contains("minis:"))
        assertTrue(doc.contains("media-src 'none'"))
        assertTrue(doc.contains("frame-src 'none'"))
    }

    @Test
    fun `height reporter survives head-phase null body`() {
        val doc = wrapIslandDocument("<p>hi</p>", "{}")
        // 观测器挂接必须等 DOMContentLoaded 或就绪态分支——脚本在 head
        // 执行时 document.body 是 null，直接 observe 会永远装不上，
        // 岛高停在默认值、动态建 DOM 的内容被裁得只剩空白。
        assertTrue(doc.contains("DOMContentLoaded"))
        assertTrue(doc.contains("document.readyState==='loading'"))
        assertTrue(doc.contains("document.documentElement"))
        assertTrue(doc.contains("addEventListener('load'"))
    }

    @Test
    fun `frame json cannot break out of inline script`() {
        val doc = wrapIslandDocument("<p>hi</p>", "{\"x\":\"</script><script>alert(1)</script>\"}")
        assertFalse(doc.contains("</script><script>alert(1)"))
        assertTrue(doc.contains("<\\/script>"))
    }

    @Test
    fun `user html lands inside body`() {
        val doc = wrapIslandDocument("<div id=\"app\">x</div>", "{}")
        val bodyAt = doc.indexOf("</head><body>")
        val contentAt = doc.indexOf("<div id=\"app\">x</div>")
        assertTrue(bodyAt in 0 until contentAt)
        assertTrue(doc.trimEnd().endsWith("</body></html>"))
    }
}
