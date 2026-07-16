package com.poyka.ripdpi.services

import co.touchlab.kermit.Logger
import com.poyka.ripdpi.data.NetworkHandoverStates
import com.poyka.ripdpi.data.ServiceStatus
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

internal interface HandoverRetryPolicy {
    fun nextRetryDelayMillis(currentRetryCount: Int): Long?
}

internal class ExponentialHandoverRetryPolicy(
    private val maxRetries: Int,
    private val baseDelayMillis: Long,
    private val maxDelayMillis: Long,
) : HandoverRetryPolicy {
    override fun nextRetryDelayMillis(currentRetryCount: Int): Long? {
        if (currentRetryCount >= maxRetries) {
            return null
        }
        return (baseDelayMillis * (1L shl currentRetryCount.coerceAtMost(maxRetries)))
            .coerceAtMost(maxDelayMillis)
    }
}

internal class NetworkHandoverProcessor<TSession>(
    private val scope: CoroutineScope,
    private val dispatcher: CoroutineDispatcher,
    private val networkHandoverMonitor: NetworkHandoverMonitor,
    private val retryPolicy: HandoverRetryPolicy,
    private val clock: ServiceClock,
    private val serviceLabel: () -> String,
    private val currentSession: () -> TSession?,
    private val currentStatus: () -> ServiceStatus,
    private val isStopping: () -> Boolean,
    private val recordPendingClassification: (String) -> Unit,
    private val updateHandoverState: (String?) -> Unit,
    private val performRestart: suspend (TSession, NetworkHandoverEvent, Long) -> String?,
    private val onExhaustedFailure: suspend (Exception) -> Unit,
    private val handoverCooldownMillis: Long,
) where TSession : ServiceRuntimeSession, TSession : HandoverAwareSession {
    private var monitorJob: Job? = null

    fun startMonitoring() {
        cancel()
        monitorJob =
            scope.launch(dispatcher) {
                networkHandoverMonitor.events.collect { event ->
                    recordPendingClassification(event.classification)
                    updateHandoverState(
                        if (event.isActionable) {
                            NetworkHandoverStates.Observed
                        } else {
                            NetworkHandoverStates.WaitingForNetwork
                        },
                    )
                    if (!event.isActionable) {
                        return@collect
                    }
                    handle(event)
                }
            }
    }

    fun cancel() {
        monitorJob?.cancel()
        monitorJob = null
    }

    @Suppress("ReturnCount")
    private suspend fun handle(event: NetworkHandoverEvent) {
        val session = currentSession()
        val currentFingerprint = event.currentFingerprint
        val canHandle =
            session != null &&
                currentFingerprint != null &&
                currentStatus() == ServiceStatus.Connected &&
                !isStopping()
        if (!canHandle) {
            return
        }

        if (currentFingerprint.captivePortalDetected) {
            updateHandoverState(NetworkHandoverStates.DeferredCaptivePortal)
            Logger.i {
                "${serviceLabel()}: captive portal detected, deferring handover until network is validated"
            }
            return
        }

        val fingerprintHash = currentFingerprint.scopeKey()
        val now = clock.nowMillis()
        val isCoolingDown =
            event.classification != "connectivity_restore" &&
                session.lastSuccessfulHandoverFingerprintHash == fingerprintHash &&
                now - session.lastSuccessfulHandoverAt < handoverCooldownMillis
        if (isCoolingDown) {
            updateHandoverState(NetworkHandoverStates.Revalidated)
            return
        }

        updateHandoverState(NetworkHandoverStates.Restarting)
        val restartAttempt = runCatching { performRestart(session, event, now) }
        val failure = restartAttempt.exceptionOrNull()
        if (failure == null) {
            val appliedFingerprintHash = restartAttempt.getOrNull() ?: return
            session.lastSuccessfulHandoverFingerprintHash = appliedFingerprintHash
            session.lastSuccessfulHandoverAt = now
            session.handoverRetryCount = 0
            updateHandoverState(NetworkHandoverStates.Revalidated)
            return
        }

        val retryCount = session.handoverRetryCount
        val retryDelayMillis = retryPolicy.nextRetryDelayMillis(retryCount)
        if (retryDelayMillis != null) {
            session.handoverRetryCount = retryCount + 1
            updateHandoverState(NetworkHandoverStates.RetryScheduled)
            Logger.w {
                "${serviceLabel()} handover failed (attempt ${retryCount + 1}), " +
                    "retrying in ${retryDelayMillis}ms"
            }
            scope.launch(dispatcher) {
                delay(retryDelayMillis)
                if (currentSession()?.runtimeId == session.runtimeId && !isStopping()) {
                    handle(event)
                }
            }
            return
        }

        val error =
            failure as? Exception ?: IllegalStateException(
                "Failed to restart ${serviceLabel()} after handover",
                failure,
            )
        Logger.e(error) {
            "Failed to restart ${serviceLabel()} after handover (exhausted retries)"
        }
        updateHandoverState(NetworkHandoverStates.Failed)
        onExhaustedFailure(error)
    }
}
