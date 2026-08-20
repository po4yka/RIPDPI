package com.poyka.ripdpi.services

import com.poyka.ripdpi.data.NativeRuntimeSnapshot

internal sealed interface RuntimeStartEvidence {
    data class ProxySnapshot(
        val snapshot: NativeRuntimeSnapshot,
    ) : RuntimeStartEvidence

    data object NotApplicable : RuntimeStartEvidence
}

internal fun ProxyRuntimeStartResult?.toRuntimeStartEvidence(): RuntimeStartEvidence =
    this
        ?.let { RuntimeStartEvidence.ProxySnapshot(it.readySnapshot) }
        ?: RuntimeStartEvidence.NotApplicable
