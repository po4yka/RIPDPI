@file:Suppress("LongMethod", "SwallowedException")

package com.poyka.ripdpi.services

import com.poyka.ripdpi.core.RipDpiAmneziaWgFactory
import com.poyka.ripdpi.core.RipDpiAmneziaWgRuntime
import com.poyka.ripdpi.data.RuntimeTelemetryOutcome
import com.poyka.ripdpi.data.awg.AwgActivationRequest
import com.poyka.ripdpi.service.awg.AmneziaWgRuntimeConfigResolver
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

/**
 * Lifecycle supervisor for a standalone AmneziaWG tunnel. A one-to-one mirror of
 * [WarpRuntimeSupervisor]: it owns a single [RipDpiAmneziaWgRuntime], launches its
 * blocking [RipDpiAmneziaWgRuntime.start] undispatched, awaits readiness, and routes
 * an unexpected exit through [SupervisorExitCause]. The only structural difference is
 * the input type -- the app-reachable [AwgActivationRequest] -- which the injected
 * [AmneziaWgRuntimeConfigResolver] maps into the engine-api `ResolvedRipDpiAmneziaWgConfig`
 * the runtime consumes.
 */
internal class AmneziaWgRuntimeSupervisor(
    private val scope: CoroutineScope,
    private val dispatcher: CoroutineDispatcher,
    private val amneziaWgFactory: RipDpiAmneziaWgFactory,
    private val runtimeConfigResolver: AmneziaWgRuntimeConfigResolver,
    private val stopTimeoutMillis: Long = 5_000L,
) {
    private var amneziaWgRuntime: RipDpiAmneziaWgRuntime? = null
    private var amneziaWgJob: Job? = null

    @Volatile
    private var stopRequested: Boolean = false
    private var exitReporting: AtomicBoolean? = null

    val runtime: RipDpiAmneziaWgRuntime?
        get() = amneziaWgRuntime

    suspend fun start(
        request: AwgActivationRequest,
        onUnexpectedExit: suspend (SupervisorExitCause) -> Unit,
    ) {
        check(amneziaWgJob == null) { "AmneziaWG fields not null" }
        val runtime = amneziaWgFactory.create()
        val resolvedConfig = runtimeConfigResolver.resolve(request)
        amneziaWgRuntime = runtime
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
                                .failure<Int>(CancellationException("AmneziaWG job cancelled"))
                                .toSupervisorExitCause(stopRequested = stopRequested),
                        )
                    }
                }
            }
        amneziaWgJob = job

        job.invokeOnCompletion {
            scope.launch(dispatcher) {
                if (amneziaWgRuntime !== runtime) {
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
                amneziaWgJob = null
                amneziaWgRuntime = null
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
        val runtime = amneziaWgRuntime
        if (runtime == null) {
            amneziaWgJob = null
            exitReporting = null
            stopRequested = false
            return
        }

        try {
            stopRequested = true
            runtime.stop()
            withTimeoutOrNull(stopTimeoutMillis) {
                amneziaWgJob?.join()
            }
        } finally {
            amneziaWgJob = null
            amneziaWgRuntime = null
            exitReporting = null
            stopRequested = false
        }
    }

    fun detach() {
        exitReporting?.set(false)
        amneziaWgJob = null
        amneziaWgRuntime = null
        exitReporting = null
        stopRequested = false
    }

    suspend fun pollTelemetry(): RuntimeTelemetryOutcome {
        val runtime = amneziaWgRuntime ?: return RuntimeTelemetryOutcome.NoData
        return runCatching { runtime.pollTelemetry() }
            .fold(
                onSuccess = { RuntimeTelemetryOutcome.Snapshot(it) },
                onFailure = { error ->
                    RuntimeTelemetryOutcome.EngineError(
                        message = error.message ?: "AmneziaWG telemetry polling failed",
                        causeClass = error.javaClass.name,
                    )
                },
            )
    }
}

internal open class AmneziaWgRuntimeSupervisorFactory
    @Inject
    constructor(
        private val amneziaWgFactory: RipDpiAmneziaWgFactory,
        private val runtimeConfigResolver: AmneziaWgRuntimeConfigResolver,
    ) {
        open fun create(
            scope: CoroutineScope,
            dispatcher: CoroutineDispatcher,
        ): AmneziaWgRuntimeSupervisor =
            AmneziaWgRuntimeSupervisor(
                scope = scope,
                dispatcher = dispatcher,
                amneziaWgFactory = amneziaWgFactory,
                runtimeConfigResolver = runtimeConfigResolver,
            )
    }
