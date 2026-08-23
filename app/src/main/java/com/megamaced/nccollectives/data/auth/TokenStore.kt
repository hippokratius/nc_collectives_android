package com.megamaced.nccollectives.data.auth

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import timber.log.Timber
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * How the app proves who it is to the Nextcloud server.
 *
 * [AppPassword] is the original Login Flow v2 route: the user authorises the
 * app in a browser, the server hands back a device-scoped app password, and
 * every request carries it as HTTP Basic.
 *
 * [Sso] is the account hand-off from the Nextcloud Files app. It does *not*
 * yield a password: the Files app mints a random token, keeps only its
 * SHA-512, and uses it to authenticate us over AIDL — then performs the HTTP
 * request itself. So in this mode there is nothing to attach to a request and
 * nothing for us to store; see
 * [com.megamaced.nccollectives.data.api.sso.SsoBridgeInterceptor].
 */
enum class AuthMode {
    AppPassword,
    Sso,
}

data class StoredCredentials(
    val host: String,
    val loginName: String,
    /** Null in [AuthMode.Sso] — the Files app holds the real credential. */
    val appPassword: String?,
    val mode: AuthMode = AuthMode.AppPassword,
    /**
     * Android account name of the Files-app account this session was imported
     * from (`SingleSignOnAccount.name`). Null outside [AuthMode.Sso]. Used to
     * re-resolve the account — and with it the AIDL token — on each request.
     */
    val ssoAccountName: String? = null,
)

