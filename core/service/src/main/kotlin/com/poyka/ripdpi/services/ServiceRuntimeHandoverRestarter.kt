package com.poyka.ripdpi.services

import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.NetworkFingerprint
import com.poyka.ripdpi.data.PolicyHandoverEvent
import com.poyka.ripdpi.data.PolicyHandoverEventStore
import com.poyka.ripdpi.data.ServiceStatus
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal class ServiceRuntimeHandoverRestarter<TSession>(
    private val mode: Mode,
    private val mutex: Mutex,
    private val policyHandoverEventStore: PolicyHandoverEventStore,
    private val currentSession: () -> TSession?,
    private val currentStatus: () -> ServiceStatus,
    private val isStopping: () -> Boolean,
    private val setStopping: (Boolean) -> Unit,
    private val resolveConnectionPolicy: suspend (NetworkFingerprint, String) -> ConnectionPolicyResolution,
    private val restartAfterHandover: suspend (TSession, ConnectionPolicyResolution, Long) -> Unit,
) where TSession : ServiceRuntimeSession, TSession : HandoverAwareSession {
    suspend fun restart(
        session: TSession,
        event: NetworkHandoverEvent,
        appliedAt: Long,
    ): String? {
        val currentFingerprint = checkNotNull(event.currentFingerprint)
        val currentFingerprintHash = currentFingerprint.scopeKey()
        val resolution =
            resolveConnectionPolicy(
                currentFingerprint,
                event.classification,
            )

        val restartResult =
            mutex.withLock {
                val activeSession = currentSession()
                if (
                    currentStatus() != ServiceStatus.Connected ||
                    isStopping() ||
                    activeSession?.runtimeId != session.runtimeId
                ) {
                    return@withLock null
                }

                val previousFingerprintHash = activeSession.currentActiveConnectionPolicy?.fingerprintHash
                setStopping(true)
                try {
                    restartAfterHandover(
                        activeSession,
                        resolution,
                        appliedAt,
                    )
                    HandoverRestartResult(
                        previousFingerprintHash = previousFingerprintHash,
                        currentFingerprintHash = currentFingerprintHash,
                    )
                } finally {
                    setStopping(false)
                }
            } ?: return null

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

        return currentFingerprintHash
    }
}

private data class HandoverRestartResult(
    val previousFingerprintHash: String?,
    val currentFingerprintHash: String,
)
