package com.poyka.ripdpi.ui.screens.diagnostics.state

import com.poyka.ripdpi.diagnostics.DiagnosticsScanTargetOverrides
import com.poyka.ripdpi.diagnostics.QuicVersionMatrixProfileId
import com.poyka.ripdpi.diagnostics.TlsHopComparisonProfileId

/** User-entered targets for a manual scan; [overrides] is null for profiles that take no input. */
internal data class ManualScanTargets(
    val overrides: DiagnosticsScanTargetOverrides?,
)

/** Null when the profile needs user input that is missing or invalid. */
internal fun DiagnosticsScanUiModel.manualScanTargets(profileId: String?): ManualScanTargets? =
    when (profileId) {
        SelectiveMatrixProfileId -> {
            parseSelectiveMatrixHosts(selectiveMatrixHostsInput)
                ?.let { ManualScanTargets(DiagnosticsScanTargetOverrides(selectiveMatrixHosts = it)) }
        }

        TlsHopComparisonProfileId -> {
            tlsHopComparisonInput
                .takeIf { it.isValid() }
                ?.let { ManualScanTargets(DiagnosticsScanTargetOverrides(tlsHopComparison = it)) }
        }

        QuicVersionMatrixProfileId -> {
            quicVersionMatrixInput
                .takeIf { it.isValid() }
                ?.let { ManualScanTargets(DiagnosticsScanTargetOverrides(quicVersionMatrix = it)) }
        }

        else -> {
            ManualScanTargets(null)
        }
    }
