package com.poyka.ripdpi.activities

import com.poyka.ripdpi.platform.LauncherIconController

internal class SettingsCustomizationActions(
    private val mutations: SettingsMutationRunner,
    private val launcherIconController: LauncherIconController,
    private val currentUiState: () -> SettingsUiState,
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
        val hashed = if (pin.isBlank()) "" else hashPin(pin)
        mutations.updateSetting(
            key = "backupPin",
            value = hashed,
        ) {
            setBackupPin(hashed)
        }
    }

    fun verifyBackupPin(pin: String): Boolean {
        val state = currentUiState()
        return state.backupPinHash.isNotBlank() && hashPin(pin) == state.backupPinHash
    }
}
