package com.poyka.ripdpi.ui.screens.diagnostics.state

import com.poyka.ripdpi.diagnostics.DiagnosticsScanTargetOverrides
import com.poyka.ripdpi.diagnostics.EchTransferComparisonProfileId
import com.poyka.ripdpi.diagnostics.Http3TransferComparisonProfileId
import com.poyka.ripdpi.diagnostics.QuicVersionMatrixProfileId
import com.poyka.ripdpi.diagnostics.TlsHopComparisonProfileId
import com.poyka.ripdpi.diagnostics.TransferVolumeComparisonProfileId
import com.poyka.ripdpi.diagnostics.VerifiedDownloadProfileId
import com.poyka.ripdpi.diagnostics.VerifiedUploadProfileId

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

        VerifiedDownloadProfileId -> {
            verifiedDownloadInput
                .takeIf { it.isValid() }
                ?.let { ManualScanTargets(DiagnosticsScanTargetOverrides(verifiedDownload = it)) }
        }

        VerifiedUploadProfileId -> {
            verifiedUploadInput
                .takeIf { it.isValid() }
                ?.let { ManualScanTargets(DiagnosticsScanTargetOverrides(verifiedUpload = it)) }
        }

        EchTransferComparisonProfileId -> {
            transferComparisonInput
                .takeIf { it.isValidForEch() }
                ?.let { ManualScanTargets(DiagnosticsScanTargetOverrides(echComparison = it)) }
        }

        Http3TransferComparisonProfileId -> {
            transferComparisonInput
                .takeIf { it.isValidForHttp3() }
                ?.let { ManualScanTargets(DiagnosticsScanTargetOverrides(http3Comparison = it)) }
        }

        TransferVolumeComparisonProfileId -> {
            volumeComparisonInput
                .takeIf { it.isValid() }
                ?.let { ManualScanTargets(DiagnosticsScanTargetOverrides(volumeComparison = it)) }
        }

        else -> {
            ManualScanTargets(null)
        }
    }
