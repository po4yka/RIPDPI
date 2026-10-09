package com.poyka.ripdpi.ui.screens.diagnostics

import androidx.annotation.StringRes
import com.poyka.ripdpi.R
import com.poyka.ripdpi.activities.DiagnosticsAllowlistSniState
import com.poyka.ripdpi.activities.DiagnosticsByohCompatibilityState
import com.poyka.ripdpi.activities.DiagnosticsCidrWhitelistState
import com.poyka.ripdpi.activities.DiagnosticsCompressionProbeState
import com.poyka.ripdpi.activities.DiagnosticsDnsAvailabilityState
import com.poyka.ripdpi.activities.DiagnosticsDnsIntegrityState
import com.poyka.ripdpi.activities.DiagnosticsDomainReachabilityState
import com.poyka.ripdpi.activities.DiagnosticsDpiSuiteState
import com.poyka.ripdpi.activities.DiagnosticsIpv4WhitelistState
import com.poyka.ripdpi.activities.DiagnosticsPluggableTransportState
import com.poyka.ripdpi.activities.DiagnosticsRknBlockDiagnosisState
import com.poyka.ripdpi.activities.DiagnosticsTcp16FatHeaderState

@StringRes
internal fun diagnosticToolStateLabelRes(state: DiagnosticsDnsIntegrityState): Int =
    when (state) {
        DiagnosticsDnsIntegrityState.Idle -> R.string.diagnostics_tool_state_idle
        DiagnosticsDnsIntegrityState.Running -> R.string.diagnostics_tool_state_running
        DiagnosticsDnsIntegrityState.Complete -> R.string.diagnostics_tool_state_complete
        DiagnosticsDnsIntegrityState.Failed -> R.string.diagnostics_tool_state_failed
    }

@StringRes
internal fun diagnosticToolStateLabelRes(state: DiagnosticsDnsAvailabilityState): Int =
    when (state) {
        DiagnosticsDnsAvailabilityState.Idle -> R.string.diagnostics_tool_state_idle
        DiagnosticsDnsAvailabilityState.Running -> R.string.diagnostics_tool_state_running
        DiagnosticsDnsAvailabilityState.Complete -> R.string.diagnostics_tool_state_complete
        DiagnosticsDnsAvailabilityState.Failed -> R.string.diagnostics_tool_state_failed
    }

@StringRes
internal fun diagnosticToolStateLabelRes(state: DiagnosticsDomainReachabilityState): Int =
    when (state) {
        DiagnosticsDomainReachabilityState.Idle -> R.string.diagnostics_tool_state_idle
        DiagnosticsDomainReachabilityState.Running -> R.string.diagnostics_tool_state_running
        DiagnosticsDomainReachabilityState.Complete -> R.string.diagnostics_tool_state_complete
        DiagnosticsDomainReachabilityState.Failed -> R.string.diagnostics_tool_state_failed
    }

@StringRes
internal fun diagnosticToolStateLabelRes(state: DiagnosticsCompressionProbeState): Int =
    when (state) {
        DiagnosticsCompressionProbeState.Idle -> R.string.diagnostics_tool_state_idle
        DiagnosticsCompressionProbeState.Running -> R.string.diagnostics_tool_state_running
        DiagnosticsCompressionProbeState.Complete -> R.string.diagnostics_tool_state_complete
        DiagnosticsCompressionProbeState.Failed -> R.string.diagnostics_tool_state_failed
    }

@StringRes
internal fun diagnosticToolStateLabelRes(state: DiagnosticsTcp16FatHeaderState): Int =
    when (state) {
        DiagnosticsTcp16FatHeaderState.Idle -> R.string.diagnostics_tool_state_idle
        DiagnosticsTcp16FatHeaderState.Running -> R.string.diagnostics_tool_state_running
        DiagnosticsTcp16FatHeaderState.Complete -> R.string.diagnostics_tool_state_complete
        DiagnosticsTcp16FatHeaderState.Failed -> R.string.diagnostics_tool_state_failed
    }

