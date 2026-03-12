package com.poyka.ripdpi.diagnostics

import com.poyka.ripdpi.core.RipDpiProxyUIPreferences
import com.poyka.ripdpi.data.DefaultFakeOffsetMarker
import com.poyka.ripdpi.data.FakePayloadProfileCompatDefault
import com.poyka.ripdpi.data.FakeTlsSniModeFixed
import com.poyka.ripdpi.data.QuicFakeProfileRealisticInitial
import com.poyka.ripdpi.data.QuicFakeProfileDisabled
import com.poyka.ripdpi.data.effectiveFakeTlsSniMode
import com.poyka.ripdpi.data.effectiveHttpFakeProfile
import com.poyka.ripdpi.data.TcpChainStepKind
import com.poyka.ripdpi.data.effectiveFakeOffsetMarker
import com.poyka.ripdpi.data.effectiveQuicFakeHost
import com.poyka.ripdpi.data.effectiveQuicFakeProfile
import com.poyka.ripdpi.data.effectiveTcpChainSteps
import com.poyka.ripdpi.data.effectiveTlsFakeProfile
import com.poyka.ripdpi.data.effectiveUdpChainSteps
import com.poyka.ripdpi.data.effectiveUdpFakeProfile
import com.poyka.ripdpi.data.formatChainSummary
import com.poyka.ripdpi.data.hasCustomFakeTlsProfile
import com.poyka.ripdpi.data.legacyDesyncMethod
import com.poyka.ripdpi.data.primaryTcpChainStep
import com.poyka.ripdpi.data.tlsPreludeTcpChainStep
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.diagnostics.BypassUsageSessionEntity
import com.poyka.ripdpi.data.diagnostics.ScanSessionEntity
import com.poyka.ripdpi.utility.shellSplit
import com.poyka.ripdpi.proto.AppSettings
import java.security.MessageDigest
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

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
    val protocolToggles: List<String>,
    val tlsRecordSplitEnabled: Boolean,
    val tlsRecordMarker: String? = null,
    val splitMarker: String? = null,
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
    val recentValidatedSessions: List<ScanSessionEntity> = emptyList(),
    val recentUsageSessions: List<BypassUsageSessionEntity> = emptyList(),
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
    val desyncMethod = legacyDesyncMethod(tcpSteps).ifEmpty { "none" }
    val hasFakeStep = tcpSteps.any { step -> step.kind == TcpChainStepKind.Fake }
    val fakeTlsProfileActive = hasFakeStep && settings.desyncHttps && settings.hasCustomFakeTlsProfile()
    val commandLineRawFakePayload = settings.enableCmdSettings && hasCommandLineRawFakePayload(settings.cmdArgs)
    val quicFakeProfile = settings.effectiveQuicFakeProfile()
    val quicFakeProfileActive = !settings.enableCmdSettings && settings.desyncUdp && quicFakeProfile != QuicFakeProfileDisabled
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
        protocolToggles = protocols,
        tlsRecordSplitEnabled = tlsRecStep != null,
        tlsRecordMarker = tlsRecStep?.marker,
        splitMarker = primaryTcpStep?.marker,
        fakeSniMode = fakeTlsSniMode.takeIf { fakeTlsProfileActive },
        fakeSniValue = settings.fakeSni.ifBlank { null }?.takeIf { fakeTlsProfileActive && fakeTlsSniMode == FakeTlsSniModeFixed },
        fakeTlsBaseMode = if (fakeTlsProfileActive) if (settings.fakeTlsUseOriginal) "original" else "default" else null,
        fakeTlsMods = fakeTlsMods.takeIf { fakeTlsProfileActive }.orEmpty(),
        fakeTlsSize = settings.fakeTlsSize.takeIf { fakeTlsProfileActive && it != 0 },
        httpFakeProfile = httpFakeProfile.takeIf { !settings.enableCmdSettings && it != FakePayloadProfileCompatDefault },
        tlsFakeProfile = tlsFakeProfile.takeIf { !settings.enableCmdSettings && it != FakePayloadProfileCompatDefault },
        udpFakeProfile = udpFakeProfile.takeIf { !settings.enableCmdSettings && it != FakePayloadProfileCompatDefault },
        fakePayloadSource = "custom_raw".takeIf { commandLineRawFakePayload },
        quicFakeProfile = quicFakeProfile.takeIf { quicFakeProfileActive },
        quicFakeHost =
            settings
                .effectiveQuicFakeHost()
                .takeIf { quicFakeProfileActive && quicFakeProfile == QuicFakeProfileRealisticInitial && it.isNotBlank() },
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
    val tcpSteps = preferences.tcpChainSteps
    val udpSteps = preferences.udpChainSteps
    val primaryTcpStep = primaryTcpChainStep(tcpSteps)
    val tlsRecStep = tlsPreludeTcpChainStep(tcpSteps)
    val protocols =
        buildList {
            if (preferences.desyncHttp) add("HTTP")
            if (preferences.desyncHttps) add("HTTPS")
            if (preferences.desyncUdp) add("UDP")
        }.ifEmpty {
            listOf("NONE")
        }
    val hasFakeStep = tcpSteps.any { step -> step.kind == TcpChainStepKind.Fake }
    val hasCustomFakeTlsProfile =
        preferences.fakeTlsUseOriginal ||
            preferences.fakeTlsRandomize ||
            preferences.fakeTlsDupSessionId ||
            preferences.fakeTlsPadEncap ||
            preferences.fakeTlsSize != 0 ||
            preferences.fakeTlsSniMode != FakeTlsSniModeFixed ||
            (preferences.fakeTlsSniMode == FakeTlsSniModeFixed &&
                preferences.fakeSni.isNotBlank() &&
                preferences.fakeSni != com.poyka.ripdpi.data.DefaultFakeSni)
    val fakeTlsProfileActive = hasFakeStep && preferences.desyncHttps && hasCustomFakeTlsProfile
    val quicFakeProfileActive = preferences.desyncUdp && preferences.quicFakeProfile != QuicFakeProfileDisabled
    val fakeTlsMods =
        buildList {
            if (preferences.fakeTlsRandomize) add("rand")
            if (preferences.fakeTlsDupSessionId) add("dupsid")
            if (preferences.fakeTlsPadEncap) add("padencap")
        }

    return BypassStrategySignature(
        mode = modeOverride.name,
        configSource = "ui",
        hostAutolearn = if (preferences.hostAutolearnEnabled) "enabled" else "disabled",
        desyncMethod = preferences.desyncMethod.wireName,
        chainSummary = preferences.chainSummary,
        protocolToggles = protocols,
        tlsRecordSplitEnabled = tlsRecStep != null,
        tlsRecordMarker = tlsRecStep?.marker,
        splitMarker = primaryTcpStep?.marker,
        fakeSniMode = preferences.fakeTlsSniMode.takeIf { fakeTlsProfileActive },
        fakeSniValue = preferences.fakeSni.takeIf { fakeTlsProfileActive && preferences.fakeTlsSniMode == FakeTlsSniModeFixed },
        fakeTlsBaseMode = if (fakeTlsProfileActive) if (preferences.fakeTlsUseOriginal) "original" else "default" else null,
        fakeTlsMods = fakeTlsMods.takeIf { fakeTlsProfileActive }.orEmpty(),
        fakeTlsSize = preferences.fakeTlsSize.takeIf { fakeTlsProfileActive && it != 0 },
        httpFakeProfile = preferences.httpFakeProfile.takeIf { it != FakePayloadProfileCompatDefault },
        tlsFakeProfile = preferences.tlsFakeProfile.takeIf { it != FakePayloadProfileCompatDefault },
        udpFakeProfile = preferences.udpFakeProfile.takeIf { it != FakePayloadProfileCompatDefault },
        quicFakeProfile = preferences.quicFakeProfile.takeIf { quicFakeProfileActive },
        quicFakeHost =
            preferences
                .quicFakeHost
                .takeIf { quicFakeProfileActive && preferences.quicFakeProfile == QuicFakeProfileRealisticInitial && it.isNotBlank() },
        fakeOffsetMarker = preferences.fakeOffsetMarker.takeUnless { it == DefaultFakeOffsetMarker },
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
        if (tlsRecordSplitEnabled) {
            append(" · TLS split")
        }
        if (routeGroup != null) {
            append(" · Route ")
            append(routeGroup)
        }
    }
