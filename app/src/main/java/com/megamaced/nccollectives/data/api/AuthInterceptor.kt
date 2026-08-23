package com.megamaced.nccollectives.data.api

import com.megamaced.nccollectives.data.auth.AuthMode
import com.megamaced.nccollectives.data.auth.SessionManager
import com.megamaced.nccollectives.data.auth.TokenStore
import com.megamaced.nccollectives.data.auth.serverHostOf
import okhttp3.Interceptor
import okhttp3.Response
import okio.ByteString.Companion.encodeUtf8
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Attaches Basic-auth + OCS headers to outgoing requests, and records each
 * authenticated response with [SessionManager] so the 401-streak signoff
 * (B-2) can keep score.
 *
 * **B-13 / S-3**: the Authorization header is *only* attached when the
 * request's host matches the user's stored Nextcloud host, so a feature
 * that issues an absolute URL to a third-party host can't leak Basic-auth
 * credentials.
 *
 * **S-23**: that host check alone can no longer see what it was written to
 * catch — [HostInterceptor] runs first in the chain and has already
 * rewritten scheme/host/port to the stored host (preserving path and
 * query), so by the time we look, *every* surviving request matches. The
 * ordering can't be flipped without losing the rewrite that keeps
 * credentials off third-party hosts in the first place, so provenance is
 * carried explicitly instead: [HostInterceptor] stamps a [RequestOrigin]
 * on requests whose URL the app itself constructed, and refuses the rest.
 * Requiring the tag here means a URL that arrived in a server response —
 * an image ref planted in a shared page body, say — cannot be handed our
 * credentials even if it somehow reaches this interceptor untagged.
 */
@Singleton
class AuthInterceptor
    @Inject
    constructor(
        private val tokenStore: TokenStore,
        private val sessionManager: SessionManager,
    ) : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val original = chain.request()
            val credentials = tokenStore.getCredentials()
            val vouchedFor = original.tag(RequestOrigin::class.java) != null
            // "This request runs under the user's session", which is what the
            // 401 bookkeeping below is about — not "we attach a credential".
            // Under [AuthMode.Sso] there is no credential to attach: the
            // Nextcloud Files app performs the request itself, and
            // `SsoBridgeInterceptor` further down the chain hands back a
            // response with the status code it reported. That still needs to
            // reach the streak counter, or a revoked SSO grant would leave the
            // app retrying forever instead of returning to the login screen.
            val authenticated = credentials != null &&
                vouchedFor &&
                hostMatches(original.url.host, credentials.host)
            val request = if (authenticated) {
                checkNotNull(credentials)
                val builder = original.newBuilder()
                if (credentials.mode == AuthMode.AppPassword) {
                    val basic = "${credentials.loginName}:${credentials.appPassword.orEmpty()}"
                        .encodeUtf8()
                        .base64()
                    builder.header("Authorization", "Basic $basic")
                    // Only in app-password mode: in SSO mode the Files app
                    // sets this header itself and *rejects* a request that
                    // already carries it (InputStreamBinder).
                    builder.header("OCS-APIRequest", "true")
                }
                // Nextcloud OCS endpoints reply with XML by default; ask
                // for JSON explicitly. Skip for binary/WebDAV endpoints and
                // for callers that already set an Accept header. Applies to
                // both modes — the Files app forwards our headers verbatim.
                if (original.url.encodedPath.contains("/ocs/") &&
                    original.header("Accept") == null
                ) {
                    builder.header("Accept", "application/json")
                }
                builder.build()
            } else {
                original
            }

            val response = chain.proceed(request)

            // Only authenticated requests count toward the 401 streak — a
            // public probe (login-poll, etc) returning 401 doesn't mean our
            // token is dead.
            if (authenticated) {
                sessionManager.onAuthenticatedResponse(response.code)
            }

            return response
        }

        private fun hostMatches(
            requestHost: String,
            credentialsHost: String,
        ): Boolean {
            val stored = serverHostOf(credentialsHost) ?: return false
            return requestHost.equals(stored, ignoreCase = true)
        }
    }
