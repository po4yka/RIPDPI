package com.poyka.ripdpi.activities

import android.content.Context
import androidx.annotation.VisibleForTesting
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.poyka.ripdpi.core.NativeRuntimeSnapshot
import com.poyka.ripdpi.data.AppSettingsRepository
import com.poyka.ripdpi.data.AppSettingsSerializer
import com.poyka.ripdpi.data.DefaultFakeOffsetMarker
import com.poyka.ripdpi.data.DefaultFakeSni
import com.poyka.ripdpi.data.DefaultQuicFakeHost
import com.poyka.ripdpi.data.DefaultHostAutolearnMaxHosts
import com.poyka.ripdpi.data.DefaultHostAutolearnPenaltyTtlHours
import com.poyka.ripdpi.data.FakePayloadProfileCompatDefault
import com.poyka.ripdpi.data.DefaultSplitMarker
import com.poyka.ripdpi.data.DefaultTlsRandRecFragmentCount
import com.poyka.ripdpi.data.DefaultTlsRandRecMaxFragmentSize
import com.poyka.ripdpi.data.DefaultTlsRandRecMinFragmentSize
import com.poyka.ripdpi.data.DefaultTlsRecordMarker
import com.poyka.ripdpi.data.FakeTlsSniModeFixed
import com.poyka.ripdpi.data.AppStatus
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.QuicFakeProfileDisabled
import com.poyka.ripdpi.data.QuicFakeProfileRealisticInitial
import com.poyka.ripdpi.data.QuicInitialModeRouteAndCache
import com.poyka.ripdpi.data.TcpChainStepKind
import com.poyka.ripdpi.data.TcpChainStepModel
import com.poyka.ripdpi.data.UdpChainStepModel
import com.poyka.ripdpi.data.effectiveHttpFakeProfile
import com.poyka.ripdpi.data.effectiveFakeOffsetMarker
import com.poyka.ripdpi.data.effectiveFakeTlsSniMode
import com.poyka.ripdpi.data.effectiveQuicFakeHost
import com.poyka.ripdpi.data.effectiveQuicFakeProfile
import com.poyka.ripdpi.data.effectiveQuicInitialMode
import com.poyka.ripdpi.data.effectiveQuicSupportV1
import com.poyka.ripdpi.data.effectiveQuicSupportV2
import com.poyka.ripdpi.data.effectiveSplitMarker
import com.poyka.ripdpi.data.effectiveTcpChainSteps
import com.poyka.ripdpi.data.effectiveTlsFakeProfile
import com.poyka.ripdpi.data.effectiveTlsRecordMarker
import com.poyka.ripdpi.data.effectiveUdpChainSteps
import com.poyka.ripdpi.data.effectiveUdpFakeProfile
import com.poyka.ripdpi.data.formatChainSummary
import com.poyka.ripdpi.data.formatStrategyChainDsl
import com.poyka.ripdpi.data.isTlsPrelude
import com.poyka.ripdpi.data.legacyDesyncMethod
import com.poyka.ripdpi.data.normalizeHostAutolearnMaxHosts
import com.poyka.ripdpi.data.normalizeHostAutolearnPenaltyTtlHours
import com.poyka.ripdpi.data.primaryTcpChainStep
import com.poyka.ripdpi.data.tlsPreludeTcpChainStep
import com.poyka.ripdpi.core.clearHostAutolearnStore
import com.poyka.ripdpi.core.hasHostAutolearnStore
import com.poyka.ripdpi.data.HostPackCatalogSnapshot
import com.poyka.ripdpi.data.HostPackPreset
import com.poyka.ripdpi.platform.LauncherIconController
import com.poyka.ripdpi.data.applyCuratedHostPack
import com.poyka.ripdpi.hosts.HostPackCatalogBuildException
import com.poyka.ripdpi.hosts.HostPackCatalogParseException
import com.poyka.ripdpi.hosts.HostPackChecksumFormatException
import com.poyka.ripdpi.hosts.HostPackChecksumMismatchException
import com.poyka.ripdpi.hosts.HostPackCatalogRepository
import com.poyka.ripdpi.proto.AppSettings
import com.poyka.ripdpi.services.ServiceStateStore
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

