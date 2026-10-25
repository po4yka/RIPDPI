package com.poyka.ripdpi.security

import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import org.junit.Assert.assertEquals
import org.junit.Test

class AppLockAuthenticatorTest {
    @Test
    fun `removed fingerprints fall back to the screen lock instead of a dead end`() {
        val authenticator =
            resolveAppLockAuthenticator(
                strongBiometricStatus = BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED,
                isDeviceSecure = true,
            )

        assertEquals(AppLockAuthenticator.DeviceCredential, authenticator)
        assertEquals(AppLockStart.LaunchPrompt, appLockStart(authenticator, hasBackupPin = false))
    }

    @Test
    fun `backup PIN keeps priority over the screen lock when biometrics are missing`() {
        assertEquals(
            AppLockStart.UseBackupPin,
            appLockStart(AppLockAuthenticator.DeviceCredential, hasBackupPin = true),
        )
        assertEquals(AppLockStart.UseBackupPin, appLockStart(AppLockAuthenticator.None, hasBackupPin = true))
    }

    @Test
    fun `enrolled strong biometrics always open the prompt`() {
        val authenticator =
            resolveAppLockAuthenticator(BiometricManager.BIOMETRIC_SUCCESS, isDeviceSecure = true)

        assertEquals(AppLockAuthenticator.StrongBiometric, authenticator)
        assertEquals(AppLockStart.LaunchPrompt, appLockStart(authenticator, hasBackupPin = true))
    }

    @Test
    fun `lock opens only when the device has no authenticator and no backup PIN`() {
        val authenticator =
            resolveAppLockAuthenticator(BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE, isDeviceSecure = false)

        assertEquals(AppLockAuthenticator.None, authenticator)
        assertEquals(AppLockStart.ExplainNoLock, appLockStart(authenticator, hasBackupPin = false))
    }

    @Test
    fun `prompt authenticators respect the androidx API-level combinations`() {
        val strongOrCredential = BIOMETRIC_STRONG or DEVICE_CREDENTIAL
        assertEquals(strongOrCredential, appLockPromptAuthenticators(AppLockAuthenticator.StrongBiometric, 27))
        assertEquals(BIOMETRIC_STRONG, appLockPromptAuthenticators(AppLockAuthenticator.StrongBiometric, 28))
        assertEquals(BIOMETRIC_STRONG, appLockPromptAuthenticators(AppLockAuthenticator.StrongBiometric, 29))
        assertEquals(strongOrCredential, appLockPromptAuthenticators(AppLockAuthenticator.StrongBiometric, 30))

        val weakOrCredential = BIOMETRIC_WEAK or DEVICE_CREDENTIAL
        assertEquals(weakOrCredential, appLockPromptAuthenticators(AppLockAuthenticator.DeviceCredential, 29))
        assertEquals(DEVICE_CREDENTIAL, appLockPromptAuthenticators(AppLockAuthenticator.DeviceCredential, 30))
    }
}
