package com.poyka.ripdpi.services

import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.NetworkFingerprint
import com.poyka.ripdpi.data.PolicyHandoverEvent
import com.poyka.ripdpi.data.PolicyHandoverEventStore
import com.poyka.ripdpi.data.ServiceStatus
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal class ServiceRuntimeHandoverRestarter<TSession>(
    private val mode: Mode,
    private val mutex: Mutex,
    private val policyHandoverEventStore: PolicyHandoverEventStore,
    private val currentSession: () -> TSession?,
    private val currentStatus: () -> ServiceStatus,
    private val isStopping: () -> Boolean,
    private val setHandoverRestarting: (Boolean) -> Unit,
    private val resolveConnectionPolicy: suspend (NetworkFingerprint, String) -> ConnectionPolicyResolution,
    private val restartAfterHandover: suspend (TSession, ConnectionPolicyResolution, Long) -> Unit,
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
        val currentFingerprintHash = currentFingerprint.scopeKey()
        val resolution = resolveConnectionPolicy(currentFingerprint, event.classification)
        val restartResult =
            if (isCurrentAttempt()) {
                restartCurrentSession(session, resolution, currentFingerprintHash, appliedAt, isCurrentAttempt)
            } else {
                null
            }

        if (restartResult != null && isCurrentAttempt()) {
            publishRestart(event, currentFingerprint, resolution, restartResult, appliedAt)
            return currentFingerprintHash
        }
        return null
    }

    private suspend fun restartCurrentSession(
        session: TSession,
        resolution: ConnectionPolicyResolution,
        currentFingerprintHash: String,
        appliedAt: Long,
        isCurrentAttempt: () -> Boolean,
    ): HandoverRestartResult? =
        mutex.withLock {
            val activeSession = currentSession()
            val lifecycleReady = currentStatus() == ServiceStatus.Connected && !isStopping()
            val sessionCurrent = activeSession?.runtimeId == session.runtimeId
            if (lifecycleReady && sessionCurrent && isCurrentAttempt()) {
                val previousFingerprintHash = activeSession.currentActiveConnectionPolicy?.fingerprintHash
                // Once stop/start begins it must finish under the lifecycle mutex; cancellation
                // only invalidates publication and lets the newer event or Stop run next.
                setHandoverRestarting(true)
                try {
                    withContext(NonCancellable) {
                        restartAfterHandover(activeSession, resolution, appliedAt)
                    }
                } finally {
                    setHandoverRestarting(false)
                }
                HandoverRestartResult(previousFingerprintHash, currentFingerprintHash)
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
                mode = mode,
                previousFingerprintHash = restartResult.previousFingerprintHash,
                currentFingerprintHash = restartResult.currentFingerprintHash,
                classification = event.classification,
                currentNetworkValidated = currentFingerprint.networkValidated,
                currentCaptivePortalDetected = currentFingerprint.captivePortalDetected,
                usedRememberedPolicy = resolution.matchedNetworkPolicy != null,
                policySignature = resolution.policySignature,
                occurredAt = appliedAt,
            ),
        )
    }
}

private data class HandoverRestartResult(
    val previousFingerprintHash: String?,
    val currentFingerprintHash: String,
)