@StringRes
internal fun diagnosticToolStateLabelRes(state: DiagnosticsAllowlistSniState): Int =
    when (state) {
        DiagnosticsAllowlistSniState.Idle -> R.string.diagnostics_tool_state_idle
        DiagnosticsAllowlistSniState.Running -> R.string.diagnostics_tool_state_running
        DiagnosticsAllowlistSniState.Complete -> R.string.diagnostics_tool_state_complete
        DiagnosticsAllowlistSniState.Failed -> R.string.diagnostics_tool_state_failed
    }

@StringRes
internal fun diagnosticToolStateLabelRes(state: DiagnosticsByohCompatibilityState): Int =
    when (state) {
        DiagnosticsByohCompatibilityState.Idle -> R.string.diagnostics_tool_state_idle
        DiagnosticsByohCompatibilityState.Running -> R.string.diagnostics_tool_state_running
        DiagnosticsByohCompatibilityState.Complete -> R.string.diagnostics_tool_state_complete
        DiagnosticsByohCompatibilityState.Failed -> R.string.diagnostics_tool_state_failed
    }

@StringRes
internal fun diagnosticToolStateLabelRes(state: DiagnosticsIpv4WhitelistState): Int =
    when (state) {
        DiagnosticsIpv4WhitelistState.Idle -> R.string.diagnostics_tool_state_idle
        DiagnosticsIpv4WhitelistState.Running -> R.string.diagnostics_tool_state_running
        DiagnosticsIpv4WhitelistState.Complete -> R.string.diagnostics_tool_state_complete
        DiagnosticsIpv4WhitelistState.Failed -> R.string.diagnostics_tool_state_failed
    }

@StringRes
internal fun diagnosticToolStateLabelRes(state: DiagnosticsCidrWhitelistState): Int =
    when (state) {
        DiagnosticsCidrWhitelistState.Idle -> R.string.diagnostics_tool_state_idle
        DiagnosticsCidrWhitelistState.Running -> R.string.diagnostics_tool_state_running
        DiagnosticsCidrWhitelistState.Complete -> R.string.diagnostics_tool_state_complete
        DiagnosticsCidrWhitelistState.Failed -> R.string.diagnostics_tool_state_failed
    }

@StringRes
internal fun diagnosticToolStateLabelRes(state: DiagnosticsRknBlockDiagnosisState): Int =
    when (state) {
        DiagnosticsRknBlockDiagnosisState.Idle -> R.string.diagnostics_tool_state_idle
        DiagnosticsRknBlockDiagnosisState.Running -> R.string.diagnostics_tool_state_running
        DiagnosticsRknBlockDiagnosisState.Complete -> R.string.diagnostics_tool_state_complete
        DiagnosticsRknBlockDiagnosisState.Failed -> R.string.diagnostics_tool_state_failed
    }

@StringRes
internal fun diagnosticToolStateLabelRes(state: DiagnosticsDpiSuiteState): Int =
    when (state) {
        DiagnosticsDpiSuiteState.Idle -> R.string.diagnostics_tool_state_idle
        DiagnosticsDpiSuiteState.Running -> R.string.diagnostics_tool_state_running
        DiagnosticsDpiSuiteState.Complete -> R.string.diagnostics_tool_state_complete
        DiagnosticsDpiSuiteState.Failed -> R.string.diagnostics_tool_state_failed
        DiagnosticsDpiSuiteState.Cancelled -> R.string.diagnostics_tool_state_cancelled
    }

@StringRes
internal fun diagnosticToolStateLabelRes(state: DiagnosticsPluggableTransportState): Int =
    when (state) {
        DiagnosticsPluggableTransportState.Idle -> R.string.diagnostics_tool_state_idle
        DiagnosticsPluggableTransportState.Running -> R.string.diagnostics_tool_state_running
        DiagnosticsPluggableTransportState.Complete -> R.string.diagnostics_tool_state_complete
        DiagnosticsPluggableTransportState.Failed -> R.string.diagnostics_tool_state_failed
        DiagnosticsPluggableTransportState.Disabled -> R.string.diagnostics_tool_state_disabled
    }
