package com.megamaced.nccollectives.data.api.sso

import android.content.Context
import androidx.annotation.WorkerThread
import com.google.gson.Gson
import com.nextcloud.android.sso.AccountImporter
import com.nextcloud.android.sso.api.NextcloudAPI
import dagger.hilt.android.qualifiers.ApplicationContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Owns the bound connection to the Nextcloud Files app.
 *
 * [NextcloudAPI] is not a value object: constructing one binds a service in
 * another process, and the first request through it blocks up to ten seconds
 * waiting for that bind. Building one per HTTP call would pay that cost on
 * every page open, so exactly one is kept alive per account and shared by
 * every request the [SsoBridgeInterceptor] carries.
 *
 * The `Gson` instance it demands is unused in practice — this app only calls
 * `performNetworkRequestV2`, which returns a raw stream and leaves parsing to
 * the existing kotlinx.serialization converter.
 */
@Singleton
class SsoApiProvider
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) {
        private val lock = Any()
        private var boundAccountName: String? = null
        private var api: NextcloudAPI? = null

        /**
         * The bound API for [accountName], connecting on first use and
         * re-connecting if the caller has since switched accounts.
         *
         * @throws com.nextcloud.android.sso.exceptions.NextcloudFilesAppAccountNotFoundException
         *   when the grant is gone — the user revoked it in the Files app, or
         *   removed the account outright.
         */
        @WorkerThread
        fun apiFor(accountName: String): NextcloudAPI {
            synchronized(lock) {
                val bound = api
                if (bound != null && boundAccountName == accountName) return bound
                // Resolve before tearing the old connection down: a failed
                // lookup then leaves the previous binding intact rather than
                // closing a working connection over a transient error.
                val account = AccountImporter.getSingleSignOnAccount(context, accountName)
                closeLocked()
                val created = NextcloudAPI(context, account, Gson())
                api = created
                boundAccountName = accountName
                return created
            }
        }

        /**
         * Unbind from the Files app. Called on sign-out — the service
         * connection would otherwise outlive the session it belongs to.
         */
        fun close() = synchronized(lock) { closeLocked() }

        private fun closeLocked() {
            runCatching { api?.close() }
                .onFailure { Timber.w(it, "Closing the Nextcloud SSO connection failed") }
            api = null
            boundAccountName = null
        }
    }
