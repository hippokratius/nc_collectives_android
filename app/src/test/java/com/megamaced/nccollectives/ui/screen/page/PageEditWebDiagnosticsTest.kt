package com.megamaced.nccollectives.ui.screen.page

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * The editor's load-timeout used to say only that it was slow. These are the
 * rules that let it say *why* — and, more importantly, the redaction that
 * keeps the `directediting` session token out of a message which reaches both
 * logcat and the screen.
 */
class PageEditWebDiagnosticsTest {
    @Test
    fun `no failures says so, rather than reading like the old build`() {
        assertEquals(
            "Editor is taking a long time to load — no HTTP or script error was reported",
            editorTimeoutMessage(emptyList()),
        )
    }

    @Test
    fun `a single failure is named`() {
        assertEquals(
            "Editor is taking a long time to load — HTTP 403 GET on /apps/text/session/sync",
            editorTimeoutMessage(listOf("HTTP 403 GET on /apps/text/session/sync")),
        )
    }

    @Test
    fun `further failures are counted, not listed`() {
        assertEquals(
            "Editor is taking a long time to load — HTTP 403 on /a (+2 more)",
            editorTimeoutMessage(listOf("HTTP 403 on /a", "HTTP 404 on /b", "JS: boom")),
        )
    }

    @Test
    fun `a failure describes status, method and path`() {
        assertEquals(
            "HTTP 419 POST on /apps/text/session/create",
            describeWebResourceFailure(419, "POST", "/apps/text/session/create"),
        )
    }

    @Test
    fun `a missing method or path still yields something readable`() {
        assertEquals("HTTP 500 on /", describeWebResourceFailure(500, null, null))
        assertEquals("HTTP 500 on /", describeWebResourceFailure(500, "", ""))
    }

    /**
     * The one that matters. A JS error quoting the session URL must not carry
     * its query into the log — that query is a live one-shot token.
     */
    @Test
    fun `a session url loses its query`() {
        assertEquals(
            "Failed to fetch https://cloud.example.com/index.php/apps/text/directedit",
            redactUrls(
                "Failed to fetch https://cloud.example.com/index.php/apps/text/directedit" +
                    "?token=s3cr3t-one-shot&fileId=42",
            ),
        )
    }

    @Test
    fun `redaction survives a token under an unexpected parameter name`() {
        val redacted = redactUrls("GET https://host/x?nc_sso_hand_off=abc123 failed")
        assertFalse(redacted.contains("abc123"))
        assertEquals("GET https://host/x failed", redacted)
    }

    @Test
    fun `fragments go too`() {
        assertEquals("see https://host/path", redactUrls("see https://host/path#tok=nope"))
    }

    @Test
    fun `every url in a message is redacted`() {
        assertEquals(
            "https://a/1 then https://b/2",
            redactUrls("https://a/1?t=x then https://b/2?t=y"),
        )
    }

    @Test
    fun `text without urls is untouched`() {
        assertEquals(
            "TypeError: undefined is not a function",
            redactUrls("TypeError: undefined is not a function"),
        )
    }

    @Test
    fun `a url with no query is left alone`() {
        assertEquals("https://host/a/b.js", redactUrls("https://host/a/b.js"))
    }
}
