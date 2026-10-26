package com.poyka.ripdpi.security

import android.os.Build
import androidx.activity.compose.LocalActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.fragment.app.FragmentActivity
import com.poyka.ripdpi.R
import kotlinx.coroutines.launch

/** Shows the system biometric or screen-lock prompt with the given authenticators. */
fun interface SecretRevealPrompt {
    suspend fun authenticate(
        activity: FragmentActivity,
        allowedAuthenticators: Int,
    ): BiometricAuthResult
}

/**
 * Returns an action that asks for the device's biometric or screen-lock authentication and calls
 * [onAuthorized] only when it succeeds. Use it before a stored secret (a private key, a passphrase)
 * shows in plain text.
 *
 * A device without a biometric or a screen lock has nothing to authenticate against, so the action
 * calls [onAuthorized] at once. Without a host activity for the system prompt, the action does nothing.
 */
@Composable
internal fun rememberSecretRevealGate(
    onAuthorized: () -> Unit,
    prompt: SecretRevealPrompt? = null,
): () -> Unit {
    val context = LocalContext.current
    val activity = LocalActivity.current as? FragmentActivity
    val scope = rememberCoroutineScope()
    val currentOnAuthorized by rememberUpdatedState(onAuthorized)
    val title = stringResource(R.string.biometric_dialog_title)
    val subtitle = stringResource(R.string.biometric_dialog_subtitle)
    val cancel = stringResource(R.string.biometric_dialog_cancel)
    val manager = remember { BiometricAuthManager() }
    val systemPrompt =
        prompt ?: remember(title, subtitle, cancel) {
            SecretRevealPrompt { host, authenticators ->
                manager.authenticate(host, title, subtitle, cancel, authenticators)
            }
        }
    return remember(context, activity, scope, systemPrompt) {
        {
            // Read the device state per reveal: the user may have removed the screen lock meanwhile.
            val authenticator = manager.appLockAuthenticator(context)
            when {
                authenticator == AppLockAuthenticator.None -> {
                    currentOnAuthorized()
                }

                activity != null -> {
                    scope.launch {
                        val result =
                            systemPrompt.authenticate(
                                activity,
                                appLockPromptAuthenticators(authenticator, Build.VERSION.SDK_INT),
                            )
                        if (result is BiometricAuthResult.Success) currentOnAuthorized()
                    }
                }

                else -> {
                    // No host for the system prompt: keep the secret hidden.
                    Unit
                }
            }
        }
    }
}
