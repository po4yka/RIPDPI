@file:Suppress("LongMethod", "SwallowedException")

package com.poyka.ripdpi.services

import com.poyka.ripdpi.core.RipDpiWarpConfig
import com.poyka.ripdpi.core.RipDpiWarpFactory
import com.poyka.ripdpi.core.RipDpiWarpRuntime
import com.poyka.ripdpi.data.RuntimeTelemetryOutcome
import com.poyka.ripdpi.service.warp.WarpRuntimeConfigResolver
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject

internal class WarpRuntimeSupervisor(
    private val scope: CoroutineScope,
    private val dispatcher: CoroutineDispatcher,
    private val warpFactory: RipDpiWarpFactory,
    private val runtimeConfigResolver: WarpRuntimeConfigResolver,
    private val stopTimeoutMillis: Long = 5_000L,
) {
    private var warpRuntime: RipDpiWarpRuntime? = null
    private var warpJob: Job? = null
    private var stopRequested: Boolean = false
    private var exitReporting: AtomicBoolean? = null

    val runtime: RipDpiWarpRuntime?
        get() = warpRuntime

    suspend fun start(
        config: RipDpiWarpConfig,
        onUnexpectedExit: suspend (SupervisorExitCause) -> Unit,
    ) {
        check(warpJob == null) { "WARP fields not null" }
        val runtime = warpFactory.create()
        val resolvedConfig = runtimeConfigResolver.resolve(config)
        warpRuntime = runtime
        stopRequested = false
        val shouldReportExit = AtomicBoolean(true)
        exitReporting = shouldReportExit

        val exitCause = CompletableDeferred<SupervisorExitCause>()
        val job =
            scope.launch(dispatcher, start = CoroutineStart.UNDISPATCHED) {
                try {
                    val result = runCatching { runtime.start(resolvedConfig) }
                    exitCause.complete(result.toSupervisorExitCause(stopRequested = stopRequested))
                } finally {
                    if (!exitCause.isCompleted) {
                        exitCause.complete(
                            Result
                                .failure<Int>(CancellationException("WARP job cancelled"))
                                .toSupervisorExitCause(stopRequested = stopRequested),
                        )
                    }
                }
            }
        warpJob = job

        job.invokeOnCompletion {
            scope.launch(dispatcher) {
                if (warpRuntime !== runtime) {
                    return@launch
                }
                if (!shouldReportExit.get()) {
                    return@launch
                }
                onUnexpectedExit(exitCause.await())
            }
        }

        @Suppress("TooGenericExceptionCaught")
        try {
            runtime.awaitReady()
        } catch (readinessError: Exception) {
            shouldReportExit.set(false)
            try {
                stopRequested = true
                runCatching { runtime.stop() }
                job.join()
            } finally {
                warpJob = null
                warpRuntime = null
                exitReporting = null
                stopRequested = false
            }
            val startupCause =
                (exitCause.await() as? SupervisorExitCause.StartupFailure)
                    ?: SupervisorExitCause.StartupFailure(readinessError)
            throw SupervisorStartupFailureException(startupCause)
        }
    }

    suspend fun stop() {
        val runtime = warpRuntime
        if (runtime == null) {
            warpJob = null
            exitReporting = null
            stopRequested = false
            return
        }

        try {
            stopRequested = true
            runtime.stop()
            withTimeoutOrNull(stopTimeoutMillis) {
                warpJob?.join()
            }
        } finally {
            warpJob = null
            warpRuntime = null
            exitReporting = null
            stopRequested = false
        }
    }

    fun detach() {
        exitReporting?.set(false)
        warpJob = null
        warpRuntime = null
        exitReporting = null
        stopRequested = false
    }

    suspend fun pollTelemetry(): RuntimeTelemetryOutcome {
        val runtime = warpRuntime ?: return RuntimeTelemetryOutcome.NoData
        return runCatching { runtime.pollTelemetry() }
            .fold(
                onSuccess = { RuntimeTelemetryOutcome.Snapshot(it) },
                onFailure = { error ->
                    RuntimeTelemetryOutcome.EngineError(
                        message = error.message ?: "WARP telemetry polling failed",
                        causeClass = error.javaClass.name,
                    )
                },
            )
    }
}

internal open class WarpRuntimeSupervisorFactory
    @Inject
    constructor(
        private val warpFactory: RipDpiWarpFactory,
        private val runtimeConfigResolver: WarpRuntimeConfigResolver,
    ) {
        open fun create(
            scope: CoroutineScope,
            dispatcher: CoroutineDispatcher,
        ): WarpRuntimeSupervisor =
            WarpRuntimeSupervisor(
                scope = scope,
                dispatcher = dispatcher,
                warpFactory = warpFactory,
                runtimeConfigResolver = runtimeConfigResolver,
            )
    }
