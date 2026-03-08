package com.poyka.ripdpi.activities

import android.app.Application
import androidx.annotation.VisibleForTesting
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.poyka.ripdpi.data.AppSettingsSerializer
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.settingsStore
import com.poyka.ripdpi.proto.AppSettings
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

sealed interface SettingsEffect {
    data class SettingChanged(val key: String, val value: String) : SettingsEffect
}

data class SettingsUiState(
    val settings: AppSettings = AppSettingsSerializer.defaultValue,
    val appTheme: String = "system",
    val ripdpiMode: String = "vpn",
    val dnsIp: String = "1.1.1.1",
    val ipv6Enable: Boolean = false,
    val enableCmdSettings: Boolean = false,
    val cmdArgs: String = "",
    val proxyIp: String = "127.0.0.1",
    val proxyPort: Int = 1080,
    val maxConnections: Int = 512,
    val bufferSize: Int = 16_384,
    val noDomain: Boolean = false,
    val tcpFastOpen: Boolean = false,
    val defaultTtl: Int = 0,
    val customTtl: Boolean = false,
    val desyncMethod: String = "disorder",
    val splitPosition: Int = 1,
    val splitAtHost: Boolean = false,
    val fakeTtl: Int = 8,
    val fakeSni: String = "www.iana.org",
    val fakeOffset: Int = 0,
    val oobData: String = "a",
    val dropSack: Boolean = false,
    val desyncHttp: Boolean = true,
    val desyncHttps: Boolean = true,
    val desyncUdp: Boolean = false,
    val hostsMode: String = "disable",
    val hostsBlacklist: String = "",
    val hostsWhitelist: String = "",
    val tlsrecEnabled: Boolean = false,
    val tlsrecPosition: Int = 0,
    val tlsrecAtSni: Boolean = false,
    val udpFakeCount: Int = 0,
    val hostMixedCase: Boolean = false,
    val domainMixedCase: Boolean = false,
    val hostRemoveSpaces: Boolean = false,
    val onboardingComplete: Boolean = false,
    val webrtcProtectionEnabled: Boolean = false,
    val biometricEnabled: Boolean = false,
    val backupPin: String = "",
    val isVpn: Boolean = true,
    val selectedMode: Mode = Mode.VPN,
    val useCmdSettings: Boolean = false,
    val desyncEnabled: Boolean = true,
    val isFake: Boolean = false,
    val isOob: Boolean = false,
    val desyncHttpEnabled: Boolean = true,
    val desyncHttpsEnabled: Boolean = true,
    val desyncUdpEnabled: Boolean = false,
    val tlsRecEnabled: Boolean = false,
)

