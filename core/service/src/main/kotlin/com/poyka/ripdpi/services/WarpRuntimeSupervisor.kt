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
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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

    @Volatile
    private var stopRequested: Boolean = false
    private var exitReporting: AtomicBoolean? = null

    val runtime: RipDpiWarpRuntime?
        get() = warpRuntime

    private var consumed: ConsumedUpstreamConfiguration? = null
    var requestedProvisioningPatch: com.poyka.ripdpi.service.warp.RuntimeWarpProvisioningPatch? = null
        private set

    fun requireConsumedConfiguration(): ConsumedUpstreamConfiguration = checkNotNull(consumed)

    suspend fun start(
        config: RipDpiWarpConfig,
        requestedReference: com.poyka.ripdpi.service.warp.RequestedWarpRuntimeReference?,
        onUnexpectedExit: suspend (SupervisorExitCause) -> Unit,
    ) {
        check(warpJob == null) { "WARP fields not null" }
        val runtime = warpFactory.create()
        val resolved = runtimeConfigResolver.resolve(config, requestedReference)
        val resolvedConfig = resolved.configuration
        val consumedConfiguration = ConsumedUpstreamConfiguration.warp(resolvedConfig)
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
            consumed = consumedConfiguration
            requestedProvisioningPatch = resolved.requestedPatch
        } catch (cancelled: CancellationException) {
            cleanupFailedReadiness(shouldReportExit)
            throw cancelled
        } catch (readinessError: Exception) {
            cleanupFailedReadiness(shouldReportExit)
            val startupCause =
                (exitCause.await() as? SupervisorExitCause.StartupFailure)
                    ?: SupervisorExitCause.StartupFailure(readinessError)
            throw SupervisorStartupFailureException(startupCause)
        }
    }

    private suspend fun cleanupFailedReadiness(shouldReportExit: AtomicBoolean) {
        shouldReportExit.set(false)
        withContext(NonCancellable) { stop() }
    }

    suspend fun stop() {
        val runtime = warpRuntime ?: return
        val job = warpJob
        stopRequested = true
        val failure =
            runCatching {
                runtime.stop()
                awaitSupervisorWorkerCompletion(job, stopTimeoutMillis)
            }.exceptionOrNull()
        if (job?.isCompleted != true) throw RuntimeCleanupPendingException(failure)
        if (warpRuntime === runtime) {
            warpJob = null
            warpRuntime = null
            consumed = null
            requestedProvisioningPatch = null
            exitReporting = null
            stopRequested = false
        }
        failure?.let { throw it }
    }

    fun detach() {
        exitReporting?.set(false)
        warpJob = null
        warpRuntime = null
        consumed = null
        requestedProvisioningPatch = null
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
