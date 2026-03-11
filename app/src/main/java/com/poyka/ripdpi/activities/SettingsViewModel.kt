package com.poyka.ripdpi.activities

import androidx.annotation.VisibleForTesting
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.poyka.ripdpi.data.AppSettingsRepository
import com.poyka.ripdpi.data.AppSettingsSerializer
import com.poyka.ripdpi.data.DefaultFakeOffsetMarker
import com.poyka.ripdpi.data.DefaultSplitMarker
import com.poyka.ripdpi.data.DefaultTlsRecordMarker
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.TcpChainStepKind
import com.poyka.ripdpi.data.TcpChainStepModel
import com.poyka.ripdpi.data.UdpChainStepModel
import com.poyka.ripdpi.data.effectiveFakeOffsetMarker
import com.poyka.ripdpi.data.effectiveSplitMarker
import com.poyka.ripdpi.data.effectiveTcpChainSteps
import com.poyka.ripdpi.data.effectiveTlsRecordMarker
import com.poyka.ripdpi.data.effectiveUdpChainSteps
import com.poyka.ripdpi.data.formatChainSummary
import com.poyka.ripdpi.data.formatStrategyChainDsl
import com.poyka.ripdpi.data.legacyDesyncMethod
import com.poyka.ripdpi.data.primaryTcpChainStep
import com.poyka.ripdpi.data.tlsRecTcpChainStep
import com.poyka.ripdpi.platform.LauncherIconController
import com.poyka.ripdpi.proto.AppSettings
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

sealed interface SettingsEffect {
    data class SettingChanged(
        val key: String,
        val value: String,
    ) : SettingsEffect
}

data class SettingsUiState(
    val settings: AppSettings = AppSettingsSerializer.defaultValue,
    val appTheme: String = "system",
    val appIconVariant: String = LauncherIconManager.DefaultIconKey,
    val themedAppIconEnabled: Boolean = true,
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
    val tcpChainSteps: List<TcpChainStepModel> = emptyList(),
    val udpChainSteps: List<UdpChainStepModel> = emptyList(),
    val chainSummary: String = "tcp: none",
    val chainDsl: String = "",
    val splitMarker: String = DefaultSplitMarker,
    val fakeTtl: Int = 8,
    val fakeSni: String = "www.iana.org",
    val fakeOffsetMarker: String = DefaultFakeOffsetMarker,
    val oobData: String = "a",
    val dropSack: Boolean = false,
    val desyncHttp: Boolean = true,
    val desyncHttps: Boolean = true,
    val desyncUdp: Boolean = false,
    val hostsMode: String = "disable",
    val hostsBlacklist: String = "",
    val hostsWhitelist: String = "",
    val tlsrecEnabled: Boolean = false,
    val tlsrecMarker: String = DefaultTlsRecordMarker,
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
    val isHydrated: Boolean = true,
) {
    val hasBackupPin: Boolean
        get() = backupPin.isNotBlank()
}

