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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

internal data class ProxyRuntimeStartResult(
    val endpoint: LocalProxyEndpoint,
    val readySnapshot: NativeRuntimeSnapshot,
)

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
                )
            } catch (readinessError: Exception) {
                val proxyStartWasActive = job.isActive
                shouldReportExit.set(false)
                forwardingLease.compareAndSet(lease, null)
                try {
                    runCatching {
                        if (proxyStartWasActive) {
                            proxyInstance.stopProxy()
                        }
                    }
                    job.join()
                } finally {
                    proxyJob = null
                    proxyRuntime = null
                    exitReporting = null
                    stopRequested = false
                }
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
        try {
            stopRequested = true
            proxyInstance.stopProxy()
            withTimeoutOrNull(stopTimeoutMillis) {
                proxyJob?.join()
            }
        } finally {
            proxyJob = null
            proxyRuntime = null
            exitReporting = null
            stopRequested = false
        }
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
