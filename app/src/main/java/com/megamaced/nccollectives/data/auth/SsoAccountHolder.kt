package com.megamaced.nccollectives.data.auth

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Outcome of an account import started from the login screen.
 *
 * [Imported] carries the three fields of a `SingleSignOnAccount` this app
 * actually stores — deliberately not the object itself, because the token on
 * it is the Files app's and nothing outside the SSO package should hold it.
 */
sealed interface SsoImportOutcome {
    data class Imported(
        val accountName: String,
        val userId: String,
        val serverUrl: String,
    ) : SsoImportOutcome

    data class Failed(
        val message: String,
    ) : SsoImportOutcome
}

/**
 * Single-process handoff for an account imported from the Nextcloud Files
 * app — the same shape as [com.megamaced.nccollectives.share.SharePayloadHolder],
 * and for the same reason.
 *
 * The released SSO library (1.0.x) has no `ActivityResultContract`: importing
 * an account is a two-step `startActivityForResult` dance (pick an account,
 * then grant this app access to it) that `AccountImporter` drives through the
 * Activity's `onActivityResult`. That lands in `MainActivity`, not in the
 * Composable that started it, so the result needs somewhere to meet the UI
 * again. `LoginViewModel` observes this and [consume]s what it takes, so a
 * recreation can't replay an import that already happened.
 *
 * Failures travel the same way rather than through the library's own error
 * dialog — see the theme note in `MainActivity.onActivityResult`.
 */
@Singleton
class SsoAccountHolder
    @Inject
    constructor() {
        private val _outcome = MutableStateFlow<SsoImportOutcome?>(null)
        val outcome: StateFlow<SsoImportOutcome?> = _outcome.asStateFlow()

        fun publish(outcome: SsoImportOutcome) {
            _outcome.value = outcome
        }

        fun consume() {
            _outcome.value = null
        }
    }
