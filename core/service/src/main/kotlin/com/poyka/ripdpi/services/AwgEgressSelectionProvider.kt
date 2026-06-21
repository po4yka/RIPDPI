package com.poyka.ripdpi.services

import com.poyka.ripdpi.data.awg.AwgActivationRequest

/**
 * Source of a VPN-mode AmneziaWG egress selected outside persisted AppSettings.
 *
 * Standalone activation and simple-flavor failover both contribute providers; the
 * connection-policy resolver uses the first currently selected request by
 * [selectionPriority].
 */
interface AwgEgressSelectionProvider {
    val selectionPriority: Int get() = 100

    suspend fun selectedAwgEgress(): AwgActivationRequest?
}
