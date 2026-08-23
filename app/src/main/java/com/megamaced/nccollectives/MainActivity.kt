package com.megamaced.nccollectives

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.megamaced.nccollectives.data.auth.ImportedSsoAccount
import com.megamaced.nccollectives.data.auth.SsoAccountHolder
import com.megamaced.nccollectives.data.prefs.UserPreferences
import com.megamaced.nccollectives.data.prefs.UserPrefs
import com.megamaced.nccollectives.share.SharePayload
import com.megamaced.nccollectives.share.SharePayloadHolder
import com.megamaced.nccollectives.ui.navigation.NcCollectivesScaffold
import com.megamaced.nccollectives.ui.theme.NcCollectivesTheme
import com.nextcloud.android.sso.AccountImporter
import dagger.hilt.android.AndroidEntryPoint
import timber.log.Timber
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject
    lateinit var sharePayloadHolder: SharePayloadHolder

    @Inject
    lateinit var userPreferences: UserPreferences

    @Inject
    lateinit var ssoAccountHolder: SsoAccountHolder

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // B-74: only a fresh start may publish. This activity is
        // `singleTask`, so a share intent stays its current intent
        // indefinitely — an unconditional publish here re-published a payload
        // the user had already saved on every recreation (rotation, theme
        // change, the system reclaiming the process), the navigation effect
        // dragged them back into the capture screen with content that was
        // already on the server, and tapping Create made a duplicate page.
        // A non-null `savedInstanceState` is exactly "this is a recreation,
        // not a new share".
        if (savedInstanceState == null) publishShareIfPresent(intent)
        setContent {
            val prefs by userPreferences.flow.collectAsStateWithLifecycle(initialValue = UserPrefs())
            NcCollectivesTheme(themeMode = prefs.themeMode, textScale = prefs.textScale) {
                NcCollectivesScaffold()
            }
        }
    }

    /**
     * Second half of the Nextcloud SSO account import.
     *
     * The released SSO library has no `ActivityResultContract`, so
     * `AccountImporter.pickNewAccount` — started from `LoginScreen` — uses
     * `startActivityForResult` and its two-step flow reports back here:
     * first the account chooser, then the Files app's grant-access screen.
     * `AccountImporter.onActivityResult` drives both steps and only invokes
     * the callback once an account has actually been granted; the result is
     * handed to [ssoAccountHolder], which `LoginViewModel` observes.
     *
     * `super` first: `ComponentActivity` dispatches to the modern
     * `ActivityResultRegistry` from here, and everything else in this app
     * (camera capture, file picking) relies on that path.
     */
    @Deprecated("AccountImporter has no ActivityResultContract in the released SSO library")
    @Suppress("DEPRECATION")
    override fun onActivityResult(
        requestCode: Int,
        resultCode: Int,
        data: Intent?,
    ) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != AccountImporter.CHOOSE_ACCOUNT_SSO &&
            requestCode != AccountImporter.REQUEST_AUTH_TOKEN_SSO
        ) {
            return
        }
        try {
            AccountImporter.onActivityResult(requestCode, resultCode, data, this) { account ->
                ssoAccountHolder.publish(
                    ImportedSsoAccount(
                        accountName = account.name,
                        userId = account.userId,
                        serverUrl = account.url,
                    ),
                )
            }
        } catch (e: Exception) {
            // Backing out of the account chooser arrives as a thrown
            // AccountImportCancelledException rather than a return value, so
            // the ordinary "user changed their mind" path lands here too.
            // Nothing to report: the login screen is still on screen.
            Timber.d(e, "Nextcloud SSO account import did not complete")
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        publishShareIfPresent(intent)
    }

    /**
     * Publish a share payload at most once per intent.
     *
     * The `savedInstanceState` gate in [onCreate] handles recreation; the
     * marker extra handles everything else that can hand us the same intent
     * object twice — `onNewIntent` for an intent we already took, and a
     * `getIntent()` re-read after the activity was rebuilt in this process.
     * `onNewIntent` still publishes normally: a genuinely new share arrives
     * as a new intent, without the marker.
     */
    private fun publishShareIfPresent(intent: Intent?) {
        if (intent == null) return
        if (intent.getBooleanExtra(EXTRA_SHARE_HANDLED, false)) return
        val payload = SharePayload.fromIntent(intent) ?: return
        intent.putExtra(EXTRA_SHARE_HANDLED, true)
        sharePayloadHolder.publish(payload)
    }

    private companion object {
        /** Marks a share intent this activity has already handed to the holder. */
        const val EXTRA_SHARE_HANDLED = "com.megamaced.nccollectives.SHARE_HANDLED"
    }
}
