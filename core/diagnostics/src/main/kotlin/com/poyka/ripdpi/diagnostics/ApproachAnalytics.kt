package com.poyka.ripdpi.diagnostics

import com.poyka.ripdpi.core.RipDpiProxyUIPreferences
import com.poyka.ripdpi.core.decodeRipDpiProxyUiPreferences
import com.poyka.ripdpi.data.DefaultAdaptiveFakeTtlDelta
import com.poyka.ripdpi.data.DefaultFakeOffsetMarker
import com.poyka.ripdpi.data.FakePayloadProfileCompatDefault
import com.poyka.ripdpi.data.FakeTlsSniModeFixed
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.QuicFakeProfileDisabled
import com.poyka.ripdpi.data.QuicFakeProfileRealisticInitial
import com.poyka.ripdpi.data.StrategyLaneFamilies
import com.poyka.ripdpi.data.TcpChainStepKind
import com.poyka.ripdpi.data.TcpChainStepModel
import com.poyka.ripdpi.data.UdpChainStepModel
import com.poyka.ripdpi.data.activeHttpParserEvasions
import com.poyka.ripdpi.data.deriveStrategyLaneFamilies
import com.poyka.ripdpi.data.effectiveAdaptiveFakeTtlDelta
import com.poyka.ripdpi.data.effectiveAdaptiveFakeTtlFallback
import com.poyka.ripdpi.data.effectiveAdaptiveFakeTtlMax
import com.poyka.ripdpi.data.effectiveAdaptiveFakeTtlMin
import com.poyka.ripdpi.data.effectiveFakeOffsetMarker
import com.poyka.ripdpi.data.effectiveFakeTlsSniMode
import com.poyka.ripdpi.data.effectiveGroupActivationFilter
import com.poyka.ripdpi.data.effectiveHttpFakeProfile
import com.poyka.ripdpi.data.effectiveQuicFakeHost
import com.poyka.ripdpi.data.effectiveQuicFakeProfile
import com.poyka.ripdpi.data.effectiveTcpChainSteps
import com.poyka.ripdpi.data.effectiveTlsFakeProfile
import com.poyka.ripdpi.data.effectiveUdpChainSteps
import com.poyka.ripdpi.data.effectiveUdpFakeProfile
import com.poyka.ripdpi.data.formatChainSummary
import com.poyka.ripdpi.data.formatNumericRange
import com.poyka.ripdpi.data.hasCustomFakeTlsProfile
import com.poyka.ripdpi.data.primaryDesyncMethod
import com.poyka.ripdpi.data.primaryTcpChainStep
import com.poyka.ripdpi.data.strategyLaneFamilyLabel
import com.poyka.ripdpi.data.tlsPreludeTcpChainStep
import com.poyka.ripdpi.data.usesSeqOverlapFakeProfile
import com.poyka.ripdpi.proto.AppSettings
import com.poyka.ripdpi.utility.shellSplit
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.security.MessageDigest

/** Fallback group labels injected by `ripdpi_default` runtime preset. */
private val DefaultFallbackGroupFamilies = listOf("tlsrec_disorder", "disorder_host", "split_host")

@Serializable
data class BypassApproachId(
    val kind: BypassApproachKind,
    val value: String,
)

@Serializable
enum class BypassApproachKind {
    Profile,
    Strategy,
}

@Serializable
data class BypassStrategySignature(
    val mode: String,
    val configSource: String,
    val hostAutolearn: String,
    val desyncMethod: String,
    val chainSummary: String,
    val tcpStrategyFamily: String? = null,
    val quicStrategyFamily: String? = null,
    val dnsStrategyFamily: String? = null,
    val dnsStrategyLabel: String? = null,
    val protocolToggles: List<String>,
    val httpParserEvasions: List<String> = emptyList(),
    val tlsRecordSplitEnabled: Boolean,
    val tlsRecordMarker: String? = null,
    val splitMarker: String? = null,
    val activationRound: String? = null,
    val activationPayloadSize: String? = null,
    val activationStreamBytes: String? = null,
    val fakeTtlMode: String? = null,
    val adaptiveFakeTtlWindow: String? = null,
    val adaptiveFakeTtlFallback: Int? = null,
    val adaptiveFakeTtlBias: Int? = null,
    val fakeSniMode: String? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val fakeSniValue: String? = null,
    val fakeTlsBaseMode: String? = null,
    val fakeTlsMods: List<String> = emptyList(),
    val fakeTlsSize: Int? = null,
    val httpFakeProfile: String? = null,
    val tlsFakeProfile: String? = null,
    val udpFakeProfile: String? = null,
    val fakePayloadSource: String? = null,
    val quicFakeProfile: String? = null,
    val quicFakeHost: String? = null,
    val fakeOffsetMarker: String? = null,
    val routeGroup: String? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val fallbackGroupFamilies: List<String> = emptyList(),
)