@Singleton
class TokenStore
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) {
        // Cached on first successful open. `null` means either not-yet-opened
        // or open-failed (in which case [openPrefs] will retry next call).
        @Volatile
        private var prefs: SharedPreferences? = null

        /**
         * R-42: memoised result of [getCredentials]. Each miss costs three
         * Tink AEAD decrypts, and the getter sits on the hot path of every
         * HTTP request — `HostInterceptor` and `AuthInterceptor` both call
         * it, and `PageBodyService.buildWebDavUrl` calls it once per
         * attachment row. `null` means "not cached", so a signed-out store
         * is re-read rather than remembered; that path does no request work
         * anyway. See also B-21 in `SettingsViewModel`, which pushed the
         * (now-cached) read onto `Dispatchers.IO`.
         *
         * `@Volatile` on an immutable holder is enough for readers: an
         * interceptor thread sees either the old reference or the new one,
         * never a half-built object.
         */
        @Volatile
        private var cachedCredentials: StoredCredentials? = null

        /**
         * Serialises cache *population* against the writers ([saveCredentials],
         * [clear], and the wipe-and-retry recovery in [openPrefs]). Without
         * it a reader that had already read the plaintext out of the store
         * could publish it into the cache after a concurrent sign-out
         * cleared both — leaving stale credentials live after logout. Reads
         * that hit the cache never take the lock.
         */
        private val credentialsLock = Any()

        /**
         * Open or reopen the encrypted prefs. On `AEADBadTagException`/
         * `KeyStoreException`/`SecurityException` — typically caused by a
         * Keystore reset (factory restore, OEM wipe) or a corrupted Tink
         * keyset on disk — the prefs file is deleted and a fresh empty
         * store is created. The user is treated as unauthenticated, which
         * routes back to the login flow naturally on the next session
         * refresh. Previously this method propagated and crashed the app
         * on launch from `SessionManager.init` (S-19).
         */
        private fun openPrefs(): SharedPreferences? {
            prefs?.let { return it }
            return try {
                val masterKey = MasterKey
                    .Builder(context)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build()
                EncryptedSharedPreferences
                    .create(
                        context,
                        PREFS_FILE,
                        masterKey,
                        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
                    ).also { prefs = it }
            } catch (e: Exception) {
                // Catch broad: Tink wraps a wide cone of failures and the
                // recovery is always the same — wipe + start over.
                Timber.w(e, "Encrypted prefs unreadable; wiping and re-creating")
                // R-42: the file we just wiped is the only source of truth
                // for the cache, so anything memoised from it is now stale.
                cachedCredentials = null
                resetPrefsFile()
                null
            }
        }

        private fun resetPrefsFile() {
            try {
                File(context.filesDir.parentFile, "shared_prefs/$PREFS_FILE.xml").delete()
            } catch (e: SecurityException) {
                Timber.w(e, "Couldn't delete corrupted prefs file")
            }
        }

        fun getCredentials(): StoredCredentials? {
            // Fast path: no lock, no decrypt.
            cachedCredentials?.let { return it }
            return synchronized(credentialsLock) { readCredentialsLocked() }
        }

        /** Cache-miss half of [getCredentials]. Call with [credentialsLock] held. */
        private fun readCredentialsLocked(): StoredCredentials? {
            // Another thread may have populated the cache while this one
            // waited for the lock.
            cachedCredentials?.let { return it }
            val store = openPrefs() ?: return null
            return try {
                val host = store.getString(KEY_HOST, null) ?: return null
                val loginName = store.getString(KEY_LOGIN_NAME, null) ?: return null
                // Absent `auth_mode` means a store written before SSO
                // existed, i.e. an app-password session. Reading it as such
                // is what keeps already-signed-in installs signed in across
                // the upgrade — there is no migration step.
                val mode = when (store.getString(KEY_AUTH_MODE, null)) {
                    MODE_SSO -> AuthMode.Sso
                    else -> AuthMode.AppPassword
                }
                val credentials = when (mode) {
                    AuthMode.AppPassword -> {
                        val appPassword = store.getString(KEY_APP_PASSWORD, null) ?: return null
                        StoredCredentials(host, loginName, appPassword, AuthMode.AppPassword)
                    }

                    AuthMode.Sso -> {
                        // Without the account name we can't resolve the Files-app
                        // account, so the session is unusable — treat it as
                        // signed out rather than failing every request later.
                        val accountName = store.getString(KEY_SSO_ACCOUNT_NAME, null) ?: return null
                        StoredCredentials(
                            host = host,
                            loginName = loginName,
                            appPassword = null,
                            mode = AuthMode.Sso,
                            ssoAccountName = accountName,
                        )
                    }
                }
                credentials.also { cachedCredentials = it }
            } catch (e: Exception) {
                Timber.w(e, "Reading credentials failed; resetting store")
                prefs = null
                cachedCredentials = null
                resetPrefsFile()
                null
            }
        }

        fun saveCredentials(
            host: String,
            loginName: String,
            appPassword: String,
        ) = save(
            StoredCredentials(
                host = host,
                loginName = loginName,
                appPassword = appPassword,
                mode = AuthMode.AppPassword,
            ),
        )

        /**
         * Persist a session imported from the Nextcloud Files app. Note what
         * is *not* written: the SSO token. It lives in the SSO library's own
         * prefs, is scoped to this package by the Files app, and is looked up
         * per request from [ssoAccountName] — storing a copy here would put a
         * credential we don't own in a second place with no way to keep it in
         * step when the user revokes the grant.
         */
        fun saveSsoCredentials(
            host: String,
            loginName: String,
            accountName: String,
        ) = save(
            StoredCredentials(
                host = host,
                loginName = loginName,
                appPassword = null,
                mode = AuthMode.Sso,
                ssoAccountName = accountName,
            ),
        )

        private fun save(credentials: StoredCredentials) {
            synchronized(credentialsLock) {
                // Drop first: if the write below can't open the store, the
                // cache must not keep serving the previous account.
                cachedCredentials = null
                val store = openPrefs() ?: return
                val editor = store
                    .edit()
                    // `clear()` first so switching between the two login
                    // routes can't leave the other one's key behind — a
                    // stale `app_password` under an SSO session would make
                    // `AuthInterceptor` attach Basic-auth that the server
                    // has long since revoked.
                    .clear()
                    .putString(KEY_HOST, credentials.host)
                    .putString(KEY_LOGIN_NAME, credentials.loginName)
                    .putString(
                        KEY_AUTH_MODE,
                        when (credentials.mode) {
                            AuthMode.AppPassword -> MODE_APP_PASSWORD
                            AuthMode.Sso -> MODE_SSO
                        },
                    )
                credentials.appPassword?.let { editor.putString(KEY_APP_PASSWORD, it) }
                credentials.ssoAccountName?.let { editor.putString(KEY_SSO_ACCOUNT_NAME, it) }
                editor.apply()
                cachedCredentials = credentials
            }
        }

        fun clear() {
            synchronized(credentialsLock) {
                cachedCredentials = null
                val store = openPrefs() ?: return
                store.edit().clear().apply()
            }
        }

        companion object {
            // Filename mirrors the entry in backup_rules.xml that excludes
            // this file from cloud backup / device transfer.
            private const val PREFS_FILE = "nc_collectives_secure_prefs"
            private const val KEY_HOST = "host"
            private const val KEY_LOGIN_NAME = "login_name"
            private const val KEY_APP_PASSWORD = "app_password"
            private const val KEY_AUTH_MODE = "auth_mode"
            private const val KEY_SSO_ACCOUNT_NAME = "sso_account_name"

            // Stored as strings rather than enum ordinals: reordering
            // `AuthMode` must not silently re-interpret an existing store.
            private const val MODE_APP_PASSWORD = "app_password"
            private const val MODE_SSO = "sso"
        }
    }
