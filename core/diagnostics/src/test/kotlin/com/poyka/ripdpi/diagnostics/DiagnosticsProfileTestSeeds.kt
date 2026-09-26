package com.poyka.ripdpi.diagnostics

import com.poyka.ripdpi.data.diagnostics.DiagnosticProfileEntity
import com.poyka.ripdpi.diagnostics.contract.profile.ProbePersistencePolicyWire
import kotlinx.serialization.json.Json

internal fun FakeDiagnosticsHistoryStores.seedDefaultProfile(json: Json) {
    profilesState.value =
        listOf(
            DiagnosticProfileEntity(
                id = "default",
                name = "Default",
                source = "bundled",
                version = 1,
                requestJson =
                    diagnosticsProfileRequestJson(
                        json = json,
                        profileId = "default",
                        displayName = "Default",
                        targets =
                            DiagnosticsProfileTargets(
                                domainTargets = listOf(DomainTarget(host = "example.org")),
                                dnsTargets = listOf(DnsTarget(domain = "blocked.example")),
                            ),
                    ),
                updatedAt = 1L,
            ),
        )
}

internal fun FakeDiagnosticsHistoryStores.seedStrategyProbeProfile(
    json: Json,
    profileId: String = "automatic-probing",
    name: String = "Automatic probing",
    suiteId: String = "quick_v1",
    family: DiagnosticProfileFamily =
        if (profileId == "automatic-audit") {
            DiagnosticProfileFamily.AUTOMATIC_AUDIT
        } else {
            DiagnosticProfileFamily.AUTOMATIC_PROBING
        },
) {
    profilesState.value =
        listOf(
            DiagnosticProfileEntity(
                id = profileId,
                name = name,
                source = "bundled",
                version = 1,
                requestJson =
                    diagnosticsProfileRequestJson(
                        json = json,
                        profileId = profileId,
                        displayName = name,
                        kind = ScanKind.STRATEGY_PROBE,
                        family = family,
                        targets =
                            DiagnosticsProfileTargets(
                                domainTargets = listOf(DomainTarget(host = "example.org")),
                                quicTargets = listOf(QuicTarget(host = "example.org")),
                                strategyProbe = StrategyProbeRequest(suiteId = suiteId),
                            ),
                        allowBackground = family == DiagnosticProfileFamily.AUTOMATIC_PROBING,
                        requiresRawPath = true,
                        manualOnly = family == DiagnosticProfileFamily.AUTOMATIC_AUDIT,
                        probePersistencePolicy =
                            if (family == DiagnosticProfileFamily.AUTOMATIC_PROBING) {
                                ProbePersistencePolicyWire.BACKGROUND_ONLY
                            } else {
                                ProbePersistencePolicyWire.MANUAL_ONLY
                            },
                    ),
                updatedAt = 1L,
            ),
        )
}