@Serializable
data class BypassRuntimeHealthSummary(
    val totalErrors: Long = 0,
    val routeChanges: Long = 0,
    val restartCount: Int = 0,
    val lastEndedReason: String? = null,
)

@Serializable
data class BypassOutcomeBreakdown(
    val probeType: String,
    val successCount: Int,
    val warningCount: Int,
    val failureCount: Int,
    val dominantFailureOutcome: String? = null,
)

@Serializable
data class BypassApproachSummary(
    val approachId: BypassApproachId,
    val displayName: String,
    val secondaryLabel: String,
    val verificationState: String,
    val validatedScanCount: Int,
    val validatedSuccessCount: Int,
    val validatedSuccessRate: Float?,
    val lastValidatedResult: String?,
    val usageCount: Int,
    val totalRuntimeDurationMs: Long,
    val recentRuntimeHealth: BypassRuntimeHealthSummary,
    val lastUsedAt: Long?,
    val topFailureOutcomes: List<String> = emptyList(),
    val outcomeBreakdown: List<BypassOutcomeBreakdown> = emptyList(),
)

@Serializable
data class BypassApproachDetail(
    val summary: BypassApproachSummary,
    val strategySignature: BypassStrategySignature? = null,
    val recentValidatedSessions: List<DiagnosticScanSession> = emptyList(),
    val recentUsageSessions: List<DiagnosticConnectionSession> = emptyList(),
    val commonProbeFailures: List<String> = emptyList(),
    val recentFailureNotes: List<String> = emptyList(),
)

private val strategyJson = Json { encodeDefaults = true }

private fun hasCommandLineRawFakePayload(args: String): Boolean {
    val tokens = shellSplit(args)
    for ((index, token) in tokens.withIndex()) {
        if ((token == "-l" || token == "--fake-data") && index + 1 < tokens.size) {
            return true
        }
    }
    return false
}

