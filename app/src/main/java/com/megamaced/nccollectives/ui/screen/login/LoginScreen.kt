package com.megamaced.nccollectives.ui.screen.login

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nextcloud.android.sso.AccountImporter
import com.nextcloud.android.sso.FilesAppTypeRegistry
import timber.log.Timber

@Composable
fun LoginScreen(viewModel: LoginViewModel = hiltViewModel()) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }

    // Only worth offering when there's an app to import from. Resolved once
    // per composition rather than per frame; installing the Nextcloud app
    // while this screen is open is rare enough to leave to a restart.
    val filesAppInstalled = remember(context) { isNextcloudFilesAppInstalled(context) }
    val activity = remember(context) { context.findActivity() }

    LaunchedEffect(uiState.loginUrl) {
        uiState.loginUrl?.let { url -> launchCustomTab(context, url) }
    }

    LaunchedEffect(uiState.error) {
        uiState.error?.let { error ->
            snackbarHostState.showSnackbar(error)
            viewModel.dismissError()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = "NC Collectives",
                style = MaterialTheme.typography.headlineLarge,
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "Connect to your Nextcloud server",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(modifier = Modifier.height(32.dp))

            if (filesAppInstalled) {
                Button(
                    onClick = { pickNextcloudAccount(activity, viewModel::onSsoImportFailed) },
                    enabled = !uiState.isLoading && !uiState.isPolling,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Log in with the Nextcloud app")
                }

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = "Pick an account you've already set up in the Nextcloud app — " +
                        "no browser, no second password.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )

                Spacer(modifier = Modifier.height(24.dp))

                HorizontalDivider()

                Spacer(modifier = Modifier.height(24.dp))

                Text(
                    text = "Or connect to a server directly",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Spacer(modifier = Modifier.height(16.dp))
            }

            OutlinedTextField(
                value = uiState.hostInput,
                onValueChange = viewModel::onHostChanged,
                label = { Text("Server URL") },
                placeholder = { Text("cloud.example.com") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Uri,
                    imeAction = ImeAction.Go,
                ),
                keyboardActions = KeyboardActions(onGo = { viewModel.startLogin() }),
                enabled = !uiState.isLoading && !uiState.isPolling,
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(modifier = Modifier.height(16.dp))

            Button(
                onClick = viewModel::startLogin,
                enabled = !uiState.isLoading && !uiState.isPolling,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (uiState.isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                } else {
                    Text("Log in")
                }
            }

            if (uiState.isPolling) {
                Spacer(modifier = Modifier.height(24.dp))
                CircularProgressIndicator(modifier = Modifier.size(32.dp))
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Waiting for authorisation…",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * Whether any flavour of the Nextcloud Files app is on the device.
 *
 * Checks for the *package*, not for importable accounts: before the user has
 * granted this app access, the Files app's accounts aren't necessarily
 * visible to us through `AccountManager`, so counting them would hide the
 * button in exactly the case it exists for. The package IDs come from the SSO
 * library's own registry (prod / beta / QA), and the `<queries>` entries its
 * manifest contributes are what make them visible under Android 11+ package
 * visibility.
 */
@Suppress("DEPRECATION") // getPackageInfo(String, Int); the flags-object overload is API 33+.
private fun isNextcloudFilesAppInstalled(context: Context): Boolean =
    FilesAppTypeRegistry.getInstance().types.any { type ->
        runCatching { context.packageManager.getPackageInfo(type.packageId, 0) }.isSuccess
    }

/**
 * Hand off to the Nextcloud app's account picker.
 *
 * `pickNewAccount` reports both of its failure modes — no Files app
 * installed, and (below API 26 only) a missing `GET_ACCOUNTS` grant — by
 * throwing, so `runCatching` is the whole error path. Neither is fatal to
 * this screen: the server-URL login below still works.
 *
 * The result does not come back here. It arrives two Activity results later
 * in `MainActivity.onActivityResult` and reaches `LoginViewModel` through
 * `SsoAccountHolder`.
 */
private fun pickNextcloudAccount(
    activity: Activity?,
    onError: (String) -> Unit,
) {
    if (activity == null) {
        onError("Couldn't open the Nextcloud account picker.")
        return
    }
    runCatching { AccountImporter.pickNewAccount(activity) }
        .onFailure { onError(it.message ?: "The Nextcloud app couldn't be opened.") }
}

/**
 * Unwrap the Activity behind a Compose `LocalContext`.
 *
 * `AccountImporter.pickNewAccount` needs a real Activity — it calls
 * `startActivityForResult` on it, and the result comes back through
 * `MainActivity.onActivityResult`. The context Compose hands out is a
 * `ContextThemeWrapper` around it, so unwrap rather than cast.
 */
private fun Context.findActivity(): Activity? {
    var current: Context? = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return null
}

private fun launchCustomTab(
    context: Context,
    url: String,
) {
    val uri = runCatching { Uri.parse(url) }.getOrNull()
    // S-26: `launchUrl` resolves whatever scheme it is handed through the
    // system, so a server-supplied `intent:` / custom-scheme URL would
    // start another app rather than a browser tab. `LoginViewModel` already
    // refuses a login URL that isn't https on the host the user typed; this
    // gate is the scheme half, held locally where the launch happens.
    if (uri == null || !uri.scheme.equals("https", ignoreCase = true)) {
        Timber.w("Refusing to open a login URL with scheme=%s", uri?.scheme)
        return
    }
    val intent = CustomTabsIntent.Builder().build()
    intent.launchUrl(context, uri)
}
