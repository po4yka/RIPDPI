package com.poyka.ripdpi.services

import co.touchlab.kermit.Logger
import com.poyka.ripdpi.data.LogTags
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.NetworkFingerprint
import com.poyka.ripdpi.data.PolicyHandoverEvent
import com.poyka.ripdpi.data.PolicyHandoverEventStore
import com.poyka.ripdpi.data.policyHandoverDeliveryId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal class ServiceRuntimeHandoverRestarter<TSession>(
    private val mode: Mode,
    private val mutex: Mutex,
    private val policyHandoverEventStore: PolicyHandoverEventStore,
    private val currentSession: () -> TSession?,
    private val canRestart: (TSession, NetworkFingerprint) -> Boolean,
    private val setHandoverRestarting: (Boolean) -> Unit,
    private val resolveConnectionPolicy: suspend (NetworkFingerprint, String) -> ConnectionPolicyResolution,
    private val restartAfterHandover: suspend (TSession, ConnectionPolicyResolution, Long, () -> Boolean) -> Boolean,
) where TSession : ServiceRuntimeSession, TSession : HandoverAwareSession {
    suspend fun restart(
        session: TSession,
        event: NetworkHandoverEvent,
        appliedAt: Long,
        isCurrentAttempt: () -> Boolean,
    ): String? {
        val currentFingerprint = event.currentFingerprint
        return if (currentFingerprint == null || !isCurrentAttempt()) {
            null
        } else {
            restartResolvedSession(session, event, currentFingerprint, appliedAt, isCurrentAttempt)
        }
    }

    private suspend fun restartResolvedSession(
        session: TSession,
        event: NetworkHandoverEvent,
        currentFingerprint: NetworkFingerprint,
        appliedAt: Long,
        isCurrentAttempt: () -> Boolean,
    ): String? {
        val resolution =
            resolveConnectionPolicy(currentFingerprint, event.classification)
                .copy(
                    samePhysicalRoute = event.canReusePhysicalRoute(currentFingerprint),
                    physicalRouteSignature = currentFingerprint.physicalRouteSignature,
                )
        val restartResult =
            if (isCurrentAttempt()) {
                restartCurrentSession(session, resolution, currentFingerprint, appliedAt, isCurrentAttempt)
            } else {
                null
            }

        if (restartResult != null && isCurrentAttempt()) {
            // The runtime already restarted. A failed event write must not report the restart
            // as failed, because the processor would then restart the runtime again.
            try {
                publishRestart(event, currentFingerprint, resolution, restartResult, appliedAt)
            } catch (error: CancellationException) {
                throw error
            } catch (
                @Suppress("TooGenericExceptionCaught") error: Exception,
            ) {
                Logger.e(error, tag = LogTags.SERVICE) { "Failed to publish the policy handover event" }
            }
            return restartResult.currentFingerprintHash
        }
        return null
    }

    private suspend fun restartCurrentSession(
        session: TSession,
        resolution: ConnectionPolicyResolution,
        fingerprint: NetworkFingerprint,
        appliedAt: Long,
        isCurrentAttempt: () -> Boolean,
    ): HandoverRestartResult? =
        mutex.withLock {
            val activeSession = currentSession()
            val sessionCurrent = activeSession?.runtimeId == session.runtimeId
            if (sessionCurrent && canRestart(activeSession, fingerprint) && isCurrentAttempt()) {
                val previousFingerprintHash = activeSession.currentActiveConnectionPolicy?.fingerprintHash
                // Once stop/start begins it must finish under the lifecycle mutex; cancellation
                // only invalidates publication and lets the newer event or Stop run next.
                setHandoverRestarting(true)
                val restarted =
                    try {
                        withContext(NonCancellable) {
                            restartAfterHandover(activeSession, resolution, appliedAt, isCurrentAttempt)
                        }
                    } finally {
                        setHandoverRestarting(false)
                    }
                if (restarted) HandoverRestartResult(previousFingerprintHash, fingerprint.scopeKey()) else null
            } else {
                null
            }
        }

    private suspend fun publishRestart(
        event: NetworkHandoverEvent,
        currentFingerprint: NetworkFingerprint,
        resolution: ConnectionPolicyResolution,
        restartResult: HandoverRestartResult,
        appliedAt: Long,
    ) {
        policyHandoverEventStore.publish(
            PolicyHandoverEvent(
                deliveryId =
                    policyHandoverDeliveryId(
                        "${mode.preferenceValue}|${restartResult.currentFingerprintHash}|" +
                            "${event.classification}|${event.occurredAt}",
                    ),
                mode = mode,
                previousFingerprintHash = restartResult.previousFingerprintHash,
                currentFingerprintHash = restartResult.currentFingerprintHash,
                classification = event.classification,
                currentNetworkValidated = currentFingerprint.networkValidated,
                currentCaptivePortalDetected = currentFingerprint.captivePortalDetected,
                usedRememberedPolicy = resolution.matchedNetworkPolicy != null,
                occurredAt = appliedAt,
            ),
        )
    }
}

private data class HandoverRestartResult(
    val previousFingerprintHash: String?,
    val currentFingerprintHash: String,
)

private fun NetworkHandoverEvent.canReusePhysicalRoute(current: NetworkFingerprint): Boolean {
    val previous = previousFingerprint ?: return false
    return classification == "link_refresh" && previous.physicalRouteSignature != null &&
        previous.physicalRouteSignature == current.physicalRouteSignature &&
        previous.transport == current.transport && previous.metered == current.metered &&
        previous.networkValidated && current.networkValidated &&
        !previous.captivePortalDetected && !current.captivePortalDetected
}