fun deriveBypassStrategySignature(
    settings: AppSettings,
    routeGroup: String?,
    modeOverride: Mode? = null,
): BypassStrategySignature {
    val mode = (modeOverride ?: Mode.fromString(settings.ripdpiMode.ifEmpty { "vpn" })).name
    val tcpSteps = settings.effectiveTcpChainSteps()
    val udpSteps = settings.effectiveUdpChainSteps()
    val primaryTcpStep = primaryTcpChainStep(tcpSteps)
    val tlsRecStep = tlsPreludeTcpChainStep(tcpSteps)
    val fakeTlsProfileActive = resolveSettingsFakeTlsProfileActive(settings, tcpSteps)
    val quicFakeProfile = settings.effectiveQuicFakeProfile()
    val quicFakeProfileActive =
        !settings.enableCmdSettings && settings.desyncUdp && quicFakeProfile != QuicFakeProfileDisabled
    val adaptiveFakeTtlActive = !settings.enableCmdSettings && settings.adaptiveFakeTtlEnabled
    val adaptiveFakeTtlDelta = settings.effectiveAdaptiveFakeTtlDelta()
    val laneFamilies = settings.deriveStrategyLaneFamilies()
    val activationFilter = settings.effectiveGroupActivationFilter()
    val activationFiltersActive = !settings.enableCmdSettings

    return BypassStrategySignature(
        mode = mode,
        configSource = if (settings.enableCmdSettings) "command_line" else "ui",
        hostAutolearn = resolveSettingsHostAutolearn(settings),
        desyncMethod = primaryDesyncMethod(tcpSteps).ifEmpty { "none" },
        chainSummary = formatChainSummary(tcpSteps, udpSteps),
        tcpStrategyFamily = laneFamilies.tcpStrategyFamily,
        quicStrategyFamily = laneFamilies.quicStrategyFamily,
        dnsStrategyFamily = laneFamilies.dnsStrategyFamily,
        dnsStrategyLabel = laneFamilies.dnsStrategyLabel,
        protocolToggles = resolveSettingsProtocols(settings),
        httpParserEvasions = settings.activeHttpParserEvasions().takeIf { !settings.enableCmdSettings }.orEmpty(),
        tlsRecordSplitEnabled = tlsRecStep != null,
        tlsRecordMarker = tlsRecStep?.marker,
        splitMarker = primaryTcpStep?.marker,
        activationRound = formatNumericRange(activationFilter.round).takeIf { activationFiltersActive },
        activationPayloadSize = formatNumericRange(activationFilter.payloadSize).takeIf { activationFiltersActive },
        activationStreamBytes = formatNumericRange(activationFilter.streamBytes).takeIf { activationFiltersActive },
        fakeTtlMode = resolveSettingsFakeTtlMode(settings, adaptiveFakeTtlDelta),
        adaptiveFakeTtlWindow = resolveSettingsAdaptiveTtlWindow(settings, adaptiveFakeTtlActive),
        adaptiveFakeTtlFallback = settings.effectiveAdaptiveFakeTtlFallback().takeIf { adaptiveFakeTtlActive },
        adaptiveFakeTtlBias =
            adaptiveFakeTtlDelta.takeIf { adaptiveFakeTtlActive && it != DefaultAdaptiveFakeTtlDelta },
        fakeSniMode = settings.effectiveFakeTlsSniMode().takeIf { fakeTlsProfileActive },
        fakeSniValue = resolveSettingsFakeSniValue(settings, fakeTlsProfileActive),
        fakeTlsBaseMode = resolveSettingsFakeTlsBaseMode(settings, fakeTlsProfileActive),
        fakeTlsMods = resolveSettingsFakeTlsMods(settings).takeIf { fakeTlsProfileActive }.orEmpty(),
        fakeTlsSize = settings.fakeTlsSize.takeIf { fakeTlsProfileActive && it != 0 },
        httpFakeProfile = resolveSettingsHttpFakeProfile(settings),
        tlsFakeProfile = resolveSettingsTlsFakeProfile(settings),
        udpFakeProfile = resolveSettingsUdpFakeProfile(settings),
        fakePayloadSource =
            "custom_raw".takeIf { settings.enableCmdSettings && hasCommandLineRawFakePayload(settings.cmdArgs) },
        quicFakeProfile = quicFakeProfile.takeIf { quicFakeProfileActive },
        quicFakeHost = resolveSettingsQuicFakeHost(settings, quicFakeProfileActive, quicFakeProfile),
        fakeOffsetMarker = settings.effectiveFakeOffsetMarker().takeUnless { it == DefaultFakeOffsetMarker },
        routeGroup = routeGroup?.takeUnless { it.isBlank() || it == "unknown" },
        fallbackGroupFamilies = if (!settings.enableCmdSettings) DefaultFallbackGroupFamilies else emptyList(),
    )
}

private fun resolveSettingsFakeTlsProfileActive(
    settings: AppSettings,
    tcpSteps: List<TcpChainStepModel>,
): Boolean {
    val hasFakeStep =
        tcpSteps.any {
            it.kind == TcpChainStepKind.Fake || it.kind == TcpChainStepKind.FakeSplit ||
                it.kind == TcpChainStepKind.FakeDisorder
        }
    val usesSeqOverlap = tcpSteps.any { it.usesSeqOverlapFakeProfile() }
    return (hasFakeStep || usesSeqOverlap) && settings.desyncHttps && settings.hasCustomFakeTlsProfile()
}

private fun resolveSettingsHostAutolearn(settings: AppSettings): String =
    when {
        settings.enableCmdSettings -> "command_line"
        settings.hostAutolearnEnabled -> "enabled"
        else -> "disabled"
    }

private fun resolveSettingsAdaptiveTtlWindow(
    settings: AppSettings,
    adaptiveFakeTtlActive: Boolean,
): String? =
    if (adaptiveFakeTtlActive) {
        "${settings.effectiveAdaptiveFakeTtlMin()}-${settings.effectiveAdaptiveFakeTtlMax()}"
    } else {
        null
    }

private fun resolveSettingsFakeSniValue(
    settings: AppSettings,
    fakeTlsProfileActive: Boolean,
): String? =
    settings.fakeSni
        .ifBlank { null }
        ?.takeIf { fakeTlsProfileActive && settings.effectiveFakeTlsSniMode() == FakeTlsSniModeFixed }

private fun resolveSettingsFakeTlsBaseMode(
    settings: AppSettings,
    fakeTlsProfileActive: Boolean,
): String? =
    if (fakeTlsProfileActive) {
        if (settings.fakeTlsUseOriginal) "original" else "default"
    } else {
        null
    }