sealed interface SettingsEffect {
    data class SettingChanged(
        val key: String,
        val value: String,
    ) : SettingsEffect

    data class Notice(
        val title: String,
        val message: String,
        val tone: SettingsNoticeTone,
    ) : SettingsEffect
}

enum class SettingsNoticeTone {
    Info,
    Warning,
    Error,
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
    val fakeSni: String = DefaultFakeSni,
    val fakeOffsetMarker: String = DefaultFakeOffsetMarker,
    val httpFakeProfile: String = FakePayloadProfileCompatDefault,
    val fakeTlsUseOriginal: Boolean = false,
    val fakeTlsRandomize: Boolean = false,
    val fakeTlsDupSessionId: Boolean = false,
    val fakeTlsPadEncap: Boolean = false,
    val fakeTlsSize: Int = 0,
    val fakeTlsSniMode: String = FakeTlsSniModeFixed,
    val tlsFakeProfile: String = FakePayloadProfileCompatDefault,
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
    val tlsPreludeMode: String = "disabled",
    val tlsPreludeStepCount: Int = 0,
    val tlsRandRecFragmentCount: Int = DefaultTlsRandRecFragmentCount,
    val tlsRandRecMinFragmentSize: Int = DefaultTlsRandRecMinFragmentSize,
    val tlsRandRecMaxFragmentSize: Int = DefaultTlsRandRecMaxFragmentSize,
    val udpFakeCount: Int = 0,
    val quicInitialMode: String = QuicInitialModeRouteAndCache,
    val quicSupportV1: Boolean = true,
    val quicSupportV2: Boolean = true,
    val udpFakeProfile: String = FakePayloadProfileCompatDefault,
    val quicFakeProfile: String = QuicFakeProfileDisabled,
    val quicFakeHost: String = "",
    val hostAutolearnEnabled: Boolean = false,
    val hostAutolearnPenaltyTtlHours: Int = DefaultHostAutolearnPenaltyTtlHours,
    val hostAutolearnMaxHosts: Int = DefaultHostAutolearnMaxHosts,
    val hostAutolearnRuntimeEnabled: Boolean = false,
    val hostAutolearnStorePresent: Boolean = false,
    val hostAutolearnLearnedHostCount: Int = 0,
    val hostAutolearnPenalizedHostCount: Int = 0,
    val hostAutolearnLastHost: String? = null,
    val hostAutolearnLastGroup: Int? = null,
    val hostAutolearnLastAction: String? = null,
    val hostMixedCase: Boolean = false,
    val domainMixedCase: Boolean = false,
    val hostRemoveSpaces: Boolean = false,
    val onboardingComplete: Boolean = false,
    val webrtcProtectionEnabled: Boolean = false,
    val biometricEnabled: Boolean = false,
    val backupPin: String = "",
    val diagnosticsMonitorEnabled: Boolean = true,
    val diagnosticsSampleIntervalSeconds: Int = 15,
    val diagnosticsHistoryRetentionDays: Int = 14,
    val diagnosticsExportIncludeHistory: Boolean = true,
    val serviceStatus: AppStatus = AppStatus.Halted,
    val isVpn: Boolean = true,
    val selectedMode: Mode = Mode.VPN,
    val useCmdSettings: Boolean = false,
    val desyncEnabled: Boolean = true,
    val isFake: Boolean = false,
    val usesFakeTransport: Boolean = false,
    val hasHostFake: Boolean = false,
    val isOob: Boolean = false,
    val desyncHttpEnabled: Boolean = true,
    val desyncHttpsEnabled: Boolean = true,
    val desyncUdpEnabled: Boolean = false,
    val tlsRecEnabled: Boolean = false,
    val isHydrated: Boolean = true,
) {
    val hasBackupPin: Boolean
        get() = backupPin.isNotBlank()

    val isServiceRunning: Boolean
        get() = serviceStatus == AppStatus.Running

    val canForgetLearnedHosts: Boolean
        get() = !enableCmdSettings && hostAutolearnStorePresent

    val canResetFakeTlsProfile: Boolean
        get() = !enableCmdSettings && hasCustomFakeTlsProfile

    val hasCustomFakePayloadProfiles: Boolean
        get() =
            httpFakeProfile != FakePayloadProfileCompatDefault ||
                tlsFakeProfile != FakePayloadProfileCompatDefault ||
                udpFakeProfile != FakePayloadProfileCompatDefault

    val fakePayloadLibraryControlsRelevant: Boolean
        get() = desyncHttpEnabled || desyncHttpsEnabled || desyncUdpEnabled

    val showFakePayloadLibrary: Boolean
        get() = enableCmdSettings || fakePayloadLibraryControlsRelevant || hasCustomFakePayloadProfiles

    val fakeTlsControlsRelevant: Boolean
        get() = desyncHttpsEnabled && isFake

    val hasUdpFakeBurst: Boolean
        get() = udpChainSteps.any { it.count.coerceAtLeast(0) > 0 }

    val quicFakeProfileActive: Boolean
        get() = quicFakeProfile != QuicFakeProfileDisabled

    val quicFakeControlsRelevant: Boolean
        get() = desyncUdpEnabled

    val showQuicFakeHostOverride: Boolean
        get() = quicFakeProfile == QuicFakeProfileRealisticInitial

    val quicFakeUsesCustomHost: Boolean
        get() = showQuicFakeHostOverride && quicFakeHost.isNotBlank()

    val quicFakeEffectiveHost: String
        get() =
            if (showQuicFakeHostOverride) {
                quicFakeHost.ifBlank { DefaultQuicFakeHost }
            } else {
                ""
            }

    val showQuicFakeProfile: Boolean
        get() = enableCmdSettings || quicFakeControlsRelevant || quicFakeProfileActive || quicFakeHost.isNotBlank()

    val hostFakeSteps: List<TcpChainStepModel>
        get() = tcpChainSteps.filter { it.kind == TcpChainStepKind.HostFake }

    val hostFakeStepCount: Int
        get() = hostFakeSteps.size

    val primaryHostFakeStep: TcpChainStepModel?
        get() = hostFakeSteps.firstOrNull()

    val hostFakeControlsRelevant: Boolean
        get() = desyncHttpEnabled || desyncHttpsEnabled

    val showHostFakeProfile: Boolean
        get() = enableCmdSettings || hasHostFake || hostFakeControlsRelevant

    val tlsPreludeControlsRelevant: Boolean
        get() = desyncHttpsEnabled

    val showTlsPreludeProfile: Boolean
        get() = enableCmdSettings || tlsPreludeControlsRelevant || tlsPreludeStepCount > 0

    val tlsPreludeUsesRandomRecords: Boolean
        get() = tlsPreludeMode == TcpChainStepKind.TlsRandRec.wireName

    val hasStackedTlsPreludeSteps: Boolean
        get() = tlsPreludeStepCount > 1

    val hasCustomFakeTlsProfile: Boolean
        get() =
            fakeTlsUseOriginal ||
                fakeTlsRandomize ||
                fakeTlsDupSessionId ||
                fakeTlsPadEncap ||
                fakeTlsSize != 0 ||
                fakeTlsSniMode != FakeTlsSniModeFixed ||
                (fakeTlsSniMode == FakeTlsSniModeFixed && fakeSni != DefaultFakeSni)
}

