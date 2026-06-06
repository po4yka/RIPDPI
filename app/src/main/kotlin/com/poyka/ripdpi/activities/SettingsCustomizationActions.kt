package com.poyka.ripdpi.activities

import com.poyka.ripdpi.data.DefaultAppRoutingRussianPresetId
import com.poyka.ripdpi.platform.LauncherIconController
import com.poyka.ripdpi.platform.StartOnBootController
import com.poyka.ripdpi.security.PinLockoutManager
import com.poyka.ripdpi.security.PinVerifier
import com.poyka.ripdpi.security.PinVerifyResult
import com.poyka.ripdpi.ui.state.SettingsUiState
import java.security.SecureRandom

private const val BackupPinLength = 4
private const val LanAuthTokenBytes = 16

/** Mint a 128-bit random LAN access token, hex-encoded (32 chars). */
private fun generateLanAuthToken(): String {
    val bytes = ByteArray(LanAuthTokenBytes)
    SecureRandom().nextBytes(bytes)
    return bytes.joinToString("") { "%02x".format(it) }
}

internal class SettingsCustomizationActions(
    private val mutations: SettingsMutationRunner,
    private val launcherIconController: LauncherIconController,
    private val startOnBootController: StartOnBootController,
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

    fun setStartOnBootEnabled(enabled: Boolean) {
        mutations.updateSetting(
            key = "startOnBoot",
            value = enabled.toString(),
        ) {
            setStartOnBoot(enabled)
        }
        startOnBootController.setReceiverEnabled(enabled)
        if (enabled && !startOnBootController.isBatteryOptimizationIgnored()) {
            mutations.launch {
                emit(SettingsEffect.RequestDisableBatteryOptimization)
            }
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

    fun setMixedInboundEnabled(enabled: Boolean) {
        mutations.updateSetting(
            key = "mixedInboundEnabled",
            value = enabled.toString(),
        ) {
            setMixedInboundEnabled(enabled)
        }
    }

    fun setAppendHttpProxy(enabled: Boolean) {
        mutations.updateSetting(
            key = "appendHttpProxy",
            value = enabled.toString(),
        ) {
            setAppendHttpProxy(enabled)
        }
    }

    /**
     * Toggle binding the local listener to all interfaces (LAN). The native
     * core rejects a non-loopback listener without an access token, so the
     * first time LAN access is enabled we mint a random token and persist it;
     * the token is reused on subsequent toggles so paired devices keep working.
     */
    fun setProxyAllowLan(enabled: Boolean) {
        mutations.updateSetting(
            key = "proxyAllowLan",
            value = enabled.toString(),
        ) {
            setProxyAllowLan(enabled)
            if (enabled && proxyLanAuthToken.isEmpty()) {
                setProxyLanAuthToken(generateLanAuthToken())
            }
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

    fun setUiPersona(persona: String) {
        val normalized = if (persona == "advanced") "advanced" else "simple"
        mutations.updateSetting(
            key = "uiPersona",
            value = normalized,
        ) {
            setUiPersona(normalized)
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
