package com.megamaced.nccollectives.data.api.sso

import com.megamaced.nccollectives.data.auth.AuthMode
import com.megamaced.nccollectives.data.auth.TokenStore
import com.nextcloud.android.sso.QueryParam
import com.nextcloud.android.sso.aidl.NextcloudRequest
import com.nextcloud.android.sso.exceptions.NextcloudFilesAppAccountNotFoundException
import com.nextcloud.android.sso.exceptions.NextcloudFilesAppAccountPermissionNotGrantedException
import com.nextcloud.android.sso.exceptions.NextcloudFilesAppNotInstalledException
import com.nextcloud.android.sso.exceptions.NextcloudHttpRequestFailedException
import com.nextcloud.android.sso.exceptions.TokenMismatchException
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response
import okio.Buffer
import okio.Pipe
import okio.buffer
import okio.source
import timber.log.Timber
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Carries out requests inside the Nextcloud Files app when the session was
 * imported from it ([AuthMode.Sso]), and gets out of the way otherwise.
 *
 * ### Why an interceptor
 *
 * SSO hands the app a token that is *only* an AIDL handle: the Files app
 * keeps nothing but its SHA-512 and performs the HTTP request itself. There
 * is no credential to attach, so the usual advice — swap the Retrofit builder
 * for `NextcloudRetrofitApiBuilder` — would mean rewriting the Retrofit
 * services onto Gson and `Call<T>`, and would still leave the hand-written
 * WebDAV in `PageBodyService`, the Coil image loader and the WorkManager sync
 * path with no way to authenticate.
 *
 * Short-circuiting the OkHttp chain instead means every one of those keeps
 * working untouched: they build the same request, and get back a
 * [Response] that behaves like one off the wire.
 *
 * ### Where it sits
 *
 * Last in the chain, after `HostInterceptor` and `AuthInterceptor`, so the
 * security invariants those two establish still hold in SSO mode — the URL
 * has already been retargeted to the stored host, tagged with its
 * `RequestOrigin`, and refused outright if it came from server-supplied
 * content. This interceptor never widens what may be requested; it only
 * changes who performs the request.
 *
 * Being last also means `AuthInterceptor` sees the synthesised status code
 * as the return value of its own `chain.proceed`, so the 401-streak sign-out
 * keeps working with no special case for SSO.
 */
