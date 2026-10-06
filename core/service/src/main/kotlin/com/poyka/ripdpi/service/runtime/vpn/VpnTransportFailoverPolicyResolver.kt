package com.poyka.ripdpi.service.runtime.vpn

import co.touchlab.kermit.Logger
import com.poyka.ripdpi.services.ConnectionPolicyResolution
import com.poyka.ripdpi.services.TransportFailoverApplyTracker
import com.poyka.ripdpi.services.TransportFailoverTarget
import com.poyka.ripdpi.services.transportFailoverTargetOrNull
import kotlinx.coroutines.CancellationException

@Suppress("TooGenericExceptionCaught")
internal suspend fun resolveVpnTransportFailoverPolicy(
    requestId: Long,
    expectedTarget: TransportFailoverTarget,
    transportFailoverApplyTracker: TransportFailoverApplyTracker,
    resolveInitialConnectionPolicy: suspend () -> ConnectionPolicyResolution,
): ConnectionPolicyResolution? {
    val resolution =
        try {
            resolveInitialConnectionPolicy()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            transportFailoverApplyTracker.recordRollbackSafeFailure(requestId)
            Logger.w(error) { "Failed to resolve transport failover request=$requestId" }
            null
        }
    return if (resolution?.transportFailoverTargetOrNull() == expectedTarget) {
        resolution
    } else {
        resolution?.let {
            transportFailoverApplyTracker.recordRollbackSafeFailure(requestId)
            Logger.w { "Resolved policy does not match transport failover request=$requestId" }
        }
        null
    }
}
