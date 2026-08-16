package com.poyka.ripdpi.ui.screens.diagnostics

import com.poyka.ripdpi.activities.DiagnosticsAllowlistSniState
import com.poyka.ripdpi.activities.DiagnosticsCompressionProbeState
import com.poyka.ripdpi.activities.DiagnosticsDnsAvailabilityState
import com.poyka.ripdpi.activities.DiagnosticsDnsIntegrityState
import com.poyka.ripdpi.activities.DiagnosticsDomainReachabilityState
import com.poyka.ripdpi.activities.DiagnosticsTcp16FatHeaderState
import com.poyka.ripdpi.activities.DiagnosticsTone

internal fun DiagnosticsDnsIntegrityState.tone(): DiagnosticsTone =
    when (this) {
        DiagnosticsDnsIntegrityState.Idle -> DiagnosticsTone.Neutral
        DiagnosticsDnsIntegrityState.Running -> DiagnosticsTone.Info
        DiagnosticsDnsIntegrityState.Complete -> DiagnosticsTone.Positive
        DiagnosticsDnsIntegrityState.Failed -> DiagnosticsTone.Negative
    }

internal fun DiagnosticsDnsAvailabilityState.tone(): DiagnosticsTone =
    when (this) {
        DiagnosticsDnsAvailabilityState.Idle -> DiagnosticsTone.Neutral
        DiagnosticsDnsAvailabilityState.Running -> DiagnosticsTone.Info
        DiagnosticsDnsAvailabilityState.Complete -> DiagnosticsTone.Positive
        DiagnosticsDnsAvailabilityState.Failed -> DiagnosticsTone.Negative
    }

internal fun DiagnosticsDomainReachabilityState.tone(): DiagnosticsTone =
    when (this) {
        DiagnosticsDomainReachabilityState.Idle -> DiagnosticsTone.Neutral
        DiagnosticsDomainReachabilityState.Running -> DiagnosticsTone.Info
        DiagnosticsDomainReachabilityState.Complete -> DiagnosticsTone.Positive
        DiagnosticsDomainReachabilityState.Failed -> DiagnosticsTone.Negative
    }

internal fun DiagnosticsCompressionProbeState.tone(): DiagnosticsTone =
    when (this) {
        DiagnosticsCompressionProbeState.Idle -> DiagnosticsTone.Neutral
        DiagnosticsCompressionProbeState.Running -> DiagnosticsTone.Info
        DiagnosticsCompressionProbeState.Complete -> DiagnosticsTone.Positive
        DiagnosticsCompressionProbeState.Failed -> DiagnosticsTone.Negative
    }

internal fun DiagnosticsTcp16FatHeaderState.tone(): DiagnosticsTone =
    when (this) {
        DiagnosticsTcp16FatHeaderState.Idle -> DiagnosticsTone.Neutral
        DiagnosticsTcp16FatHeaderState.Running -> DiagnosticsTone.Info
        DiagnosticsTcp16FatHeaderState.Complete -> DiagnosticsTone.Positive
        DiagnosticsTcp16FatHeaderState.Failed -> DiagnosticsTone.Negative
    }

internal fun DiagnosticsAllowlistSniState.tone(): DiagnosticsTone =
    when (this) {
        DiagnosticsAllowlistSniState.Idle -> DiagnosticsTone.Neutral
        DiagnosticsAllowlistSniState.Running -> DiagnosticsTone.Info
        DiagnosticsAllowlistSniState.Complete -> DiagnosticsTone.Positive
        DiagnosticsAllowlistSniState.Failed -> DiagnosticsTone.Negative
    }

// Liveness, kept separate from tone() on purpose. Every tone() above folds Running into
// DiagnosticsTone.Info, which the StatusIndicator palette renders as Idle, so tone alone cannot
// tell a probe that is running from one that never started.
internal fun DiagnosticsDnsIntegrityState.running(): Boolean = this == DiagnosticsDnsIntegrityState.Running

internal fun DiagnosticsDnsAvailabilityState.running(): Boolean = this == DiagnosticsDnsAvailabilityState.Running

internal fun DiagnosticsDomainReachabilityState.running(): Boolean = this == DiagnosticsDomainReachabilityState.Running

internal fun DiagnosticsCompressionProbeState.running(): Boolean = this == DiagnosticsCompressionProbeState.Running

internal fun DiagnosticsTcp16FatHeaderState.running(): Boolean = this == DiagnosticsTcp16FatHeaderState.Running

internal fun DiagnosticsAllowlistSniState.running(): Boolean = this == DiagnosticsAllowlistSniState.Running
