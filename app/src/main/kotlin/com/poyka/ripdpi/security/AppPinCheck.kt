package com.poyka.ripdpi.security

import androidx.lifecycle.ViewModel
import com.poyka.ripdpi.data.AppSettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

private const val BackupPinLength = 4

/** The app's own backup PIN, which guards a secret reveal on a device without a screen lock. */
interface AppPinCheck {
    suspend fun hasPin(): Boolean

    suspend fun verify(pin: String): PinVerifyResult
}

/** Checks [pin] against [storedHash] and counts a wrong four-digit PIN toward the lockout. */
fun verifyBackupPin(
    pin: String,
    storedHash: String,
    pinVerifier: PinVerifier,
    pinLockoutManager: PinLockoutManager,
): PinVerifyResult {
    if (pinLockoutManager.isLockedOut()) {
        return PinVerifyResult.LockedOut(pinLockoutManager.remainingLockoutMs())
    }

    val validPin = pin.length == BackupPinLength && pin.all { it.isDigit() }
    val matched = validPin && storedHash.isNotBlank() && pinVerifier.verify(pin, storedHash)

    if (matched) {
        pinLockoutManager.recordSuccess()
    } else if (validPin) {
        pinLockoutManager.recordFailure()
    }

    return when {
        matched -> PinVerifyResult.Success
        pinLockoutManager.isLockedOut() -> PinVerifyResult.LockedOut(pinLockoutManager.remainingLockoutMs())
        else -> PinVerifyResult.Failed
    }
}

@HiltViewModel
class SecretRevealPinViewModel
    @Inject
    constructor(
        private val appSettingsRepository: AppSettingsRepository,
        private val pinVerifier: PinVerifier,
        private val pinLockoutManager: PinLockoutManager,
    ) : ViewModel(),
        AppPinCheck {
        override suspend fun hasPin(): Boolean = appSettingsRepository.snapshot().backupPin.isNotBlank()

        override suspend fun verify(pin: String): PinVerifyResult =
            verifyBackupPin(pin, appSettingsRepository.snapshot().backupPin, pinVerifier, pinLockoutManager)
    }