private fun resolveSettingsHttpFakeProfile(settings: AppSettings): String? =
    settings
        .effectiveHttpFakeProfile()
        .takeIf { !settings.enableCmdSettings && it != FakePayloadProfileCompatDefault }

private fun resolveSettingsTlsFakeProfile(settings: AppSettings): String? =
    settings
        .effectiveTlsFakeProfile()
        .takeIf { !settings.enableCmdSettings && it != FakePayloadProfileCompatDefault }

private fun resolveSettingsUdpFakeProfile(settings: AppSettings): String? =
    settings
        .effectiveUdpFakeProfile()
        .takeIf { !settings.enableCmdSettings && it != FakePayloadProfileCompatDefault }

private fun resolveSettingsQuicFakeHost(
    settings: AppSettings,
    quicFakeProfileActive: Boolean,
    quicFakeProfile: String,
): String? =
    settings.effectiveQuicFakeHost().takeIf {
        quicFakeProfileActive && quicFakeProfile == QuicFakeProfileRealisticInitial && it.isNotBlank()
    }

private fun resolveSettingsProtocols(settings: AppSettings): List<String> =
    buildList {
        if (settings.desyncHttp) add("HTTP")
        if (settings.desyncHttps) add("HTTPS")
        if (settings.desyncUdp) add("UDP")
    }.ifEmpty { listOf("NONE") }

private fun resolveSettingsFakeTtlMode(
    settings: AppSettings,
    adaptiveFakeTtlDelta: Int,
): String? =
    if (settings.enableCmdSettings) {
        null
    } else if (settings.adaptiveFakeTtlEnabled) {
        if (adaptiveFakeTtlDelta == DefaultAdaptiveFakeTtlDelta) "adaptive" else "adaptive_custom"
    } else {
        "fixed"
    }

private fun resolveSettingsFakeTlsMods(settings: AppSettings): List<String> =
    buildList {
        if (settings.fakeTlsRandomize) add("rand")
        if (settings.fakeTlsDupSessionId) add("dupsid")
        if (settings.fakeTlsPadEncap) add("padencap")
    }

fun deriveBypassStrategySignature(
    configJson: String,
    routeGroup: String?,
    modeOverride: Mode,
): BypassStrategySignature? =
    decodeRipDpiProxyUiPreferences(configJson)
        ?.let { preferences ->
            deriveBypassStrategySignature(
                preferences = preferences,
                routeGroup = routeGroup,
                modeOverride = modeOverride,
            )
        }

