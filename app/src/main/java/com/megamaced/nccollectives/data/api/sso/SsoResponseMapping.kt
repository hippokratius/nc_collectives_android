package com.megamaced.nccollectives.data.api.sso

import okhttp3.Headers
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.asResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.BufferedSource

/**
 * Assemble the [Response] this app's stack sees for a request the Nextcloud
 * Files app carried out on our behalf.
 *
 * The AIDL channel hands back a body stream and a header list but no status
 * line: anything outside 2xx never returns at all, it is raised as
 * `NextcloudHttpRequestFailedException` carrying the code. Rebuilding a real
 * [Response] here — rather than translating outcomes at each call site — is
 * what keeps the rest of the app oblivious to which login route is in use.
 * It matters more than it sounds: `PageBodyService` reads `304` to skip a
 * re-download, `405` to mean "collection already exists", `412` to mean an
 * `If-Match` conflict, and `SessionManager` counts `401`s to decide the
 * session is dead. All four are non-2xx, i.e. all four arrive as exceptions.
 *
 * @param contentLength always `-1`: the stream is read incrementally and a
 *   `Content-Length` header copied from the server would be a second,
 *   possibly disagreeing, source of truth for its size.
 */
internal fun buildSsoResponse(
    request: Request,
    code: Int,
    message: String,
    headers: List<Pair<String, String>>,
    body: BufferedSource?,
): Response {
    val headerBuilder = Headers.Builder()
    headers.forEach { (name, value) ->
        // addUnsafeNonAscii rather than add: header values arrive from a
        // third-party server, and a stray non-ASCII byte in one of them
        // should not take down the whole response with an exception.
        runCatching { headerBuilder.addUnsafeNonAscii(name, value) }
    }
    val builtHeaders = headerBuilder.build()
    val contentType = builtHeaders["Content-Type"]?.toMediaTypeOrNull()
    return Response
        .Builder()
        .request(request)
        // The Files app performs the call with commons-httpclient, which
        // speaks HTTP/1.1 only.
        .protocol(Protocol.HTTP_1_1)
        .code(code)
        .message(message)
        .headers(builtHeaders)
        // Fresh empty body per call rather than a shared constant: a
        // ResponseBody owns an okio source, and okio sources are not safe to
        // hand to two concurrent callers.
        .body(body?.asResponseBody(contentType, -1L) ?: "".toResponseBody(null))
        .build()
}

/**
 * Reason phrase for a synthesised response. Only reaches the user through
 * `ApiResult.HttpError(code, message)`, so it needs to be recognisable rather
 * than exhaustive.
 */
internal fun ssoStatusMessage(code: Int): String =
    when (code) {
        200 -> "OK"
        304 -> "Not Modified"
        401 -> "Unauthorized"
        403 -> "Forbidden"
        404 -> "Not Found"
        405 -> "Method Not Allowed"
        409 -> "Conflict"
        412 -> "Precondition Failed"
        507 -> "Insufficient Storage"
        else -> "HTTP $code"
    }
