package com.poyka.ripdpi.diagnostics

import com.poyka.ripdpi.data.DefaultFakeOffsetMarker
import com.poyka.ripdpi.data.effectiveFakeOffsetMarker
import com.poyka.ripdpi.data.effectiveSplitMarker
import com.poyka.ripdpi.data.effectiveTlsRecordMarker
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.diagnostics.BypassUsageSessionEntity
import com.poyka.ripdpi.data.diagnostics.ScanSessionEntity
import com.poyka.ripdpi.proto.AppSettings
import java.security.MessageDigest
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
    val desyncMethod: String,
    val protocolToggles: List<String>,
    val tlsRecordSplitEnabled: Boolean,
    val tlsRecordMarker: String?,
    val splitMarker: String?,
    val fakeSniMode: String?,
    val fakeOffsetMarker: String?,
    val routeGroup: String?,
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

fun deriveBypassStrategySignature(
    settings: AppSettings,
    routeGroup: String?,
    modeOverride: Mode? = null,
): BypassStrategySignature {
    val mode = (modeOverride ?: Mode.fromString(settings.ripdpiMode.ifEmpty { "vpn" })).name
    val protocols =
        buildList {
            if (settings.desyncHttp) add("HTTP")
            if (settings.desyncHttps) add("HTTPS")
            if (settings.desyncUdp) add("UDP")
        }.ifEmpty {
            listOf("NONE")
        }
    val desyncMethod = settings.desyncMethod.ifEmpty { "disorder" }
    val fakeSniMode =
        settings.fakeSni
            .takeIf { it.isNotBlank() && desyncMethod in setOf("fake", "disoob") }
            ?.let { "custom" }

    return BypassStrategySignature(
        mode = mode,
        configSource = if (settings.enableCmdSettings) "command_line" else "ui",
        desyncMethod = desyncMethod,
        protocolToggles = protocols,
        tlsRecordSplitEnabled = settings.tlsrecEnabled,
        tlsRecordMarker = settings.effectiveTlsRecordMarker().takeIf { settings.tlsrecEnabled },
        splitMarker = settings.effectiveSplitMarker().takeIf { desyncMethod != "none" },
        fakeSniMode = fakeSniMode,
        fakeOffsetMarker =
            settings
                .effectiveFakeOffsetMarker()
                .takeUnless { it == DefaultFakeOffsetMarker },
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
        append(desyncMethod.replaceFirstChar { it.uppercase() })
        append(" · ")
        append(protocolToggles.joinToString("/"))
        if (tlsRecordSplitEnabled) {
            append(" · TLS split")
        }
        if (routeGroup != null) {
            append(" · Route ")
            append(routeGroup)
        }
    }