fun deriveBypassStrategySignature(
    preferences: RipDpiProxyUIPreferences,
    routeGroup: String?,
    modeOverride: Mode,
): BypassStrategySignature {
    val tcpSteps = preferences.chains.tcpSteps
    val udpSteps = preferences.chains.udpSteps
    val primaryTcpStep = primaryTcpChainStep(tcpSteps)
    val tlsRecStep = tlsPreludeTcpChainStep(tcpSteps)
    val fakeTlsProfileActive = resolvePreferencesFakeTlsProfileActive(preferences, tcpSteps)
    val quicFakeProfileActive =
        preferences.protocols.desyncUdp && preferences.quic.fakeProfile != QuicFakeProfileDisabled
    val adaptiveFakeTtlActive = preferences.fakePackets.adaptiveFakeTtlEnabled
    val adaptiveFakeTtlDelta = preferences.fakePackets.adaptiveFakeTtlDelta
    val laneFamilies = resolvePreferencesLaneFamilies(preferences, tcpSteps, udpSteps)

    return BypassStrategySignature(
        mode = modeOverride.name,
        configSource = "ui",
        hostAutolearn = if (preferences.hostAutolearn.enabled) "enabled" else "disabled",
        desyncMethod = primaryDesyncMethod(tcpSteps).ifEmpty { "none" },
        chainSummary = preferences.chainSummary,
        tcpStrategyFamily = laneFamilies.tcpStrategyFamily,
        quicStrategyFamily = laneFamilies.quicStrategyFamily,
        dnsStrategyFamily = laneFamilies.dnsStrategyFamily,
        dnsStrategyLabel = laneFamilies.dnsStrategyLabel,
        protocolToggles = resolvePreferencesProtocols(preferences),
        httpParserEvasions = resolvePreferencesHttpParserEvasions(preferences),
        tlsRecordSplitEnabled = tlsRecStep != null,
        tlsRecordMarker = tlsRecStep?.marker,
        splitMarker = primaryTcpStep?.marker,
        activationRound = formatNumericRange(preferences.chains.groupActivationFilter.round),
        activationPayloadSize = formatNumericRange(preferences.chains.groupActivationFilter.payloadSize),
        activationStreamBytes = formatNumericRange(preferences.chains.groupActivationFilter.streamBytes),
        fakeTtlMode = resolvePreferencesFakeTtlMode(preferences, adaptiveFakeTtlDelta),
        adaptiveFakeTtlWindow = resolvePreferencesAdaptiveTtlWindow(preferences, adaptiveFakeTtlActive),
        adaptiveFakeTtlFallback = preferences.fakePackets.adaptiveFakeTtlFallback.takeIf { adaptiveFakeTtlActive },
        adaptiveFakeTtlBias =
            adaptiveFakeTtlDelta.takeIf { adaptiveFakeTtlActive && it != DefaultAdaptiveFakeTtlDelta },
        fakeSniMode = preferences.fakePackets.fakeTlsSniMode.takeIf { fakeTlsProfileActive },
        fakeSniValue = resolvePreferencesFakeSniValue(preferences, fakeTlsProfileActive),
        fakeTlsBaseMode = resolvePreferencesFakeTlsBaseMode(preferences, fakeTlsProfileActive),
        fakeTlsMods = resolvePreferencesFakeTlsMods(preferences).takeIf { fakeTlsProfileActive }.orEmpty(),
        fakeTlsSize = preferences.fakePackets.fakeTlsSize.takeIf { fakeTlsProfileActive && it != 0 },
        httpFakeProfile = preferences.fakePackets.httpFakeProfile.takeIf { it != FakePayloadProfileCompatDefault },
        tlsFakeProfile = preferences.fakePackets.tlsFakeProfile.takeIf { it != FakePayloadProfileCompatDefault },
        udpFakeProfile = preferences.fakePackets.udpFakeProfile.takeIf { it != FakePayloadProfileCompatDefault },
        quicFakeProfile = preferences.quic.fakeProfile.takeIf { quicFakeProfileActive },
        quicFakeHost = resolvePreferencesQuicFakeHost(preferences, quicFakeProfileActive),
        fakeOffsetMarker = preferences.fakePackets.fakeOffsetMarker.takeUnless { it == DefaultFakeOffsetMarker },
        routeGroup = routeGroup?.takeUnless { it.isBlank() || it == "unknown" },
        fallbackGroupFamilies = DefaultFallbackGroupFamilies,
    )
}

private fun resolvePreferencesHasCustomFakeTlsProfile(preferences: RipDpiProxyUIPreferences): Boolean =
    preferences.fakePackets.fakeTlsUseOriginal ||
        preferences.fakePackets.fakeTlsRandomize ||
        preferences.fakePackets.fakeTlsDupSessionId ||
        preferences.fakePackets.fakeTlsPadEncap ||
        preferences.fakePackets.fakeTlsSize != 0 ||
        preferences.fakePackets.fakeTlsSniMode != FakeTlsSniModeFixed ||
        (
            preferences.fakePackets.fakeTlsSniMode == FakeTlsSniModeFixed &&
                preferences.fakePackets.fakeSni.isNotBlank() &&
                preferences.fakePackets.fakeSni != com.poyka.ripdpi.data.DefaultFakeSni
        )

private fun resolvePreferencesFakeTtlMode(
    preferences: RipDpiProxyUIPreferences,
    adaptiveFakeTtlDelta: Int,
): String =
    if (preferences.fakePackets.adaptiveFakeTtlEnabled) {
        if (adaptiveFakeTtlDelta == DefaultAdaptiveFakeTtlDelta) "adaptive" else "adaptive_custom"
    } else {
        "fixed"
    }

private fun resolvePreferencesFakeTlsMods(preferences: RipDpiProxyUIPreferences): List<String> =
    buildList {
        if (preferences.fakePackets.fakeTlsRandomize) add("rand")
        if (preferences.fakePackets.fakeTlsDupSessionId) add("dupsid")
        if (preferences.fakePackets.fakeTlsPadEncap) add("padencap")
    }

private fun resolvePreferencesFakeTlsProfileActive(
    preferences: RipDpiProxyUIPreferences,
    tcpSteps: List<TcpChainStepModel>,
): Boolean {
    val hasFakeStep =
        tcpSteps.any {
            it.kind == TcpChainStepKind.Fake || it.kind == TcpChainStepKind.FakeSplit ||
                it.kind == TcpChainStepKind.FakeDisorder
        }
    return hasFakeStep && preferences.protocols.desyncHttps && resolvePreferencesHasCustomFakeTlsProfile(preferences)
}

