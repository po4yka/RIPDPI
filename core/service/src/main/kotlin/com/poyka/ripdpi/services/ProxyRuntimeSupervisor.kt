package com.poyka.ripdpi.services

import com.poyka.ripdpi.core.ProxyForwardingEvidence
import com.poyka.ripdpi.core.RipDpiProxyFactory
import com.poyka.ripdpi.core.RipDpiProxyPreferences
import com.poyka.ripdpi.core.RipDpiProxyRuntime
import com.poyka.ripdpi.data.NativeNetworkSnapshotProvider
import com.poyka.ripdpi.data.NativeRuntimeSnapshot
import com.poyka.ripdpi.data.RuntimeTelemetryOutcome
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

internal data class ProxyRuntimeStartResult(
    val endpoint: LocalProxyEndpoint,
    val readySnapshot: NativeRuntimeSnapshot,
    val effectivePreferences: RipDpiProxyPreferences,
    val consumedUpstreams: List<ConsumedUpstreamConfiguration>,
    val requestedWarpPatch: com.poyka.ripdpi.service.warp.RuntimeWarpProvisioningPatch?,
) {
    override fun toString(): String = "ProxyRuntimeStartResult([REDACTED])"
}

/**
 * Supervises the native proxy runtime — starts it, owns its coroutine `Job`,
 * surfaces an unexpected exit via a `SupervisorExitCause` callback, and stops
 * it within a bounded timeout.
 */
