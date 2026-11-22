package com.poyka.ripdpi.services

import com.poyka.ripdpi.data.DiagnosticsNetworkEpoch

/** The producing run and physical epoch, with a live check that the batch may still teach policy. */
internal class DirectPathLearningOrigin(
    private val run: Any,
    private val networkEpoch: DiagnosticsNetworkEpoch? = null,
    val isCurrent: () -> Boolean,
) {
    val hasNetworkEpoch: Boolean
        get() = networkEpoch != null

    fun sameScope(other: DirectPathLearningOrigin?): Boolean =
        other != null && run === other.run && networkEpoch == other.networkEpoch
}
