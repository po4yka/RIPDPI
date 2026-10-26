package com.poyka.ripdpi.security

import android.os.Build
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextObfuscationMode
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.fragment.app.FragmentActivity
import com.poyka.ripdpi.R
import com.poyka.ripdpi.ui.components.feedback.RipDpiDialog
import com.poyka.ripdpi.ui.components.feedback.RipDpiDialogAction
import com.poyka.ripdpi.ui.components.inputs.RipDpiTextField
import com.poyka.ripdpi.ui.components.inputs.RipDpiTextFieldBehavior
import com.poyka.ripdpi.ui.components.inputs.RipDpiTextFieldDecoration
import com.poyka.ripdpi.ui.testing.RipDpiTestTags
import kotlinx.coroutines.launch

private const val MsPerSecond = 1_000L

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
 * A device without a biometric or a screen lock has nothing to authenticate against. Then the action
 * asks for the app's backup PIN when [appPin] has one, and calls [onAuthorized] at once when it has
 * none. Without a host activity for the system prompt, the action does nothing.
 */
@Composable
internal fun rememberSecretRevealGate(
    onAuthorized: () -> Unit,
    appPin: AppPinCheck?,
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
    var askingPin by remember { mutableStateOf(false) }
    if (askingPin && appPin != null) {
        AppPinDialog(
            appPin = appPin,
            onAuthorized = {
                askingPin = false
                currentOnAuthorized()
            },
            onDismiss = { askingPin = false },
        )
    }
    return remember(context, activity, scope, systemPrompt, appPin) {
        {
            // Read the device state per reveal: the user may have removed the screen lock meanwhile.
            val authenticator = manager.appLockAuthenticator(context)
            when {
                authenticator == AppLockAuthenticator.None && appPin == null -> {
                    currentOnAuthorized()
                }

                authenticator == AppLockAuthenticator.None && appPin != null -> {
                    // The app's own backup PIN is the only lock left; ask for it when it is set.
                    scope.launch { if (appPin.hasPin()) askingPin = true else currentOnAuthorized() }
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

@Composable
private fun AppPinDialog(
    appPin: AppPinCheck,
    onAuthorized: () -> Unit,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var pin by remember { mutableStateOf("") }
    var result by remember { mutableStateOf<PinVerifyResult?>(null) }
    val errorText =
        when (val current = result) {
            PinVerifyResult.Failed -> {
                stringResource(R.string.biometric_prompt_pin_error)
            }

            is PinVerifyResult.LockedOut -> {
                val seconds = ((current.remainingMs + MsPerSecond - 1) / MsPerSecond).toInt()
                pluralStringResource(R.plurals.biometric_prompt_pin_lockout_body, seconds, seconds)
            }

            PinVerifyResult.Success, null -> {
                null
            }
        }
    RipDpiDialog(
        onDismissRequest = onDismiss,
        title = stringResource(R.string.biometric_prompt_pin_title),
        dismissAction =
            RipDpiDialogAction(label = stringResource(R.string.biometric_dialog_cancel), onClick = onDismiss),
        confirmAction =
            RipDpiDialogAction(
                label = stringResource(R.string.biometric_prompt_action),
                onClick = {
                    scope.launch {
                        val verified = appPin.verify(pin)
                        result = verified
                        if (verified is PinVerifyResult.Success) onAuthorized()
                    }
                },
                testTag = RipDpiTestTags.BiometricPromptPrimaryAction,
            ),
    ) {
        RipDpiTextField(
            value = pin,
            onValueChange = { value ->
                pin = value.filter(Char::isDigit)
                result = null
            },
            decoration =
                RipDpiTextFieldDecoration(
                    label = stringResource(R.string.biometric_prompt_pin_label),
                    placeholder = stringResource(R.string.biometric_prompt_pin_placeholder),
                    helperText = stringResource(R.string.biometric_prompt_pin_helper),
                    errorText = errorText,
                    testTag = RipDpiTestTags.BiometricPromptPinField,
                ),
            behavior =
                RipDpiTextFieldBehavior(
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    textObfuscationMode = TextObfuscationMode.Hidden,
                ),
        )
    }
}
