package com.poyka.ripdpi.services

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

internal class ProxyRuntimeSupervisor(
    private val scope: CoroutineScope,
    private val dispatcher: CoroutineDispatcher,
    private val ripDpiProxyFactory: RipDpiProxyFactory,
    private val networkSnapshotProvider: NativeNetworkSnapshotProvider,
    private val stopTimeoutMillis: Long = 5_000L,
) {
    private var proxyRuntime: RipDpiProxyRuntime? = null
    private var proxyJob: Job? = null
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
}
