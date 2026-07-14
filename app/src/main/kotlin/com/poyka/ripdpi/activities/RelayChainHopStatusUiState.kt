package com.poyka.ripdpi.activities

import com.poyka.ripdpi.data.NativeRuntimeSnapshot
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList

data class RelayChainHopStatusUiState(
    val entry: RelayChainHopUiState = RelayChainHopUiState(),
    val intermediate: ImmutableList<RelayChainHopUiState> = persistentListOf(),
    val exit: RelayChainHopUiState = RelayChainHopUiState(),
) {
    /**
     * Per-hop telemetry for the multi-hop editor: hop 0 maps to the entry status, the last hop
     * to the exit status, and each intermediate hop (positions 1..hopCount-2) maps to the matching
     * [intermediate] entry. The native N-hop runtime reports intermediate-hop live latency through
     * `NativeRuntimeSnapshot.chainIntermediateHops`; an intermediate hop with no recorded telemetry
     * yet renders blank.
     */
    fun hopStatusAt(
        index: Int,
        hopCount: Int,
    ): RelayChainHopUiState =
        when (index) {
            0 -> entry
            hopCount - 1 -> exit
            else -> intermediate.getOrNull(index - 1) ?: RelayChainHopUiState()
        }

    /**
     * The aggregate cumulative latency across every hop with a recorded latency, in milliseconds,
     * or `null` when no hop has reported a latency yet. Mirrors the cumulative-latency caveat the
     * editor surfaces alongside the Tor/anonymity caveat: each added hop adds its connect latency
     * to the end-to-end path.
     */
    val cumulativeLatencyMs: Long?
        get() =
            (listOf(entry) + intermediate + listOf(exit))
                .mapNotNull { it.latencyMs }
                .takeIf { it.isNotEmpty() }
                ?.sum()
}

data class RelayChainHopUiState(
    val statusLabel: String? = null,
    val latencyLabel: String? = null,
    val latencyMs: Long? = null,
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
                latencyMs = snapshot.chainEntryLatencyMs,
            ),
        intermediate =
            snapshot.chainIntermediateHops
                .sortedBy { it.hopIndex }
                .map { hop ->
                    RelayChainHopUiState(
                        statusLabel = hop.state.chainHopStatusLabel(),
                        latencyLabel = hop.latencyMs?.let { "$it ms" },
                        latencyMs = hop.latencyMs,
                    )
                }.toImmutableList(),
        exit =
            RelayChainHopUiState(
                statusLabel = snapshot.chainExitState?.chainHopStatusLabel(),
                latencyLabel = snapshot.chainExitLatencyMs?.let { "$it ms" },
                latencyMs = snapshot.chainExitLatencyMs,
            ),
    )

private fun String.chainHopStatusLabel(): String =
    split('_', '-')
        .filter { it.isNotBlank() }
        .joinToString(" ") { token ->
            token.replaceFirstChar { char -> if (char.isLowerCase()) char.titlecase() else char.toString() }
        }