data class HostPackCatalogUiState(
    val snapshot: HostPackCatalogSnapshot = HostPackCatalogSnapshot(),
    val isRefreshing: Boolean = false,
) {
    val presets: List<HostPackPreset>
        get() = snapshot.packs
}

@VisibleForTesting
internal fun AppSettings.toUiState(
    isHydrated: Boolean = true,
    serviceStatus: AppStatus = AppStatus.Halted,
    proxyTelemetry: NativeRuntimeSnapshot = NativeRuntimeSnapshot.idle(source = "proxy"),
    hostAutolearnStorePresent: Boolean = false,
): SettingsUiState {
    val normalizedMode = ripdpiMode.ifEmpty { "vpn" }
    val tcpChainSteps = effectiveTcpChainSteps()
    val udpChainSteps = effectiveUdpChainSteps()
    val tlsPreludeSteps = tcpChainSteps.filter { it.kind.isTlsPrelude }
    val primaryTcpStep = primaryTcpChainStep(tcpChainSteps)
    val tlsRecStep = tlsPreludeTcpChainStep(tcpChainSteps)
    val normalizedDesyncMethod = legacyDesyncMethod(tcpChainSteps).ifEmpty { "none" }
    val normalizedHostsMode = hostsMode.ifEmpty { "disable" }
    val isVpn = normalizedMode == "vpn"
    val useCmdSettings = enableCmdSettings
    val desyncEnabled = primaryTcpStep != null
    val isFake = tcpChainSteps.any { it.kind == TcpChainStepKind.Fake }
    val usesFakeTransport = tcpChainSteps.any { it.kind == TcpChainStepKind.Fake || it.kind == TcpChainStepKind.HostFake }
    val hasHostFake = tcpChainSteps.any { it.kind == TcpChainStepKind.HostFake }
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
        fakeSni = fakeSni.ifEmpty { DefaultFakeSni },
        fakeOffsetMarker = effectiveFakeOffsetMarker(),
        httpFakeProfile = effectiveHttpFakeProfile(),
        fakeTlsUseOriginal = fakeTlsUseOriginal,
        fakeTlsRandomize = fakeTlsRandomize,
        fakeTlsDupSessionId = fakeTlsDupSessionId,
        fakeTlsPadEncap = fakeTlsPadEncap,
        fakeTlsSize = fakeTlsSize,
        fakeTlsSniMode = effectiveFakeTlsSniMode(),
        tlsFakeProfile = effectiveTlsFakeProfile(),
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
        tlsPreludeMode = tlsRecStep?.kind?.wireName ?: "disabled",
        tlsPreludeStepCount = tlsPreludeSteps.size,
        tlsRandRecFragmentCount = tlsRecStep?.fragmentCount?.takeIf { it > 0 } ?: DefaultTlsRandRecFragmentCount,
        tlsRandRecMinFragmentSize = tlsRecStep?.minFragmentSize?.takeIf { it > 0 } ?: DefaultTlsRandRecMinFragmentSize,
        tlsRandRecMaxFragmentSize = tlsRecStep?.maxFragmentSize?.takeIf { it > 0 } ?: DefaultTlsRandRecMaxFragmentSize,
        udpFakeCount = udpChainSteps.sumOf { it.count.coerceAtLeast(0) }.takeIf { it > 0 } ?: udpFakeCount,
        quicInitialMode = effectiveQuicInitialMode(),
        quicSupportV1 = effectiveQuicSupportV1(),
        quicSupportV2 = effectiveQuicSupportV2(),
        udpFakeProfile = effectiveUdpFakeProfile(),
        quicFakeProfile = effectiveQuicFakeProfile(),
        quicFakeHost = effectiveQuicFakeHost(),
        hostAutolearnEnabled = hostAutolearnEnabled,
        hostAutolearnPenaltyTtlHours = normalizeHostAutolearnPenaltyTtlHours(hostAutolearnPenaltyTtlHours),
        hostAutolearnMaxHosts = normalizeHostAutolearnMaxHosts(hostAutolearnMaxHosts),
        hostAutolearnRuntimeEnabled = proxyTelemetry.autolearnEnabled,
        hostAutolearnStorePresent = hostAutolearnStorePresent,
        hostAutolearnLearnedHostCount = proxyTelemetry.learnedHostCount,
        hostAutolearnPenalizedHostCount = proxyTelemetry.penalizedHostCount,
        hostAutolearnLastHost = proxyTelemetry.lastAutolearnHost,
        hostAutolearnLastGroup = proxyTelemetry.lastAutolearnGroup,
        hostAutolearnLastAction = proxyTelemetry.lastAutolearnAction,
        hostMixedCase = hostMixedCase,
        domainMixedCase = domainMixedCase,
        hostRemoveSpaces = hostRemoveSpaces,
        onboardingComplete = onboardingComplete,
        webrtcProtectionEnabled = webrtcProtectionEnabled,
        biometricEnabled = biometricEnabled,
        backupPin = backupPin,
        diagnosticsMonitorEnabled = diagnosticsMonitorEnabled,
        diagnosticsSampleIntervalSeconds =
            diagnosticsSampleIntervalSeconds
                .takeIf { it > 0 }
                ?: AppSettingsSerializer.defaultValue.diagnosticsSampleIntervalSeconds,
        diagnosticsHistoryRetentionDays =
            diagnosticsHistoryRetentionDays
                .takeIf { it > 0 }
                ?: AppSettingsSerializer.defaultValue.diagnosticsHistoryRetentionDays,
        diagnosticsExportIncludeHistory = diagnosticsExportIncludeHistory,
        serviceStatus = serviceStatus,
        isVpn = isVpn,
        selectedMode = if (isVpn) Mode.VPN else Mode.Proxy,
        useCmdSettings = useCmdSettings,
        desyncEnabled = desyncEnabled,
        isFake = isFake,
        usesFakeTransport = usesFakeTransport,
        hasHostFake = hasHostFake,
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
        @param:ApplicationContext private val appContext: Context,
        private val appSettingsRepository: AppSettingsRepository,
        private val hostPackCatalogRepository: HostPackCatalogRepository,
        private val launcherIconController: LauncherIconController,
        private val serviceStateStore: ServiceStateStore,
    ) : ViewModel() {
    private val _effects = Channel<SettingsEffect>(Channel.BUFFERED)
    private val hostAutolearnStoreRefresh = MutableStateFlow(0)
    private val hostPackCatalogState = MutableStateFlow(HostPackCatalogUiState())
    val effects: Flow<SettingsEffect> = _effects.receiveAsFlow()
    val hostPackCatalog: StateFlow<HostPackCatalogUiState> = hostPackCatalogState

    val uiState: StateFlow<SettingsUiState> =
        combine(
            appSettingsRepository.settings,
            serviceStateStore.telemetry,
            hostAutolearnStoreRefresh,
        ) { settings, telemetry, _ ->
            settings.toUiState(
                serviceStatus = telemetry.status,
                proxyTelemetry = telemetry.proxyTelemetry,
                hostAutolearnStorePresent = hasHostAutolearnStore(appContext),
            )
        }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue =
                    AppSettingsSerializer.defaultValue.toUiState(
                        isHydrated = false,
                        serviceStatus = serviceStateStore.telemetry.value.status,
                        proxyTelemetry = serviceStateStore.telemetry.value.proxyTelemetry,
                        hostAutolearnStorePresent = hasHostAutolearnStore(appContext),
                    ),
            )

    init {
        viewModelScope.launch {
            hostPackCatalogState.value =
                HostPackCatalogUiState(
                    snapshot = hostPackCatalogRepository.loadSnapshot(),
                )
        }
    }

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

    fun applyHostPackPreset(
        preset: HostPackPreset,
        targetMode: String,
        applyMode: String,
    ) {
        val currentState = uiState.value
        val result =
            applyCuratedHostPack(
                currentBlacklist = currentState.hostsBlacklist,
                currentWhitelist = currentState.hostsWhitelist,
                presetHosts = preset.hosts,
                targetMode = targetMode,
                applyMode = applyMode,
            )

        viewModelScope.launch {
            appSettingsRepository.update {
                setHostsMode(result.hostsMode)
                setHostsBlacklist(result.hostsBlacklist)
                setHostsWhitelist(result.hostsWhitelist)
            }
            _effects.send(SettingsEffect.SettingChanged(key = "hostPackPreset", value = preset.id))
        }
    }

    fun refreshHostPackCatalog() {
        val previousSnapshot = hostPackCatalogState.value.snapshot
        hostPackCatalogState.update { current ->
            current.copy(isRefreshing = true)
        }

        viewModelScope.launch {
            val effect =
                runCatching {
                    hostPackCatalogRepository.refreshSnapshot()
                }.fold(
                    onSuccess = { snapshot ->
                        hostPackCatalogState.value =
                            HostPackCatalogUiState(
                                snapshot = snapshot,
                                isRefreshing = false,
                            )
                        SettingsEffect.Notice(
                            title = "Host packs refreshed",
                            message = "RIPDPI downloaded geosite.dat, verified its SHA-256 checksum, and updated the on-device curated host packs.",
                            tone = SettingsNoticeTone.Info,
                        )
                    },
                    onFailure = { error ->
                        hostPackCatalogState.value =
                            HostPackCatalogUiState(
                                snapshot = previousSnapshot,
                                isRefreshing = false,
                            )
                        when (error) {
                            is HostPackChecksumMismatchException,
                            is HostPackChecksumFormatException,
                                ->
                                SettingsEffect.Notice(
                                    title = "Host pack verification failed",
                                    message = "The downloaded geosite.dat checksum did not match the published SHA-256 digest. RIPDPI kept the current curated host packs.",
                                    tone = SettingsNoticeTone.Error,
                                )

                            is HostPackCatalogParseException,
                            is HostPackCatalogBuildException,
                                ->
                                SettingsEffect.Notice(
                                    title = "Host pack refresh failed",
                                    message = "RIPDPI verified the download, but the remote geosite.dat did not produce a complete YouTube, Telegram, and Discord catalog. The current packs remain in use.",
                                    tone = SettingsNoticeTone.Error,
                                )

                            else ->
                                SettingsEffect.Notice(
                                    title = "Couldn’t refresh host packs",
                                    message = "RIPDPI could not download the latest geosite.dat or checksum file. The current curated host packs remain in use.",
                                    tone = SettingsNoticeTone.Warning,
                                )
                        }
                    },
                )
            _effects.send(effect)
        }
    }

    fun forgetLearnedHosts() {
        viewModelScope.launch {
            val cleared = clearHostAutolearnStore(appContext)
            hostAutolearnStoreRefresh.update { it + 1 }
            val effect =
                when {
                    !cleared ->
                        SettingsEffect.Notice(
                            title = "Couldn’t clear learned hosts",
                            message = "RIPDPI could not delete the stored host learning file on this device.",
                            tone = SettingsNoticeTone.Error,
                        )

                    serviceStateStore.status.value.first == AppStatus.Running ->
                        SettingsEffect.Notice(
                            title = "Learned hosts cleared for next start",
                            message = "The stored host learning file was removed. The running proxy keeps its in-memory routes until RIPDPI restarts.",
                            tone = SettingsNoticeTone.Info,
                        )

                    else ->
                        SettingsEffect.Notice(
                            title = "Learned hosts cleared",
                            message = "Stored host learning was removed and the next RIPDPI start will rebuild it from scratch.",
                            tone = SettingsNoticeTone.Info,
                        )
                }
            _effects.send(effect)
        }
    }

    fun resetFakeTlsProfile() {
        viewModelScope.launch {
            appSettingsRepository.update {
                setFakeTlsUseOriginal(false)
                setFakeTlsRandomize(false)
                setFakeTlsDupSessionId(false)
                setFakeTlsPadEncap(false)
                setFakeTlsSize(0)
                setFakeTlsSniMode(FakeTlsSniModeFixed)
                setFakeSni(DefaultFakeSni)
            }
            val effect =
                if (serviceStateStore.status.value.first == AppStatus.Running) {
                    SettingsEffect.Notice(
                        title = "Fake TLS profile reset for next start",
                        message = "The profile is back to the default fake ClientHello. Restart RIPDPI to apply it to the active session.",
                        tone = SettingsNoticeTone.Info,
                    )
                } else {
                    SettingsEffect.Notice(
                        title = "Fake TLS profile reset",
                        message = "RIPDPI will use the default fake ClientHello, fixed SNI, and input-sized payload on the next start.",
                        tone = SettingsNoticeTone.Info,
                    )
                }
            _effects.send(effect)
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
