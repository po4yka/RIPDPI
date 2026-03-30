package com.poyka.ripdpi.security

import android.content.Context
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

sealed interface BiometricAuthResult {
    data object Success : BiometricAuthResult

    data class Error(
        val errorCode: Int,
        val message: String,
    ) : BiometricAuthResult

    data object Failed : BiometricAuthResult

    data object Cancelled : BiometricAuthResult
}

class BiometricAuthManager {
    suspend fun authenticate(
        activity: FragmentActivity,
        title: String,
        subtitle: String,
        cancelLabel: String,
    ): BiometricAuthResult =
        suspendCancellableCoroutine { continuation ->
            val executor = ContextCompat.getMainExecutor(activity)
            val callback =
                object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                        if (continuation.isActive) {
                            continuation.resume(BiometricAuthResult.Success)
                        }
                    }

                    override fun onAuthenticationError(
                        errorCode: Int,
                        errString: CharSequence,
                    ) {
                        if (continuation.isActive) {
                            val result =
                                if (errorCode == BiometricPrompt.ERROR_USER_CANCELED ||
                                    errorCode == BiometricPrompt.ERROR_NEGATIVE_BUTTON ||
                                    errorCode == BiometricPrompt.ERROR_CANCELED
                                ) {
                                    BiometricAuthResult.Cancelled
                                } else {
                                    BiometricAuthResult.Error(errorCode, errString.toString())
                                }
                            continuation.resume(result)
                        }
                    }

                    override fun onAuthenticationFailed() {
                        // Called on each failed attempt (e.g. unrecognized fingerprint).
                        // The system dialog handles retry UI, so we do not resume here.
                    }
                }

            val prompt = BiometricPrompt(activity, executor, callback)
            val promptInfo =
                BiometricPrompt.PromptInfo
                    .Builder()
                    .setTitle(title)
                    .setSubtitle(subtitle)
                    .setNegativeButtonText(cancelLabel)
                    .setAllowedAuthenticators(AUTHENTICATORS)
                    .build()

            continuation.invokeOnCancellation { prompt.cancelAuthentication() }
            prompt.authenticate(promptInfo)
        }

    fun canAuthenticate(context: Context): Int = BiometricManager.from(context).canAuthenticate(AUTHENTICATORS)

    private companion object {
        const val AUTHENTICATORS =
            BiometricManager.Authenticators.BIOMETRIC_STRONG or
                BiometricManager.Authenticators.BIOMETRIC_WEAK
    }
}
