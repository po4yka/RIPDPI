package com.poyka.ripdpi.diagnostics

import com.poyka.ripdpi.core.RipDpiProxyUIPreferences
import com.poyka.ripdpi.data.DefaultAdaptiveFakeTtlDelta
import com.poyka.ripdpi.data.DefaultFakeOffsetMarker
import com.poyka.ripdpi.data.FakePayloadProfileCompatDefault
import com.poyka.ripdpi.data.FakeTlsSniModeFixed
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.QuicFakeProfileDisabled
import com.poyka.ripdpi.data.QuicFakeProfileRealisticInitial
import com.poyka.ripdpi.data.TcpChainStepKind
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
import com.poyka.ripdpi.proto.AppSettings
import com.poyka.ripdpi.utility.shellSplit
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.security.MessageDigest

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
    val protocols =
        buildList {
            if (settings.desyncHttp) add("HTTP")
            if (settings.desyncHttps) add("HTTPS")
            if (settings.desyncUdp) add("UDP")
        }.ifEmpty {
            listOf("NONE")
        }
    val desyncMethod = primaryDesyncMethod(tcpSteps).ifEmpty { "none" }
    val hasFakeStep =
        tcpSteps.any { step ->
            step.kind == TcpChainStepKind.Fake ||
                step.kind == TcpChainStepKind.FakeSplit ||
                step.kind == TcpChainStepKind.FakeDisorder
        }
    val fakeTlsProfileActive = hasFakeStep && settings.desyncHttps && settings.hasCustomFakeTlsProfile()
    val commandLineRawFakePayload = settings.enableCmdSettings && hasCommandLineRawFakePayload(settings.cmdArgs)
    val quicFakeProfile = settings.effectiveQuicFakeProfile()
    val quicFakeProfileActive =
        !settings.enableCmdSettings && settings.desyncUdp && quicFakeProfile != QuicFakeProfileDisabled
    val httpFakeProfile = settings.effectiveHttpFakeProfile()
    val tlsFakeProfile = settings.effectiveTlsFakeProfile()
    val udpFakeProfile = settings.effectiveUdpFakeProfile()
    val fakeTlsSniMode = settings.effectiveFakeTlsSniMode()
    val fakeTlsMods =
        buildList {
            if (settings.fakeTlsRandomize) add("rand")
            if (settings.fakeTlsDupSessionId) add("dupsid")
            if (settings.fakeTlsPadEncap) add("padencap")
        }
    val activationFilter = settings.effectiveGroupActivationFilter()
    val activationFiltersActive = !settings.enableCmdSettings
    val adaptiveFakeTtlActive = !settings.enableCmdSettings && settings.adaptiveFakeTtlEnabled
    val adaptiveFakeTtlDelta = settings.effectiveAdaptiveFakeTtlDelta()
    val httpParserEvasions =
        settings
            .activeHttpParserEvasions()
            .takeIf { !settings.enableCmdSettings }
            .orEmpty()
    val laneFamilies = settings.deriveStrategyLaneFamilies()

    return BypassStrategySignature(
        mode = mode,
        configSource = if (settings.enableCmdSettings) "command_line" else "ui",
        hostAutolearn =
            when {
                settings.enableCmdSettings -> "command_line"
                settings.hostAutolearnEnabled -> "enabled"
                else -> "disabled"
            },
        desyncMethod = desyncMethod,
        chainSummary = formatChainSummary(tcpSteps, udpSteps),
        tcpStrategyFamily = laneFamilies.tcpStrategyFamily,
        quicStrategyFamily = laneFamilies.quicStrategyFamily,
        dnsStrategyFamily = laneFamilies.dnsStrategyFamily,
        dnsStrategyLabel = laneFamilies.dnsStrategyLabel,
        protocolToggles = protocols,
        httpParserEvasions = httpParserEvasions,
        tlsRecordSplitEnabled = tlsRecStep != null,
        tlsRecordMarker = tlsRecStep?.marker,
        splitMarker = primaryTcpStep?.marker,
        activationRound = formatNumericRange(activationFilter.round).takeIf { activationFiltersActive },
        activationPayloadSize = formatNumericRange(activationFilter.payloadSize).takeIf { activationFiltersActive },
        activationStreamBytes = formatNumericRange(activationFilter.streamBytes).takeIf { activationFiltersActive },
        fakeTtlMode =
            if (settings.enableCmdSettings) {
                null
            } else if (settings.adaptiveFakeTtlEnabled) {
                if (adaptiveFakeTtlDelta == DefaultAdaptiveFakeTtlDelta) "adaptive" else "adaptive_custom"
            } else {
                "fixed"
            },
        adaptiveFakeTtlWindow =
            if (adaptiveFakeTtlActive) {
                "${settings.effectiveAdaptiveFakeTtlMin()}-${settings.effectiveAdaptiveFakeTtlMax()}"
            } else {
                null
            },
        adaptiveFakeTtlFallback = settings.effectiveAdaptiveFakeTtlFallback().takeIf { adaptiveFakeTtlActive },
        adaptiveFakeTtlBias =
            adaptiveFakeTtlDelta.takeIf {
                adaptiveFakeTtlActive && it != DefaultAdaptiveFakeTtlDelta
            },
        fakeSniMode = fakeTlsSniMode.takeIf { fakeTlsProfileActive },
        fakeSniValue =
            settings.fakeSni.ifBlank { null }?.takeIf {
                fakeTlsProfileActive &&
                    fakeTlsSniMode == FakeTlsSniModeFixed
            },
        fakeTlsBaseMode =
            if (fakeTlsProfileActive) {
                if (settings.fakeTlsUseOriginal) {
                    "original"
                } else {
                    "default"
                }
            } else {
                null
            },
        fakeTlsMods = fakeTlsMods.takeIf { fakeTlsProfileActive }.orEmpty(),
        fakeTlsSize = settings.fakeTlsSize.takeIf { fakeTlsProfileActive && it != 0 },
        httpFakeProfile =
            httpFakeProfile.takeIf {
                !settings.enableCmdSettings && it != FakePayloadProfileCompatDefault
            },
        tlsFakeProfile =
            tlsFakeProfile.takeIf {
                !settings.enableCmdSettings && it != FakePayloadProfileCompatDefault
            },
        udpFakeProfile =
            udpFakeProfile.takeIf {
                !settings.enableCmdSettings && it != FakePayloadProfileCompatDefault
            },
        fakePayloadSource = "custom_raw".takeIf { commandLineRawFakePayload },
        quicFakeProfile = quicFakeProfile.takeIf { quicFakeProfileActive },
        quicFakeHost =
            settings
                .effectiveQuicFakeHost()
                .takeIf {
                    quicFakeProfileActive && quicFakeProfile == QuicFakeProfileRealisticInitial && it.isNotBlank()
                },
        fakeOffsetMarker =
            settings
                .effectiveFakeOffsetMarker()
                .takeUnless { it == DefaultFakeOffsetMarker },
        routeGroup = routeGroup?.takeUnless { it.isBlank() || it == "unknown" },
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
    val protocols =
        buildList {
            if (preferences.protocols.desyncHttp) add("HTTP")
            if (preferences.protocols.desyncHttps) add("HTTPS")
            if (preferences.protocols.desyncUdp) add("UDP")
        }.ifEmpty {
            listOf("NONE")
        }
    val hasFakeStep =
        tcpSteps.any { step ->
            step.kind == TcpChainStepKind.Fake ||
                step.kind == TcpChainStepKind.FakeSplit ||
                step.kind == TcpChainStepKind.FakeDisorder
        }
    val hasCustomFakeTlsProfile =
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
    val fakeTlsProfileActive = hasFakeStep && preferences.protocols.desyncHttps && hasCustomFakeTlsProfile
    val quicFakeProfileActive =
        preferences.protocols.desyncUdp && preferences.quic.fakeProfile != QuicFakeProfileDisabled
    val adaptiveFakeTtlActive = preferences.fakePackets.adaptiveFakeTtlEnabled
    val fakeTlsMods =
        buildList {
            if (preferences.fakePackets.fakeTlsRandomize) add("rand")
            if (preferences.fakePackets.fakeTlsDupSessionId) add("dupsid")
            if (preferences.fakePackets.fakeTlsPadEncap) add("padencap")
        }
    val laneFamilies =
        deriveStrategyLaneFamilies(
            tcpSteps = tcpSteps,
            desyncUdp = preferences.protocols.desyncUdp,
            quicInitialMode = preferences.quic.initialMode,
            quicFakeProfile = preferences.quic.fakeProfile,
        )
    val httpParserEvasions =
        activeHttpParserEvasions(
            hostMixedCase = preferences.parserEvasions.hostMixedCase,
            domainMixedCase = preferences.parserEvasions.domainMixedCase,
            hostRemoveSpaces = preferences.parserEvasions.hostRemoveSpaces,
            httpMethodEol = preferences.parserEvasions.httpMethodEol,
            httpUnixEol = preferences.parserEvasions.httpUnixEol,
        )

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
        protocolToggles = protocols,
        httpParserEvasions = httpParserEvasions,
        tlsRecordSplitEnabled = tlsRecStep != null,
        tlsRecordMarker = tlsRecStep?.marker,
        splitMarker = primaryTcpStep?.marker,
        activationRound = formatNumericRange(preferences.chains.groupActivationFilter.round),
        activationPayloadSize = formatNumericRange(preferences.chains.groupActivationFilter.payloadSize),
        activationStreamBytes = formatNumericRange(preferences.chains.groupActivationFilter.streamBytes),
        fakeTtlMode =
            if (preferences.fakePackets.adaptiveFakeTtlEnabled) {
                if (preferences.fakePackets.adaptiveFakeTtlDelta == DefaultAdaptiveFakeTtlDelta) {
                    "adaptive"
                } else {
                    "adaptive_custom"
                }
            } else {
                "fixed"
            },
        adaptiveFakeTtlWindow =
            if (adaptiveFakeTtlActive) {
                "${preferences.fakePackets.adaptiveFakeTtlMin}-${preferences.fakePackets.adaptiveFakeTtlMax}"
            } else {
                null
            },
        adaptiveFakeTtlFallback = preferences.fakePackets.adaptiveFakeTtlFallback.takeIf { adaptiveFakeTtlActive },
        adaptiveFakeTtlBias =
            preferences.fakePackets.adaptiveFakeTtlDelta.takeIf {
                adaptiveFakeTtlActive && it != DefaultAdaptiveFakeTtlDelta
            },
        fakeSniMode = preferences.fakePackets.fakeTlsSniMode.takeIf { fakeTlsProfileActive },
        fakeSniValue =
            preferences.fakePackets.fakeSni.takeIf {
                fakeTlsProfileActive &&
                    preferences.fakePackets.fakeTlsSniMode == FakeTlsSniModeFixed
            },
        fakeTlsBaseMode =
            if (fakeTlsProfileActive) {
                if (preferences.fakePackets.fakeTlsUseOriginal) {
                    "original"
                } else {
                    "default"
                }
            } else {
                null
            },
        fakeTlsMods = fakeTlsMods.takeIf { fakeTlsProfileActive }.orEmpty(),
        fakeTlsSize = preferences.fakePackets.fakeTlsSize.takeIf { fakeTlsProfileActive && it != 0 },
        httpFakeProfile = preferences.fakePackets.httpFakeProfile.takeIf { it != FakePayloadProfileCompatDefault },
        tlsFakeProfile = preferences.fakePackets.tlsFakeProfile.takeIf { it != FakePayloadProfileCompatDefault },
        udpFakeProfile = preferences.fakePackets.udpFakeProfile.takeIf { it != FakePayloadProfileCompatDefault },
        quicFakeProfile = preferences.quic.fakeProfile.takeIf { quicFakeProfileActive },
        quicFakeHost =
            preferences
                .quic
                .fakeHost
                .takeIf {
                    quicFakeProfileActive && preferences.quic.fakeProfile == QuicFakeProfileRealisticInitial &&
                        it.isNotBlank()
                },
        fakeOffsetMarker = preferences.fakePackets.fakeOffsetMarker.takeUnless { it == DefaultFakeOffsetMarker },
        routeGroup = routeGroup?.takeUnless { it.isBlank() || it == "unknown" },
    )
}

fun BypassStrategySignature.stableId(): String {
    val encoded = strategyJson.encodeToString(BypassStrategySignature.serializer(), this)
    val hash = MessageDigest.getInstance("SHA-256").digest(encoded.toByteArray())
    val suffix = hash.take(6).joinToString("") { "%02x".format(it) }
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
