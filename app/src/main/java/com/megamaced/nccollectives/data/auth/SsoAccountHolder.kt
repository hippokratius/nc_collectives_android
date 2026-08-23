package com.megamaced.nccollectives.data.auth

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/** The three fields of a `SingleSignOnAccount` this app actually stores. */
data class ImportedSsoAccount(
    val accountName: String,
    val userId: String,
    val serverUrl: String,
)

/**
 * Single-process handoff for an account imported from the Nextcloud Files
 * app — the same shape as [com.megamaced.nccollectives.share.SharePayloadHolder],
 * and for the same reason.
 *
 * The released SSO library (1.3.x) has no `ActivityResultContract`: importing
 * an account is a two-step `startActivityForResult` dance (pick an account,
 * then grant this app access to it) that `AccountImporter` drives through the
 * Activity's `onActivityResult`. That lands in `MainActivity`, not in the
 * Composable that started it, so the result needs somewhere to meet the UI
 * again. `LoginViewModel` observes this and [consume]s what it takes, so a
 * recreation can't replay an import that already happened.
 *
 * Deliberately carries plain strings rather than `SingleSignOnAccount`: the
 * token on that object is the Files app's, and nothing outside the SSO
 * package has any business holding it.
 */
@Singleton
class SsoAccountHolder
    @Inject
    constructor() {
        private val _imported = MutableStateFlow<ImportedSsoAccount?>(null)
        val imported: StateFlow<ImportedSsoAccount?> = _imported.asStateFlow()

        fun publish(account: ImportedSsoAccount) {
            _imported.value = account
        }

        fun consume() {
            _imported.value = null
        }
    }