internal class ProxyRuntimeSupervisor(
    private val scope: CoroutineScope,
    private val dispatcher: CoroutineDispatcher,
    private val ripDpiProxyFactory: RipDpiProxyFactory,
    private val networkSnapshotProvider: NativeNetworkSnapshotProvider,
    private val stopTimeoutMillis: Long = 5_000L,
    private val afterForwardingLeaseAcquired: suspend () -> Unit = {},
) {
    private var proxyRuntime: RipDpiProxyRuntime? = null
    private var proxyJob: Job? = null
    private val forwardingLease = AtomicReference<ProxyForwardingLease?>()

    @Volatile
    private var stopRequested: Boolean = false
    private var exitReporting: AtomicBoolean? = null

    val runtime: RipDpiProxyRuntime?
        get() = proxyRuntime

    suspend fun start(
        preferences: RipDpiProxyPreferences,
        onUnexpectedExit: suspend (SupervisorExitCause) -> Unit,
    ): ProxyRuntimeStartResult {
        check(proxyJob == null) { "Proxy fields not null" }

        val proxyInstance = ripDpiProxyFactory.create()
        val lease = ProxyForwardingLease(proxyInstance)
        proxyRuntime = proxyInstance
        forwardingLease.set(lease)
        stopRequested = false
        val shouldReportExit = AtomicBoolean(true)
        exitReporting = shouldReportExit

        val exitCause = CompletableDeferred<SupervisorExitCause>()
        val exitResult = CompletableDeferred<Result<Int>>()
        val job =
            scope.launch(dispatcher, start = CoroutineStart.UNDISPATCHED) {
                try {
                    val result = runCatching { proxyInstance.startProxy(preferences) }
                    exitResult.complete(result)
                    exitCause.complete(result.toSupervisorExitCause(stopRequested = stopRequested))
                } finally {
                    forwardingLease.compareAndSet(lease, null)
                    if (!exitResult.isCompleted) {
                        val cancellation = Result.failure<Int>(CancellationException("Proxy job cancelled"))
                        exitResult.complete(cancellation)
                        exitCause.complete(cancellation.toSupervisorExitCause(stopRequested = stopRequested))
                    }
                }
            }
        proxyJob = job

        installUnexpectedExitHandler(job, proxyInstance, shouldReportExit, exitCause, onUnexpectedExit)

        @Suppress("TooGenericExceptionCaught")
        val startResult =
            try {
                proxyInstance.awaitReady()
                val readySnapshot = proxyInstance.pollTelemetry()
                ProxyRuntimeStartResult(
                    endpoint =
                        resolveLocalProxyEndpoint(
                            telemetry = readySnapshot,
                            authToken = preferences.localAuthToken,
                        ),
                    readySnapshot = readySnapshot,
                    effectivePreferences = preferences,
                    consumedUpstreams = emptyList(),
                    requestedWarpPatch = null,
                )
            } catch (readinessError: CancellationException) {
                cleanupFailedStart(job, proxyInstance, lease, shouldReportExit)
                throw readinessError
            } catch (readinessError: Exception) {
                val proxyStartWasActive = job.isActive
                cleanupFailedStart(job, proxyInstance, lease, shouldReportExit)
                val startupFailure =
                    resolveProxyStartupFailure(
                        readinessError = readinessError,
                        proxyStartWasActive = proxyStartWasActive,
                        proxyStartResult = exitResult.await(),
                    )
                throw SupervisorStartupFailureException(
                    SupervisorExitCause.StartupFailure(startupFailure),
                )
            }

        updateNetworkSnapshot(proxyInstance)
        return startResult
    }

    private suspend fun cleanupFailedStart(
        job: Job,
        proxyInstance: RipDpiProxyRuntime,
        lease: ProxyForwardingLease,
        shouldReportExit: AtomicBoolean,
    ) {
        shouldReportExit.set(false)
        forwardingLease.compareAndSet(lease, null)
        val cleanupFailure =
            runCatching {
                withContext(NonCancellable) {
                    if (!job.isCompleted) stop() else job.join()
                }
            }.exceptionOrNull()
        if (cleanupFailure is RuntimeCleanupPendingException) throw cleanupFailure
        if (!job.isCompleted) throw RuntimeCleanupPendingException(cleanupFailure)
        if (proxyRuntime === proxyInstance) {
            proxyJob = null
            proxyRuntime = null
            exitReporting = null
            stopRequested = false
        }
    }

    private suspend fun updateNetworkSnapshot(proxyInstance: RipDpiProxyRuntime) {
        runCatching { proxyInstance.updateNetworkSnapshot(networkSnapshotProvider.capture()) }
    }

    private fun installUnexpectedExitHandler(
        job: Job,
        proxyInstance: RipDpiProxyRuntime,
        shouldReportExit: AtomicBoolean,
        exitCause: CompletableDeferred<SupervisorExitCause>,
        onUnexpectedExit: suspend (SupervisorExitCause) -> Unit,
    ) {
        job.invokeOnCompletion {
            scope.launch(dispatcher) {
                if (proxyRuntime === proxyInstance && shouldReportExit.get()) {
                    onUnexpectedExit(exitCause.await())
                }
            }
        }
    }

    suspend fun stop() {
        val proxyInstance = proxyRuntime
        if (proxyInstance == null) {
            proxyJob = null
            exitReporting = null
            stopRequested = false
            return
        }

        forwardingLease.set(null)
        val outcome =
            runCatching {
                stopRequested = true
                stopNativeProxyAndJoin(proxyInstance)
            }
        val failure = outcome.exceptionOrNull()
        if (failure is Exception && proxyJob?.isCompleted != true) {
            val pending = failure as? RuntimeCleanupPendingException ?: RuntimeCleanupPendingException(failure)
            throw pending
        }
        proxyJob = null
        proxyRuntime = null
        exitReporting = null
        stopRequested = false
        outcome.getOrThrow()
    }

    private suspend fun stopNativeProxyAndJoin(proxyInstance: RipDpiProxyRuntime) {
        var stopFailure: Throwable? = null
        val stopped =
            withTimeoutOrNull(stopTimeoutMillis) {
                stopFailure = runCatching { proxyInstance.stopProxy() }.exceptionOrNull()
                proxyJob?.join()
                true
            } == true
        if (!stopped && proxyJob?.isCompleted != true) throw RuntimeCleanupPendingException(stopFailure)
        stopFailure?.let { throw it }
    }

    fun detach() {
        forwardingLease.set(null)
        exitReporting?.set(false)
        proxyJob = null
        proxyRuntime = null
        exitReporting = null
        stopRequested = false
    }

    suspend fun pollTelemetry(): RuntimeTelemetryOutcome {
        val runtime = proxyRuntime ?: return RuntimeTelemetryOutcome.NoData
        return runCatching { runtime.pollTelemetry() }
            .fold(
                onSuccess = { RuntimeTelemetryOutcome.Snapshot(it) },
                onFailure = { error ->
                    RuntimeTelemetryOutcome.EngineError(
                        message = error.message ?: "Proxy telemetry polling failed",
                        causeClass = error.javaClass.name,
                    )
                },
            )
    }

    suspend fun pollTelemetryAndForwardingEvidence(): RuntimeTelemetryEvidencePoll<ProxyForwardingEvidence> {
        val lease =
            forwardingLease.get()
                ?: return RuntimeTelemetryEvidencePoll(
                    RuntimeTelemetryOutcome.NoData,
                    RuntimeForwardingEvidence.Unavailable,
                )
        afterForwardingLeaseAcquired()
        val runtime = lease.runtime
        val telemetry =
            runCatching { runtime.pollTelemetry() }
                .fold(
                    onSuccess = { RuntimeTelemetryOutcome.Snapshot(it) },
                    onFailure = { error ->
                        if (error is CancellationException || error !is Exception) throw error
                        RuntimeTelemetryOutcome.EngineError(
                            message = error.message ?: "Proxy telemetry polling failed",
                            causeClass = error.javaClass.name,
                        )
                    },
                )
        val evidence =
            try {
                RuntimeForwardingEvidence.Available(runtime.pollForwardingEvidence())
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                RuntimeForwardingEvidence.Unavailable
            }
        return RuntimeTelemetryEvidencePoll(
            telemetry = telemetry,
            forwardingEvidence =
                evidence.takeIf { forwardingLease.get() === lease }
                    ?: RuntimeForwardingEvidence.Unavailable,
        )
    }

    suspend fun pollForwardingEvidence(): ProxyForwardingEvidence? {
        val lease = forwardingLease.get() ?: return null
        return try {
            lease.runtime.pollForwardingEvidence().takeIf { forwardingLease.get() === lease }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            null
        }
    }
}

private class ProxyForwardingLease(
    val runtime: RipDpiProxyRuntime,
)
