package com.poyka.ripdpi.activities

import androidx.compose.runtime.Stable
import com.poyka.ripdpi.data.DirectModeReasonCode
import com.poyka.ripdpi.data.DirectModeVerdictResult
import com.poyka.ripdpi.data.DirectTransportClass
import com.poyka.ripdpi.diagnostics.DiagnosticsScanLaunchOrigin
import kotlinx.collections.immutable.ImmutableList

@Stable
data class DiagnosticsSessionRowUiModel(
    val id: String,
    val profileId: String,
    val title: String,
    val subtitle: String,
    val pathMode: String,
    val serviceMode: String,
    val status: String,
    val startedAtLabel: String,
    val summary: String,
    val metrics: ImmutableList<DiagnosticsMetricUiModel>,
    val tone: DiagnosticsTone,
    val completionLabel: String? = null,
    val launchOrigin: DiagnosticsScanLaunchOrigin = DiagnosticsScanLaunchOrigin.UNKNOWN,
    val triggerClassification: String? = null,
    val ownedStackLaunchUrl: String? = null,
    val ownedStackOnly: Boolean = false,
    val directModeResult: DirectModeVerdictResult? = null,
    val directModeReasonCode: DirectModeReasonCode? = null,
    val directTransportClass: DirectTransportClass? = null,
)
