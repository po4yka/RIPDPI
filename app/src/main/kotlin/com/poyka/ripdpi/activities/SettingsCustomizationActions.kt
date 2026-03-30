package com.poyka.ripdpi.activities

import com.poyka.ripdpi.platform.LauncherIconController
import com.poyka.ripdpi.security.PinLockoutManager
import com.poyka.ripdpi.security.PinVerifier
import com.poyka.ripdpi.security.PinVerifyResult
import java.security.MessageDigest

internal class SettingsCustomizationActions(
    private val mutations: SettingsMutationRunner,
    private val launcherIconController: LauncherIconController,
    private val currentUiState: () -> SettingsUiState,
    private val pinVerifier: PinVerifier,
    private val pinLockoutManager: PinLockoutManager,
) {
    fun setWebRtcProtectionEnabled(enabled: Boolean) {
        mutations.updateSetting(
            key = "webrtcProtectionEnabled",
            value = enabled.toString(),
        ) {
            setWebrtcProtectionEnabled(enabled)
        }
    }

    fun setAppTheme(theme: String) {
        mutations.updateSetting(
            key = "appTheme",
            value = theme,
        ) {
            setAppTheme(theme)
        }
    }

    fun setAppIcon(iconKey: String) {
        val normalizedIconKey = LauncherIconManager.normalizeIconKey(iconKey)
        val iconStyle =
            if (currentUiState().themedAppIconEnabled) {
                LauncherIconManager.ThemedIconStyle
            } else {
                LauncherIconManager.PlainIconStyle
            }

        mutations.launch {
            updateDirect {
                setAppIconVariant(normalizedIconKey)
            }
            launcherIconController.applySelection(
                iconKey = normalizedIconKey,
                iconStyle = iconStyle,
            )
            emit(SettingsEffect.SettingChanged(key = "appIconVariant", value = normalizedIconKey))
        }
    }

    fun setThemedAppIconEnabled(enabled: Boolean) {
        val iconStyle =
            if (enabled) {
                LauncherIconManager.ThemedIconStyle
            } else {
                LauncherIconManager.PlainIconStyle
            }

        mutations.launch {
            updateDirect {
                setAppIconStyle(iconStyle)
            }
            launcherIconController.applySelection(
                iconKey = currentUiState().appIconVariant,
                iconStyle = iconStyle,
            )
            emit(SettingsEffect.SettingChanged(key = "appIconStyle", value = iconStyle))
        }
    }

    fun setBiometricEnabled(enabled: Boolean) {
        mutations.updateSetting(
            key = "biometricEnabled",
            value = enabled.toString(),
        ) {
            setBiometricEnabled(enabled)
        }
    }

    fun setBackupPin(pin: String) {
        val hashed = if (pin.isBlank()) "" else pinVerifier.hashPin(pin)
        mutations.updateSetting(
            key = "backupPin",
            value = hashed,
        ) {
            setBackupPin(hashed)
        }
    }

    fun verifyBackupPin(pin: String): PinVerifyResult {
        if (pinLockoutManager.isLockedOut()) {
            return PinVerifyResult.LockedOut(pinLockoutManager.remainingLockoutMs())
        }

        val storedHash = currentUiState().backupPinHash
        val matched = storedHash.isNotBlank() && matchesStoredPin(pin, storedHash)

        if (matched) {
            pinLockoutManager.recordSuccess()
        } else {
            pinLockoutManager.recordFailure()
        }

        return when {
            matched -> PinVerifyResult.Success
            pinLockoutManager.isLockedOut() -> PinVerifyResult.LockedOut(pinLockoutManager.remainingLockoutMs())
            else -> PinVerifyResult.Failed
        }
    }

    private fun matchesStoredPin(
        pin: String,
        storedHash: String,
    ): Boolean {
        if (pinVerifier.verify(pin, storedHash)) return true
        val isLegacyMatch =
            MessageDigest.isEqual(
                legacySha256Hash(pin).toByteArray(Charsets.UTF_8),
                storedHash.toByteArray(Charsets.UTF_8),
            )
        if (isLegacyMatch) {
            val newHash = pinVerifier.hashPin(pin)
            mutations.updateSetting(key = "backupPin", value = newHash) { setBackupPin(newHash) }
        }
        return isLegacyMatch
    }

    private fun legacySha256Hash(pin: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(pin.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}
