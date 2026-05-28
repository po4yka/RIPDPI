package com.poyka.ripdpi.activities

import com.poyka.ripdpi.data.NativeRuntimeSnapshot

data class RelayChainHopStatusUiState(
    val entry: RelayChainHopUiState = RelayChainHopUiState(),
    val exit: RelayChainHopUiState = RelayChainHopUiState(),
)

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