private fun resolvePreferencesLaneFamilies(
    preferences: RipDpiProxyUIPreferences,
    tcpSteps: List<TcpChainStepModel>,
    udpSteps: List<UdpChainStepModel>,
): StrategyLaneFamilies =
    deriveStrategyLaneFamilies(
        tcpSteps = tcpSteps,
        udpSteps = udpSteps,
        desyncUdp = preferences.protocols.desyncUdp,
        quicInitialMode = preferences.quic.initialMode,
        quicFakeProfile = preferences.quic.fakeProfile,
    )

private fun resolvePreferencesProtocols(preferences: RipDpiProxyUIPreferences): List<String> =
    buildList {
        if (preferences.protocols.desyncHttp) add("HTTP")
        if (preferences.protocols.desyncHttps) add("HTTPS")
        if (preferences.protocols.desyncUdp) add("UDP")
    }.ifEmpty { listOf("NONE") }

private fun resolvePreferencesHttpParserEvasions(preferences: RipDpiProxyUIPreferences): List<String> =
    activeHttpParserEvasions(
        hostMixedCase = preferences.parserEvasions.hostMixedCase,
        domainMixedCase = preferences.parserEvasions.domainMixedCase,
        hostRemoveSpaces = preferences.parserEvasions.hostRemoveSpaces,
        httpMethodSpace = preferences.parserEvasions.httpMethodSpace,
        httpMethodEol = preferences.parserEvasions.httpMethodEol,
        httpHostPad = preferences.parserEvasions.httpHostPad,
        httpUnixEol = preferences.parserEvasions.httpUnixEol,
    )

private fun resolvePreferencesAdaptiveTtlWindow(
    preferences: RipDpiProxyUIPreferences,
    adaptiveFakeTtlActive: Boolean,
): String? =
    if (adaptiveFakeTtlActive) {
        "${preferences.fakePackets.adaptiveFakeTtlMin}-${preferences.fakePackets.adaptiveFakeTtlMax}"
    } else {
        null
    }

private fun resolvePreferencesFakeSniValue(
    preferences: RipDpiProxyUIPreferences,
    fakeTlsProfileActive: Boolean,
): String? =
    preferences.fakePackets.fakeSni.takeIf {
        fakeTlsProfileActive && preferences.fakePackets.fakeTlsSniMode == FakeTlsSniModeFixed
    }

private fun resolvePreferencesFakeTlsBaseMode(
    preferences: RipDpiProxyUIPreferences,
    fakeTlsProfileActive: Boolean,
): String? =
    if (fakeTlsProfileActive) {
        if (preferences.fakePackets.fakeTlsUseOriginal) "original" else "default"
    } else {
        null
    }

private fun resolvePreferencesQuicFakeHost(
    preferences: RipDpiProxyUIPreferences,
    quicFakeProfileActive: Boolean,
): String? =
    preferences.quic.fakeHost.takeIf {
        quicFakeProfileActive && preferences.quic.fakeProfile == QuicFakeProfileRealisticInitial &&
            it.isNotBlank()
    }

private const val StableIdHashPrefixBytes = 6

fun BypassStrategySignature.stableId(): String {
    val encoded = strategyJson.encodeToString(BypassStrategySignature.serializer(), this)
    val hash = MessageDigest.getInstance("SHA-256").digest(encoded.toByteArray())
    val suffix = hash.take(StableIdHashPrefixBytes).joinToString("") { "%02x".format(it) }
    return "strategy-$suffix"
}

fun BypassStrategySignature.displayLabel(): String =
    buildString {
        append(mode)
        append(" ")
        append(chainSummary)
        append(" · ")
        append(protocolToggles.joinToString("/"))
        append(" · ")
        append(
            when (hostAutolearn) {
                "command_line" -> "Autolearn CLI"
                "enabled" -> "Autolearn on"
                else -> "Autolearn off"
            },
        )
        val lanes =
            listOfNotNull(
                tcpStrategyFamily?.let { "TCP ${strategyLaneFamilyLabel(it)}" },
                quicStrategyFamily?.let { "QUIC ${strategyLaneFamilyLabel(it)}" },
                dnsStrategyLabel?.let { "DNS $it" },
            )
        if (lanes.isNotEmpty()) {
            append(" · ")
            append(lanes.joinToString(" / "))
        }
        if (tlsRecordSplitEnabled) {
            append(" · TLS split")
        }
        if (routeGroup != null) {
            append(" · Route ")
            append(routeGroup)
        }
    }
