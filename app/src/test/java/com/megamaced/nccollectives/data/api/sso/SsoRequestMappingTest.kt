package com.megamaced.nccollectives.data.api.sso

import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The other end of the B-60 / GH-8 prefix problem. `HostInterceptor` makes
 * sure a subdirectory prefix lands on a request exactly once; the Files app
 * then resolves whatever we hand it against a base URI that already carries
 * that prefix, so it has to come back off before the request crosses the AIDL
 * boundary.
 */
class SsoRequestMappingTest {
    private fun path(
        requestUrl: String,
        storedHost: String,
    ): String = ssoServerRelativePath(requestUrl.toHttpUrl(), storedHost.toHttpUrl())

    @Test
    fun `root install passes the path straight through`() {
        assertEquals(
            "/ocs/v2.php/apps/collectives/api/v1.0/collectives",
            path(
                "https://example.com/ocs/v2.php/apps/collectives/api/v1.0/collectives",
                "https://example.com",
            ),
        )
    }

    @Test
    fun `subdirectory install has its prefix stripped`() {
        assertEquals(
            "/ocs/v2.php/apps/collectives/api/v1.0/collectives",
            path(
                "https://example.com/nextcloud/ocs/v2.php/apps/collectives/api/v1.0/collectives",
                "https://example.com/nextcloud",
            ),
        )
    }

    @Test
    fun `nested subdirectory install has its whole prefix stripped`() {
        assertEquals(
            "/remote.php/dav/files/bob/Collectives/Wiki/Page.md",
            path(
                "https://example.com/apps/nc/remote.php/dav/files/bob/Collectives/Wiki/Page.md",
                "https://example.com/apps/nc",
            ),
        )
    }

    @Test
    fun `a trailing slash on the stored host does not change the result`() {
        assertEquals(
            "/ocs/v2.php/cloud/capabilities",
            path("https://example.com/nc/ocs/v2.php/cloud/capabilities", "https://example.com/nc/"),
        )
    }

    /** MKCOL targets a collection, and the trailing slash keeps the server off a 301. */
    @Test
    fun `a collection url keeps its trailing slash`() {
        assertEquals(
            "/remote.php/dav/files/bob/Collectives/Wiki/.attachments.42/",
            path(
                "https://example.com/nc/remote.php/dav/files/bob/Collectives/Wiki/.attachments.42/",
                "https://example.com/nc",
            ),
        )
    }

    /** Prefix matching is per-segment: `/nc` must not eat into `/nextcloud`. */
    @Test
    fun `a prefix that is not a whole segment is left alone`() {
        assertEquals(
            "/nextcloud/ocs/v2.php/cloud/capabilities",
            path("https://example.com/nextcloud/ocs/v2.php/cloud/capabilities", "https://example.com/nc"),
        )
    }

    @Test
    fun `a path equal to the prefix becomes root`() {
        assertEquals("/", path("https://example.com/nextcloud", "https://example.com/nextcloud"))
    }

    @Test
    fun `a path that never carried the prefix is returned unchanged`() {
        assertEquals("/status.php", path("https://example.com/status.php", "https://example.com/nextcloud"))
    }

    @Test
    fun `query parameters are lifted out of the url`() {
        assertEquals(
            listOf("term" to "seasonal", "from" to "0"),
            ssoQueryParams("https://example.com/ocs/v2.php/search?term=seasonal&from=0".toHttpUrl()),
        )
    }

    @Test
    fun `a valueless query parameter becomes an empty value`() {
        assertEquals(
            listOf("format" to "json", "pretty" to ""),
            ssoQueryParams("https://example.com/ocs/v2.php/x?format=json&pretty".toHttpUrl()),
        )
    }

    @Test
    fun `a url with no query yields no parameters`() {
        assertTrue(ssoQueryParams("https://example.com/status.php".toHttpUrl()).isEmpty())
    }

    /**
     * The Files app adds `OCS-APIRequest` itself and throws if the caller
     * also sent it, so forwarding ours would break every OCS call.
     */
    @Test
    fun `ocs and authorization headers are not forwarded`() {
        val forwarded = ssoForwardableHeaders(
            Headers
                .Builder()
                .add("OCS-APIRequest", "true")
                .add("Authorization", "Basic c2VjcmV0")
                .add("Accept", "application/json")
                .add("If-None-Match", "\"abc123\"")
                .build(),
        )
        assertEquals(setOf("Accept", "If-None-Match"), forwarded.keys)
        assertEquals(listOf("application/json"), forwarded["Accept"])
    }

    @Test
    fun `the header filter is case insensitive`() {
        val forwarded = ssoForwardableHeaders(
            Headers
                .Builder()
                .add("ocs-apirequest", "true")
                .add("authorization", "Basic x")
                .build(),
        )
        assertTrue(forwarded.isEmpty())
    }

    @Test
    fun `connection scoped headers are dropped`() {
        val forwarded = ssoForwardableHeaders(
            Headers
                .Builder()
                .add("Host", "example.com")
                .add("Content-Length", "12")
                .add("Content-Type", "text/markdown")
                .build(),
        )
        assertEquals(setOf("Content-Type"), forwarded.keys)
        assertFalse(forwarded.containsKey("Host"))
    }

    @Test
    fun `repeated headers keep every value`() {
        val forwarded = ssoForwardableHeaders(
            Headers
                .Builder()
                .add("Accept", "application/json")
                .add("Accept", "text/plain")
                .build(),
        )
        assertEquals(listOf("application/json", "text/plain"), forwarded["Accept"])
    }
}
