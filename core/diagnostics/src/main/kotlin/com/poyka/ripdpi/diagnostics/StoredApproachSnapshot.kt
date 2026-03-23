package com.poyka.ripdpi.diagnostics

import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.diagnostics.DiagnosticProfileEntity
import com.poyka.ripdpi.proto.AppSettings
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

internal data class StoredApproachSnapshot(
    val profileId: String?,
    val profileName: String,
    val strategyId: String,
    val strategyLabel: String,
    val strategyJson: String,
)

internal fun createStoredApproachSnapshot(
    json: Json,
    settings: AppSettings,
    profile: DiagnosticProfileEntity?,
    context: DiagnosticContextModel,
): StoredApproachSnapshot {
    val signature =
        deriveBypassStrategySignature(
            settings = settings,
            routeGroup = context.service.routeGroup,
            modeOverride = Mode.fromString(settings.ripdpiMode.ifEmpty { "vpn" }),
        )
    return StoredApproachSnapshot(
        profileId = profile?.id ?: settings.diagnosticsActiveProfileId.takeIf { it.isNotBlank() },
        profileName = profile?.name ?: context.service.selectedProfileName,
        strategyId = signature.stableId(),
        strategyLabel = signature.displayLabel(),
        strategyJson = json.encodeToString(BypassStrategySignature.serializer(), signature),
    )
}
