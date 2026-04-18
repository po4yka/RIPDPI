package com.poyka.ripdpi.diagnostics

import com.poyka.ripdpi.data.PolicyHandoverEvent

typealias DiagnosticsScanLaunchOrigin = com.poyka.ripdpi.diagnostics.application.DiagnosticsScanLaunchOrigin
typealias DiagnosticsScanLaunchTrigger = com.poyka.ripdpi.diagnostics.application.DiagnosticsScanLaunchTrigger
typealias DiagnosticsScanTriggerType = com.poyka.ripdpi.diagnostics.application.DiagnosticsScanTriggerType
internal typealias DefaultDiagnosticsBootstrapper = com.poyka.ripdpi.diagnostics.application.DefaultDiagnosticsBootstrapper
internal typealias DefaultDiagnosticsResolverActions = com.poyka.ripdpi.diagnostics.application.DefaultDiagnosticsResolverActions
internal typealias DiagnosticsRecommendationStore = com.poyka.ripdpi.diagnostics.application.DiagnosticsRecommendationStore
internal typealias DiagnosticsScanOrigin = com.poyka.ripdpi.diagnostics.application.DiagnosticsScanOrigin
internal typealias DiagnosticsScanRequestFactory = com.poyka.ripdpi.diagnostics.application.DiagnosticsScanRequestFactory
internal typealias PreparedDiagnosticsScan = com.poyka.ripdpi.diagnostics.application.PreparedDiagnosticsScan

internal fun DiagnosticsScanOrigin.toLaunchOrigin(): DiagnosticsScanLaunchOrigin =
    when (this) {
        DiagnosticsScanOrigin.USER_INITIATED -> DiagnosticsScanLaunchOrigin.USER_INITIATED
        DiagnosticsScanOrigin.AUTOMATIC_BACKGROUND -> DiagnosticsScanLaunchOrigin.AUTOMATIC_BACKGROUND
        DiagnosticsScanOrigin.DNS_CORRECTED_REPROBE -> DiagnosticsScanLaunchOrigin.DNS_CORRECTED_REPROBE
    }

internal fun PolicyHandoverEvent.toLaunchTrigger(): DiagnosticsScanLaunchTrigger =
    DiagnosticsScanLaunchTrigger(
        type = DiagnosticsScanTriggerType.POLICY_HANDOVER,
        classification = classification,
        occurredAt = occurredAt,
        previousFingerprintHash = previousFingerprintHash,
        currentFingerprintHash = currentFingerprintHash,
    )
