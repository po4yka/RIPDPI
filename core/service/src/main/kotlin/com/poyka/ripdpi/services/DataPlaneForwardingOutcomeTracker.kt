package com.poyka.ripdpi.services

import java.util.concurrent.atomic.AtomicReference

private const val TerminalFailureConfirmationPolls = 3

internal data class DataPlaneForwardingOutcome(
    val wireValue: String,
    val terminalFailure: Boolean,
    val revision: Long,
)

internal data class DataPlaneEvidenceResult(
    val snapshot: VpnTelemetrySnapshot,
    val outcome: DataPlaneForwardingOutcome,
)

internal class DataPlaneForwardingOutcomeTracker {
    private val published =
        AtomicReference(
            DataPlaneForwardingOutcome(
                wireValue = DataPlaneCorrelationState.EvidenceUnavailable.wireValue,
                terminalFailure = false,
                revision = 0L,
            ),
        )
    private var terminalFailureCandidate: DataPlaneCorrelationState? = null
    private var terminalFailurePolls = 0
    private var revision = 0L

    fun current(): DataPlaneForwardingOutcome = published.get()

    fun resetConfirmation() {
        terminalFailureCandidate = null
        terminalFailurePolls = 0
    }

    fun publish(state: DataPlaneCorrelationState) {
        if (state.isTerminalFailure) {
            if (terminalFailureCandidate == state) {
                terminalFailurePolls += 1
            } else {
                terminalFailureCandidate = state
                terminalFailurePolls = 1
            }
        } else {
            resetConfirmation()
        }
        revision += 1L
        published.set(
            DataPlaneForwardingOutcome(
                wireValue = state.wireValue,
                terminalFailure = terminalFailurePolls >= TerminalFailureConfirmationPolls,
                revision = revision,
            ),
        )
    }
}