@VisibleForTesting
internal fun AppSettings.toUiState(isHydrated: Boolean = true): SettingsUiState {
    val normalizedMode = ripdpiMode.ifEmpty { "vpn" }
    val tcpChainSteps = effectiveTcpChainSteps()
    val udpChainSteps = effectiveUdpChainSteps()
    val primaryTcpStep = primaryTcpChainStep(tcpChainSteps)
    val tlsRecStep = tlsRecTcpChainStep(tcpChainSteps)
    val normalizedDesyncMethod = legacyDesyncMethod(tcpChainSteps).ifEmpty { "none" }
    val normalizedHostsMode = hostsMode.ifEmpty { "disable" }
    val isVpn = normalizedMode == "vpn"
    val useCmdSettings = enableCmdSettings
    val desyncEnabled = primaryTcpStep != null
    val isFake = tcpChainSteps.any { it.kind == TcpChainStepKind.Fake }
    val isOob = tcpChainSteps.any { it.kind == TcpChainStepKind.Oob || it.kind == TcpChainStepKind.Disoob }

    val desyncAllUnchecked = !desyncHttp && !desyncHttps && !desyncUdp
    val desyncHttpEnabled = desyncAllUnchecked || desyncHttp
    val desyncHttpsEnabled = desyncAllUnchecked || desyncHttps
    val desyncUdpEnabled = desyncAllUnchecked || desyncUdp
    val tlsRecEnabled = desyncHttpsEnabled && tlsRecStep != null

    return SettingsUiState(
        settings = this,
        appTheme = appTheme.ifEmpty { "system" },
        appIconVariant = LauncherIconManager.normalizeIconKey(appIconVariant),
        themedAppIconEnabled =
            LauncherIconManager.normalizeIconStyle(appIconStyle) == LauncherIconManager.ThemedIconStyle,
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
        tcpChainSteps = tcpChainSteps,
        udpChainSteps = udpChainSteps,
        chainSummary = formatChainSummary(tcpChainSteps, udpChainSteps),
        chainDsl = formatStrategyChainDsl(tcpChainSteps, udpChainSteps),
        splitMarker = primaryTcpStep?.marker ?: effectiveSplitMarker(),
        fakeTtl = fakeTtl.takeIf { it > 0 } ?: 8,
        fakeSni = fakeSni.ifEmpty { "www.iana.org" },
        fakeOffsetMarker = effectiveFakeOffsetMarker(),
        oobData = oobData.ifEmpty { "a" },
        dropSack = dropSack,
        desyncHttp = desyncHttp,
        desyncHttps = desyncHttps,
        desyncUdp = desyncUdp,
        hostsMode = normalizedHostsMode,
        hostsBlacklist = hostsBlacklist,
        hostsWhitelist = hostsWhitelist,
        tlsrecEnabled = tlsRecStep != null,
        tlsrecMarker = tlsRecStep?.marker ?: effectiveTlsRecordMarker(),
        udpFakeCount = udpChainSteps.sumOf { it.count.coerceAtLeast(0) }.takeIf { it > 0 } ?: udpFakeCount,
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
        isHydrated = isHydrated,
    )
}

@HiltViewModel
class SettingsViewModel
    @Inject
    constructor(
        private val appSettingsRepository: AppSettingsRepository,
        private val launcherIconController: LauncherIconController,
    ) : ViewModel() {
    private val _effects = Channel<SettingsEffect>(Channel.BUFFERED)
    val effects: Flow<SettingsEffect> = _effects.receiveAsFlow()

    val uiState: StateFlow<SettingsUiState> =
        appSettingsRepository.settings
            .map { it.toUiState() }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = AppSettingsSerializer.defaultValue.toUiState(isHydrated = false),
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

    fun setAppTheme(theme: String) {
        updateSetting(
            key = "appTheme",
            value = theme,
        ) {
            setAppTheme(theme)
        }
    }

    fun setAppIcon(iconKey: String) {
        val normalizedIconKey = LauncherIconManager.normalizeIconKey(iconKey)
        val iconStyle =
            if (uiState.value.themedAppIconEnabled) {
                LauncherIconManager.ThemedIconStyle
            } else {
                LauncherIconManager.PlainIconStyle
            }

        viewModelScope.launch {
            appSettingsRepository.update {
                setAppIconVariant(normalizedIconKey)
            }
            launcherIconController.applySelection(
                iconKey = normalizedIconKey,
                iconStyle = iconStyle,
            )
            _effects.send(SettingsEffect.SettingChanged(key = "appIconVariant", value = normalizedIconKey))
        }
    }

    fun setThemedAppIconEnabled(enabled: Boolean) {
        val iconStyle =
            if (enabled) {
                LauncherIconManager.ThemedIconStyle
            } else {
                LauncherIconManager.PlainIconStyle
            }

        viewModelScope.launch {
            appSettingsRepository.update {
                setAppIconStyle(iconStyle)
            }
            launcherIconController.applySelection(
                iconKey = uiState.value.appIconVariant,
                iconStyle = iconStyle,
            )
            _effects.send(SettingsEffect.SettingChanged(key = "appIconStyle", value = iconStyle))
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
            appSettingsRepository.replace(AppSettingsSerializer.defaultValue)
            _effects.send(SettingsEffect.SettingChanged(key = "settings", value = "reset"))
        }
    }

    private fun mutateSettings(
        effect: SettingsEffect?,
        transform: AppSettings.Builder.() -> Unit,
    ) {
        viewModelScope.launch {
            appSettingsRepository.update(transform)
            effect?.let { _effects.send(it) }
        }
    }
}
