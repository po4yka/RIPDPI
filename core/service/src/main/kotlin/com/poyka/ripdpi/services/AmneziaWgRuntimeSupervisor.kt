@file:Suppress("LongMethod", "SwallowedException")

package com.poyka.ripdpi.services

import com.poyka.ripdpi.core.RipDpiAmneziaWgFactory
import com.poyka.ripdpi.core.RipDpiAmneziaWgRuntime
import com.poyka.ripdpi.data.awg.AwgActivationRequest
import com.poyka.ripdpi.service.awg.AmneziaWgRuntimeConfigResolver
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

    private var consumed: ConsumedUpstreamConfiguration? = null

    fun requireConsumedConfiguration(): ConsumedUpstreamConfiguration = checkNotNull(consumed)

    suspend fun start(
        request: AwgActivationRequest,
        onUnexpectedExit: suspend (SupervisorExitCause) -> Unit,
    ) {
        check(amneziaWgJob == null) { "AmneziaWG fields not null" }
        val runtime = amneziaWgFactory.create()
        val resolvedConfig = runtimeConfigResolver.resolve(request)
        val consumedConfiguration = ConsumedUpstreamConfiguration.awg(resolvedConfig)
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
            consumed = consumedConfiguration
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
        val runtime = amneziaWgRuntime ?: return
        val job = amneziaWgJob
        stopRequested = true
        val failure =
            runCatching {
                runtime.stop()
                awaitSupervisorWorkerCompletion(job, stopTimeoutMillis)
            }.exceptionOrNull()
        if (job?.isCompleted != true) throw RuntimeCleanupPendingException(failure)
        if (amneziaWgRuntime === runtime) {
            amneziaWgJob = null
            amneziaWgRuntime = null
            consumed = null
            exitReporting = null
            stopRequested = false
        }
        failure?.let { throw it }
    }

    fun detach() {
        exitReporting?.set(false)
        amneziaWgJob = null
        amneziaWgRuntime = null
        consumed = null
        exitReporting = null
        stopRequested = false
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