@VisibleForTesting
internal fun AppSettings.toUiState(): SettingsUiState {
    val normalizedMode = ripdpiMode.ifEmpty { "vpn" }
    val normalizedDesyncMethod = desyncMethod.ifEmpty { "disorder" }
    val normalizedHostsMode = hostsMode.ifEmpty { "disable" }
    val isVpn = normalizedMode == "vpn"
    val useCmdSettings = enableCmdSettings
    val desyncEnabled = normalizedDesyncMethod != "none"
    val isFake = normalizedDesyncMethod == "fake"
    val isOob = normalizedDesyncMethod == "oob" || normalizedDesyncMethod == "disoob"

    val desyncAllUnchecked = !desyncHttp && !desyncHttps && !desyncUdp
    val desyncHttpEnabled = desyncAllUnchecked || desyncHttp
    val desyncHttpsEnabled = desyncAllUnchecked || desyncHttps
    val desyncUdpEnabled = desyncAllUnchecked || desyncUdp
    val tlsRecEnabled = desyncHttpsEnabled && tlsrecEnabled

    return SettingsUiState(
        settings = this,
        appTheme = appTheme.ifEmpty { "system" },
        ripdpiMode = normalizedMode,
        dnsIp = dnsIp.ifEmpty { "1.1.1.1" },
        ipv6Enable = ipv6Enable,
        enableCmdSettings = enableCmdSettings,
        cmdArgs = cmdArgs,
        proxyIp = proxyIp.ifEmpty { "127.0.0.1" },
        proxyPort = proxyPort.takeIf { it > 0 } ?: 1080,
        maxConnections = maxConnections.takeIf { it > 0 } ?: 512,
        bufferSize = bufferSize.takeIf { it > 0 } ?: 16_384,
        noDomain = noDomain,
        tcpFastOpen = tcpFastOpen,
        defaultTtl = defaultTtl,
        customTtl = customTtl,
        desyncMethod = normalizedDesyncMethod,
        splitPosition = splitPosition,
        splitAtHost = splitAtHost,
        fakeTtl = fakeTtl.takeIf { it > 0 } ?: 8,
        fakeSni = fakeSni.ifEmpty { "www.iana.org" },
        fakeOffset = fakeOffset,
        oobData = oobData.ifEmpty { "a" },
        dropSack = dropSack,
        desyncHttp = desyncHttp,
        desyncHttps = desyncHttps,
        desyncUdp = desyncUdp,
        hostsMode = normalizedHostsMode,
        hostsBlacklist = hostsBlacklist,
        hostsWhitelist = hostsWhitelist,
        tlsrecEnabled = tlsrecEnabled,
        tlsrecPosition = tlsrecPosition,
        tlsrecAtSni = tlsrecAtSni,
        udpFakeCount = udpFakeCount,
        hostMixedCase = hostMixedCase,
        domainMixedCase = domainMixedCase,
        hostRemoveSpaces = hostRemoveSpaces,
        onboardingComplete = onboardingComplete,
        webrtcProtectionEnabled = webrtcProtectionEnabled,
        biometricEnabled = biometricEnabled,
        backupPin = backupPin,
        isVpn = isVpn,
        selectedMode = if (isVpn) Mode.VPN else Mode.Proxy,
        useCmdSettings = useCmdSettings,
        desyncEnabled = desyncEnabled,
        isFake = isFake,
        isOob = isOob,
        desyncHttpEnabled = desyncHttpEnabled,
        desyncHttpsEnabled = desyncHttpsEnabled,
        desyncUdpEnabled = desyncUdpEnabled,
        tlsRecEnabled = tlsRecEnabled,
    )
}

class SettingsViewModel(application: Application) : AndroidViewModel(application) {

    private val _effects = Channel<SettingsEffect>(Channel.BUFFERED)
    val effects: Flow<SettingsEffect> = _effects.receiveAsFlow()

    val uiState: StateFlow<SettingsUiState> = application.settingsStore.data
        .map { it.toUiState() }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = AppSettingsSerializer.defaultValue.toUiState(),
        )

    fun update(transform: AppSettings.Builder.() -> Unit) {
        mutateSettings(effect = null, transform = transform)
    }

    fun updateSetting(
        key: String,
        value: String,
        transform: AppSettings.Builder.() -> Unit,
    ) {
        mutateSettings(
            effect = SettingsEffect.SettingChanged(key = key, value = value),
            transform = transform,
        )
    }

    fun setWebRtcProtectionEnabled(enabled: Boolean) {
        updateSetting(
            key = "webrtcProtectionEnabled",
            value = enabled.toString(),
        ) {
            setWebrtcProtectionEnabled(enabled)
        }
    }

    fun setBiometricEnabled(enabled: Boolean) {
        updateSetting(
            key = "biometricEnabled",
            value = enabled.toString(),
        ) {
            setBiometricEnabled(enabled)
        }
    }

    fun setBackupPin(pin: String) {
        updateSetting(
            key = "backupPin",
            value = pin,
        ) {
            setBackupPin(pin)
        }
    }

    fun resetSettings() {
        viewModelScope.launch {
            getApplication<Application>().settingsStore.updateData {
                AppSettingsSerializer.defaultValue
            }
            _effects.send(SettingsEffect.SettingChanged(key = "settings", value = "reset"))
        }
    }

    private fun mutateSettings(
        effect: SettingsEffect?,
        transform: AppSettings.Builder.() -> Unit,
    ) {
        viewModelScope.launch {
            getApplication<Application>().settingsStore.updateData { current ->
                current.toBuilder().apply(transform).build()
            }
            effect?.let { _effects.send(it) }
        }
    }
}