@Singleton
class SsoBridgeInterceptor
    @Inject
    constructor(
        private val tokenStore: TokenStore,
        private val apiProvider: SsoApiProvider,
    ) : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val request = chain.request()
            val credentials = tokenStore.getCredentials()
            if (credentials == null || credentials.mode != AuthMode.Sso) {
                return chain.proceed(request)
            }
            val accountName = credentials.ssoAccountName
                ?: throw IOException("SSO session has no Files-app account name")
            val storedHost = credentials.host.toHttpUrlOrNull()
                ?: throw IOException("Stored Nextcloud host is unparseable: ${credentials.host}")

            val nextcloudRequest = NextcloudRequest
                .Builder()
                .setMethod(request.method)
                .setUrl(ssoServerRelativePath(request.url, storedHost))
                .setParameter(ssoQueryParams(request.url).map { QueryParam(it.first, it.second) })
                .setHeader(ssoForwardableHeaders(request.headers))
                .setFollowRedirects(true)
                .build()
            request.body?.let { nextcloudRequest.bodyAsStream = it.asInputStream() }

            return try {
                val response = apiProvider.apiFor(accountName).performNetworkRequestV2(nextcloudRequest)
                buildSsoResponse(
                    request = request,
                    // The channel only ever returns 2xx; the exact code is
                    // not carried across it. Everything downstream tests
                    // `code in 200..299`, so the distinction between 200,
                    // 201 and 204 is not one this app can act on anyway.
                    code = 200,
                    message = ssoStatusMessage(200),
                    headers = response.plainHeaders.map { it.name to it.value },
                    body = response.body.source().buffer(),
                )
            } catch (e: NextcloudHttpRequestFailedException) {
                // The load-bearing case, not an error path: 304, 401, 405 and
                // 412 all reach us here — see [buildSsoResponse].
                buildSsoResponse(
                    request = request,
                    code = e.statusCode,
                    message = ssoStatusMessage(e.statusCode),
                    headers = emptyList(),
                    body = null,
                )
            } catch (e: TokenMismatchException) {
                accountUnusable(request, e, "the Files app no longer recognises our token")
            } catch (e: NextcloudFilesAppAccountNotFoundException) {
                accountUnusable(request, e, "the Files-app account is gone")
            } catch (e: NextcloudFilesAppAccountPermissionNotGrantedException) {
                accountUnusable(request, e, "access to the Files-app account was revoked")
            } catch (e: NextcloudFilesAppNotInstalledException) {
                // Uninstalling the Files app is not a credential problem, and
                // signing the user out over it would throw away their offline
                // queue. Treat it the way the app treats being offline.
                throw IOException("The Nextcloud app is no longer installed", e)
            } catch (e: IOException) {
                throw e
            } catch (e: Exception) {
                // The AIDL surface declares plain `Exception`: a dead binder,
                // a serialisation failure, or a network error the Files app
                // hit on our behalf all arrive this way. None of them mean
                // the session is invalid, so they must not reach the
                // 401-streak counter — IOException routes them to the same
                // retry/offline handling as a failed socket.
                throw IOException("Nextcloud app request failed: ${e.message}", e)
            }
        }

        /**
         * Report a revoked or missing grant as `401`.
         *
         * Deliberately not an [IOException]: the session really is dead, and
         * a 401 is the signal `SessionManager` already understands. Two of
         * them in a row sign the user out and return them to the login
         * screen, which is exactly the recovery — re-import the account —
         * rather than a sync that retries against a grant that will never
         * come back.
         */
        private fun accountUnusable(
            request: Request,
            cause: Exception,
            reason: String,
        ): Response {
            Timber.w(cause, "Nextcloud SSO account unusable: %s", reason)
            return buildSsoResponse(
                request = request,
                code = 401,
                message = ssoStatusMessage(401),
                headers = emptyList(),
                body = null,
            )
        }

        /**
         * Expose an OkHttp body as the [InputStream] the AIDL channel wants.
         *
         * Small bodies (OCS payloads, page markdown) are materialised — one
         * allocation, no thread. Anything larger, or of unknown size, is
         * streamed through an [okio.Pipe] instead: attachment uploads are
         * multi-MB photos, and buffering one whole would undo the streaming
         * that `PageBodyService.downloadTo` documents as deliberate.
         *
         * The writer runs on a daemon thread with a write timeout, so a Files
         * app that stops reading mid-upload costs a stalled thread for a
         * minute rather than for the life of the process.
         */
        private fun RequestBody.asInputStream(): InputStream {
            val length = runCatching { contentLength() }.getOrDefault(-1L)
            if (length in 0..MAX_BUFFERED_BODY_BYTES) {
                val buffer = Buffer()
                writeTo(buffer)
                return buffer.inputStream()
            }
            val pipe = Pipe(PIPE_BUFFER_BYTES)
            pipe.sink.timeout().timeout(BODY_WRITE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            val body = this
            Thread({
                try {
                    pipe.sink.buffer().use { sink -> body.writeTo(sink) }
                } catch (e: Exception) {
                    Timber.w(e, "Streaming a request body to the Nextcloud app failed")
                }
            }, "sso-request-body").apply {
                isDaemon = true
                start()
            }
            return pipe.source.buffer().inputStream()
        }

        private companion object {
            /** Above this, stream rather than buffer. */
            const val MAX_BUFFERED_BODY_BYTES = 512L * 1024L
            const val PIPE_BUFFER_BYTES = 64L * 1024L
            const val BODY_WRITE_TIMEOUT_SECONDS = 60L
        }
    }
