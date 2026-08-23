package com.megamaced.nccollectives.data.api.sso

import okhttp3.Request
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The AIDL channel reports success as "a stream plus headers" and everything
 * else as an exception carrying a status code. These are the codes the rest
 * of the app reads back off the reconstructed response: `PageBodyService`
 * needs 304 (revalidation), 405 (collection exists) and 412 (`If-Match`
 * conflict), and `SessionManager` counts 401s.
 */
class SsoResponseMappingTest {
    private val request = Request.Builder().url("https://example.com/ocs/v2.php/x").build()

    @Test
    fun `a successful response carries the body and headers`() {
        val response = buildSsoResponse(
            request = request,
            code = 200,
            message = ssoStatusMessage(200),
            headers = listOf(
                "Content-Type" to "text/markdown; charset=utf-8",
                "ETag" to "\"abc123\"",
            ),
            body = Buffer().writeUtf8("# Seasonal guide"),
        )

        assertTrue(response.isSuccessful)
        assertEquals(200, response.code)
        assertEquals("\"abc123\"", response.header("ETag"))
        assertEquals("# Seasonal guide", response.body.string())
    }

    @Test
    fun `the content type comes off the forwarded headers`() {
        val response = buildSsoResponse(
            request = request,
            code = 200,
            message = ssoStatusMessage(200),
            headers = listOf("Content-Type" to "image/jpeg"),
            body = Buffer().writeUtf8("binary-ish"),
        )

        assertEquals("image", response.body.contentType()?.type)
        assertEquals("jpeg", response.body.contentType()?.subtype)
    }

    /**
     * `-1` on purpose: the stream is read incrementally, so a `Content-Length`
     * copied off the server would be a second source of truth for its size.
     */
    @Test
    fun `the body reports an unknown length`() {
        val response = buildSsoResponse(
            request = request,
            code = 200,
            message = ssoStatusMessage(200),
            headers = listOf("Content-Length" to "16"),
            body = Buffer().writeUtf8("sixteen chars!!!"),
        )

        assertEquals(-1L, response.body.contentLength())
    }

    @Test
    fun `a 304 survives as a 304 with no body`() {
        val response = buildSsoResponse(
            request = request,
            code = 304,
            message = ssoStatusMessage(304),
            headers = emptyList(),
            body = null,
        )

        assertEquals(304, response.code)
        assertEquals("Not Modified", response.message)
        assertFalse(response.isSuccessful)
        assertEquals("", response.body.string())
    }

    @Test
    fun `the codes the app branches on round trip`() {
        listOf(401, 405, 409, 412).forEach { code ->
            val response = buildSsoResponse(
                request = request,
                code = code,
                message = ssoStatusMessage(code),
                headers = emptyList(),
                body = null,
            )
            assertEquals(code, response.code)
            assertFalse(response.isSuccessful)
        }
    }

    @Test
    fun `repeated headers are all kept`() {
        val response = buildSsoResponse(
            request = request,
            code = 200,
            message = ssoStatusMessage(200),
            headers = listOf("Set-Cookie" to "a=1", "Set-Cookie" to "b=2"),
            body = null,
        )

        assertEquals(listOf("a=1", "b=2"), response.headers.values("Set-Cookie"))
    }

    /**
     * Header names and values come from a third-party server. One malformed
     * pair must cost that pair, not the whole response.
     */
    @Test
    fun `a malformed header does not sink the response`() {
        val response = buildSsoResponse(
            request = request,
            code = 200,
            message = ssoStatusMessage(200),
            headers = listOf("Bad Name" to "x", "ETag" to "\"ok\""),
            body = null,
        )

        assertEquals(200, response.code)
        assertEquals("\"ok\"", response.header("ETag"))
        assertNull(response.header("Bad Name"))
    }

    @Test
    fun `unmapped codes still get a readable message`() {
        assertEquals("HTTP 418", ssoStatusMessage(418))
        assertEquals("Precondition Failed", ssoStatusMessage(412))
    }
}
