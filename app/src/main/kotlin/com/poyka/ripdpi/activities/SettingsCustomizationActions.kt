package com.poyka.ripdpi.activities

import com.poyka.ripdpi.data.DefaultAppRoutingRussianPresetId
import com.poyka.ripdpi.platform.LauncherIconController
import com.poyka.ripdpi.security.PinLockoutManager
import com.poyka.ripdpi.security.PinVerifier
import com.poyka.ripdpi.security.PinVerifyResult

private const val BackupPinLength = 4

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

    fun setExcludeRussianAppsEnabled(enabled: Boolean) {
        mutations.updateSetting(
            key = "excludeRussianAppsEnabled",
            value = enabled.toString(),
        ) {
            val updatedPresetIds = currentUiState().routingProtection.enabledPresetIds.toMutableSet()
            if (enabled) {
                updatedPresetIds += DefaultAppRoutingRussianPresetId
            } else {
                updatedPresetIds -= DefaultAppRoutingRussianPresetId
            }
            setExcludeRussianAppsEnabled(enabled)
            clearAppRoutingEnabledPresetIds()
            if (updatedPresetIds.isNotEmpty()) {
                addAllAppRoutingEnabledPresetIds(updatedPresetIds.sorted())
            }
        }
    }

    fun setFullTunnelMode(enabled: Boolean) {
        mutations.updateSetting(
            key = "fullTunnelMode",
            value = enabled.toString(),
        ) {
            setFullTunnelMode(enabled)
        }
    }

    fun setCommunityApiUrl(url: String) {
        mutations.updateSetting(
            key = "communityApiUrl",
            value = url,
        ) {
            setCommunityApiUrl(url)
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
        if (enabled && !currentUiState().hasBackupPin) return
        mutations.updateSetting(
            key = "biometricEnabled",
            value = enabled.toString(),
        ) {
            setBiometricEnabled(enabled)
        }
    }

    fun setBackupPin(pin: String) {
        if (pin.isNotBlank() && (pin.length != BackupPinLength || !pin.all { it.isDigit() })) return
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

        val validPin = pin.length == BackupPinLength && pin.all { it.isDigit() }
        val storedHash = currentUiState().backupPinHash
        val matched = validPin && storedHash.isNotBlank() && matchesStoredPin(pin, storedHash)

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

    private fun matchesStoredPin(
        pin: String,
        storedHash: String,
    ): Boolean = pinVerifier.verify(pin, storedHash)
}
