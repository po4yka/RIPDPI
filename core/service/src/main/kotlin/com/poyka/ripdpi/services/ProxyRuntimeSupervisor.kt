package com.poyka.ripdpi.services

import com.poyka.ripdpi.core.ProxyForwardingEvidence
import com.poyka.ripdpi.core.RipDpiProxyFactory
import com.poyka.ripdpi.core.RipDpiProxyPreferences
import com.poyka.ripdpi.core.RipDpiProxyRuntime
import com.poyka.ripdpi.data.NativeNetworkSnapshotProvider
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
import java.util.concurrent.atomic.AtomicLong

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
) {
    private var proxyRuntime: RipDpiProxyRuntime? = null
    private var proxyJob: Job? = null

    private val runtimeGeneration = AtomicLong()

    @Volatile
    private var stopRequested: Boolean = false
    private var exitReporting: AtomicBoolean? = null

    val runtime: RipDpiProxyRuntime?
        get() = proxyRuntime

    suspend fun start(
        preferences: RipDpiProxyPreferences,
        onUnexpectedExit: suspend (SupervisorExitCause) -> Unit,
    ): LocalProxyEndpoint {
        check(proxyJob == null) { "Proxy fields not null" }

        val proxyInstance = ripDpiProxyFactory.create()
        val generation = runtimeGeneration.incrementAndGet()
        proxyRuntime = proxyInstance
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
                    runtimeGeneration.compareAndSet(generation, generation + 1L)
                    if (!exitResult.isCompleted) {
                        val cancellation = Result.failure<Int>(CancellationException("Proxy job cancelled"))
                        exitResult.complete(cancellation)
                        exitCause.complete(cancellation.toSupervisorExitCause(stopRequested = stopRequested))
                    }
                }
            }
        proxyJob = job

        job.invokeOnCompletion {
            scope.launch(dispatcher) {
                if (proxyRuntime !== proxyInstance) {
                    return@launch
                }
                if (!shouldReportExit.get()) {
                    return@launch
                }
                onUnexpectedExit(exitCause.await())
            }
        }

        @Suppress("TooGenericExceptionCaught")
        val endpoint =
            try {
                proxyInstance.awaitReady()
                resolveLocalProxyEndpoint(
                    telemetry = proxyInstance.pollTelemetry(),
                    authToken = preferences.localAuthToken,
                )
            } catch (readinessError: Exception) {
                val proxyStartWasActive = job.isActive
                shouldReportExit.set(false)
                runtimeGeneration.incrementAndGet()
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

        runCatching { proxyInstance.updateNetworkSnapshot(networkSnapshotProvider.capture()) }
        return endpoint
    }

    suspend fun stop() {
        val proxyInstance = proxyRuntime
        if (proxyInstance == null) {
            proxyJob = null
            exitReporting = null
            stopRequested = false
            return
        }

        try {
            runtimeGeneration.incrementAndGet()
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
        runtimeGeneration.incrementAndGet()
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
        val runtime =
            proxyRuntime
                ?: return RuntimeTelemetryEvidencePoll(
                    RuntimeTelemetryOutcome.NoData,
                    RuntimeForwardingEvidence.Unavailable,
                )
        val generation = runtimeGeneration.get()
        val telemetry =
            try {
                RuntimeTelemetryOutcome.Snapshot(runtime.pollTelemetry())
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                RuntimeTelemetryOutcome.EngineError(
                    message = error.message ?: "Proxy telemetry polling failed",
                    causeClass = error.javaClass.name,
                )
            }
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
                evidence.takeIf { proxyRuntime === runtime && runtimeGeneration.get() == generation }
                    ?: RuntimeForwardingEvidence.Unavailable,
        )
    }

    suspend fun pollForwardingEvidence(): ProxyForwardingEvidence? {
        val runtime = proxyRuntime ?: return null
        return try {
            runtime.pollForwardingEvidence()
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            null
        }
    }
}
