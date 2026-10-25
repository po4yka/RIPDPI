package com.poyka.ripdpi.security

import android.app.KeyguardManager
import android.content.Context
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.suspendCancellableCoroutine
import javax.inject.Inject
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

/** The way the app lock can verify the user on this device. */
enum class AppLockAuthenticator {
    StrongBiometric,
    DeviceCredential,
    None,
}

/** What the lock screen does when it opens on the biometric stage. */
enum class AppLockStart {
    LaunchPrompt,
    UseBackupPin,
    ExplainNoLock,
}

fun resolveAppLockAuthenticator(
    strongBiometricStatus: Int,
    isDeviceSecure: Boolean,
): AppLockAuthenticator =
    when {
        strongBiometricStatus == BiometricManager.BIOMETRIC_SUCCESS -> AppLockAuthenticator.StrongBiometric
        isDeviceSecure -> AppLockAuthenticator.DeviceCredential
        else -> AppLockAuthenticator.None
    }

/** The backup PIN keeps priority over the screen lock; the lock opens only when no authenticator exists. */
fun appLockStart(
    authenticator: AppLockAuthenticator,
    hasBackupPin: Boolean,
): AppLockStart =
    when {
        authenticator == AppLockAuthenticator.StrongBiometric -> AppLockStart.LaunchPrompt
        hasBackupPin -> AppLockStart.UseBackupPin
        authenticator == AppLockAuthenticator.DeviceCredential -> AppLockStart.LaunchPrompt
        else -> AppLockStart.ExplainNoLock
    }

/**
 * androidx.biometric rejects BIOMETRIC_STRONG | DEVICE_CREDENTIAL on API 28-29 and
 * DEVICE_CREDENTIAL alone below API 30.
 */
fun appLockPromptAuthenticators(
    authenticator: AppLockAuthenticator,
    sdkInt: Int,
): Int {
    val strong = BiometricManager.Authenticators.BIOMETRIC_STRONG
    val weak = BiometricManager.Authenticators.BIOMETRIC_WEAK
    val credential = BiometricManager.Authenticators.DEVICE_CREDENTIAL
    return when (authenticator) {
        AppLockAuthenticator.StrongBiometric -> if (sdkInt in ApiPie..ApiQ) strong else strong or credential
        AppLockAuthenticator.DeviceCredential -> if (sdkInt >= ApiR) credential else weak or credential
        AppLockAuthenticator.None -> error("No authenticator to prompt for")
    }
}

private const val ApiPie = 28
private const val ApiQ = 29
private const val ApiR = 30

class BiometricAuthManager {
    fun appLockAuthenticator(context: Context): AppLockAuthenticator {
        // Fail closed: a missing KeyguardManager must not count as "no screen lock".
        val isDeviceSecure = context.getSystemService(KeyguardManager::class.java)?.isDeviceSecure ?: true
        return resolveAppLockAuthenticator(
            strongBiometricStatus = canAuthenticate(context),
            isDeviceSecure = isDeviceSecure,
        )
    }

    suspend fun authenticate(
        activity: FragmentActivity,
        title: String,
        subtitle: String,
        cancelLabel: String,
        allowedAuthenticators: Int = AUTHENTICATORS,
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
            val builder =
                BiometricPrompt.PromptInfo
                    .Builder()
                    .setTitle(title)
                    .setSubtitle(subtitle)
                    .setAllowedAuthenticators(allowedAuthenticators)
            // With the credential allowed, the system shows its own button and rejects a negative one.
            if ((allowedAuthenticators and BiometricManager.Authenticators.DEVICE_CREDENTIAL) == 0) {
                builder.setNegativeButtonText(cancelLabel)
            }
            val promptInfo = builder.build()

            continuation.invokeOnCancellation { prompt.cancelAuthentication() }
            prompt.authenticate(promptInfo)
        }

    fun canAuthenticate(context: Context): Int = BiometricManager.from(context).canAuthenticate(AUTHENTICATORS)

    private companion object {
        const val AUTHENTICATORS = BiometricManager.Authenticators.BIOMETRIC_STRONG
    }
}

interface BiometricCapabilityChecker {
    fun canAuthenticate(): Int
}

class AndroidBiometricCapabilityChecker
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
    ) : BiometricCapabilityChecker {
        override fun canAuthenticate(): Int = BiometricManager.from(context).canAuthenticate(AUTHENTICATORS)

        private companion object {
            const val AUTHENTICATORS = BiometricManager.Authenticators.BIOMETRIC_STRONG
        }
    }
