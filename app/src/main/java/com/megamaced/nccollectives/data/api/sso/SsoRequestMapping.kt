package com.megamaced.nccollectives.data.api.sso

import okhttp3.Headers
import okhttp3.HttpUrl

// Pure translation rules between an OkHttp request and the `NextcloudRequest`
// the Nextcloud Files app expects, kept free of Android and of the SSO library
// so they can be unit-tested the same way `HostInterceptor.retarget` is.
// `SsoBridgeInterceptor` does the wiring; everything decision-shaped is here.

/**
 * Strip [storedHost]'s subdirectory prefix off [requestUrl]'s path.
 *
 * The Files app refuses anything that isn't a path starting with `/`
 * (`InputStreamBinder.processRequestV2`) and resolves it against *its own*
 * account base URI — which already carries the subdirectory prefix of a
 * `https://example.com/nextcloud` install. Everything reaching this point has
 * been through `HostInterceptor`, which guarantees the opposite: the prefix is
 * present, either spliced onto a Retrofit path or built in by
 * `PageBodyService.resourceUrl`. Handing that straight over would produce
 * `/nextcloud/nextcloud/ocs/...` — the same double-prefix bug as GH-8, just
 * from the other end.
 *
 * Only strips on a segment boundary, so a stored `https://example.com/nc`
 * leaves `/nextcloud/...` alone rather than eating three characters of it.
 * A path that doesn't carry the prefix at all is returned unchanged.
 */
internal fun ssoServerRelativePath(
    requestUrl: HttpUrl,
    storedHost: HttpUrl,
): String {
    val path = requestUrl.encodedPath
    val prefix = storedHost.encodedPath.trimEnd('/')
    if (prefix.isEmpty()) return path
    return when {
        path == prefix -> "/"
        path.startsWith("$prefix/") -> path.removePrefix(prefix)
        else -> path
    }
}

/**
 * Query string as discrete key/value pairs.
 *
 * It can't be left in the URL: the Files app calls `setQueryString` on the
 * method it builds whenever a parameter collection is present, and passes an
 * empty array when it isn't — either way overwriting whatever the URL string
 * carried. A valueless `?flag` becomes an empty value, which is how
 * commons-httpclient renders it back out.
 */
internal fun ssoQueryParams(url: HttpUrl): List<Pair<String, String>> =
    (0 until url.querySize).map { i ->
        url.queryParameterName(i) to url.queryParameterValue(i).orEmpty()
    }

/**
 * Request headers safe to hand to the Files app, in the `Map<String,
 * List<String>>` shape `NextcloudRequest.Builder.setHeader` wants.
 *
 * Two of the exclusions are load-bearing rather than tidy-up:
 *  - `OCS-APIRequest` — the Files app adds it itself and *throws* if the
 *    caller also sent it, which would turn every OCS call into a hard error.
 *  - `Authorization` — `AuthInterceptor` doesn't set it in SSO mode, but a
 *    future code path that did would be shipping a credential into another
 *    app's process for no gain.
 *
 * `Host` and `Content-Length` describe a connection this app never makes; the
 * Files app derives both from its own account and request entity.
 */
internal fun ssoForwardableHeaders(headers: Headers): Map<String, List<String>> =
    headers
        .names()
        .filterNot { it.lowercase() in SSO_EXCLUDED_REQUEST_HEADERS }
        .associateWith { headers.values(it) }

private val SSO_EXCLUDED_REQUEST_HEADERS = setOf(
    "ocs-apirequest",
    "authorization",
    "host",
    "content-length",
)
