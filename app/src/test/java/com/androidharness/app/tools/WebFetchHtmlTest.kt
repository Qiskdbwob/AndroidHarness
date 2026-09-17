package com.androidharness.app.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * web_fetch promises text with the HTML stripped. The trigger used to be
 * "contains <html or <body", so every fragment-only document came back as raw
 * markup even when the response said text/html (on-device QA, 2026-09-17).
 */
class WebFetchHtmlTest {

    @Test
    fun `doctype-less fragment with a text html content type is stripped`() {
        val body = """<!doctype html><title>Next 731</title><a href="/">Home</a><p>NEXT_731</p>"""
        val text = htmlToText(body, "html")
        assertFalse("Markup must be gone, got: $text", text.contains("<"))
        assertTrue(text.contains("NEXT_731"))
        assertTrue(text.contains("Next 731"))
    }

    @Test
    fun `fragment sniffing works without a content type`() {
        val text = htmlToText("<p>NEXT_731</p>")
        assertEquals("NEXT_731", text)
    }

    @Test
    fun `full documents keep working`() {
        val body = "<html><head><title>T</title><style>p{color:red}</style></head>" +
            "<body><h1>Hello</h1><script>bad()</script><p>World&nbsp;now</p></body></html>"
        val text = htmlToText(body, "html")
        assertTrue(text.contains("Hello"))
        assertTrue(text.contains("World now"))
        assertFalse(text.contains("bad()"))
        assertFalse(text.contains("color:red"))
    }

    @Test
    fun `plain text json and xml are left alone`() {
        val json = """{"next": 731, "html": "<p>not a document</p>"}"""
        // JSON has no html-ish content type: the embedded markup must survive verbatim.
        assertEquals(json, htmlToText(json, "json"))

        val text = "1 < 2 and 3 > 2\nno markup here"
        assertEquals(text, htmlToText(text))

        val svg = """<svg xmlns="http://www.w3.org/2000/svg"><rect/></svg>"""
        assertEquals(svg, htmlToText(svg, "svg+xml"))
    }

    @Test
    fun `xhtml content type counts as html`() {
        assertTrue(looksLikeHtml("any body at all", "xhtml"))
        assertTrue(looksLikeHtml("any body at all", "html"))
        assertFalse(looksLikeHtml("just words", "plain"))
    }
}
