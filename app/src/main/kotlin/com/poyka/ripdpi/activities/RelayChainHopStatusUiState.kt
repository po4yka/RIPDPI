package com.poyka.ripdpi.activities

import com.poyka.ripdpi.data.NativeRuntimeSnapshot

data class RelayChainHopStatusUiState(
    val entry: RelayChainHopUiState = RelayChainHopUiState(),
    val exit: RelayChainHopUiState = RelayChainHopUiState(),
) {
    /**
     * Per-hop telemetry for the multi-hop editor: hop 0 maps to the entry status, the last hop
     * to the exit status, and intermediate hops carry no live telemetry yet (the native N-hop
     * runtime composition that reports them lands in the next epic task), so they render blank.
     */
    fun hopStatusAt(
        index: Int,
        hopCount: Int,
    ): RelayChainHopUiState =
        when (index) {
            0 -> entry
            hopCount - 1 -> exit
            else -> RelayChainHopUiState()
        }
}

data class RelayChainHopUiState(
    val statusLabel: String? = null,
    val latencyLabel: String? = null,
) {
    val displayLabel: String?
        get() =
            listOfNotNull(statusLabel, latencyLabel)
                .joinToString(" · ")
                .ifBlank { null }
}

internal fun buildRelayChainHopStatus(snapshot: NativeRuntimeSnapshot): RelayChainHopStatusUiState =
    RelayChainHopStatusUiState(
        entry =
            RelayChainHopUiState(
                statusLabel = snapshot.chainEntryState?.chainHopStatusLabel(),
                latencyLabel = snapshot.chainEntryLatencyMs?.let { "$it ms" },
            ),
        exit =
            RelayChainHopUiState(
                statusLabel = snapshot.chainExitState?.chainHopStatusLabel(),
                latencyLabel = snapshot.chainExitLatencyMs?.let { "$it ms" },
            ),
    )

private fun String.chainHopStatusLabel(): String =
    split('_', '-')
        .filter { it.isNotBlank() }
        .joinToString(" ") { token ->
            token.replaceFirstChar { char -> if (char.isLowerCase()) char.titlecase() else char.toString() }
        }
