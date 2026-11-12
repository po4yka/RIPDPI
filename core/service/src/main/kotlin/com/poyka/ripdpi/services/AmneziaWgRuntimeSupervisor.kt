@file:Suppress("SwallowedException")

package com.poyka.ripdpi.services

import com.poyka.ripdpi.core.ResolvedRipDpiAmneziaWgConfig
import com.poyka.ripdpi.core.RipDpiAmneziaWgFactory
import com.poyka.ripdpi.core.RipDpiAmneziaWgRuntime
import com.poyka.ripdpi.data.NumericIp
import com.poyka.ripdpi.data.awg.AwgActivationRequest
import com.poyka.ripdpi.data.awg.AwgCohortCatalog
import com.poyka.ripdpi.data.awg.AwgCohortCatalogData
import com.poyka.ripdpi.data.awg.handshakeAttemptsFor
import com.poyka.ripdpi.service.awg.AmneziaWgRuntimeConfigResolver
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Provider

/**
 * Owns authenticated AWG native sessions and their startup retry budget.
 * Established WS sessions use fresh runtimes after bounded backoff; the owner keeps the TUN installed.
 */
internal class AmneziaWgRuntimeSupervisor(
    private val scope: CoroutineScope,
    private val dispatcher: CoroutineDispatcher,
    private val amneziaWgFactory: RipDpiAmneziaWgFactory,
    private val runtimeConfigResolver: AmneziaWgRuntimeConfigResolver,
    private val stopTimeoutMillis: Long = 5_000L,
    private val activeRegistry: ActiveAwgRuntimeRegistry,
    private val handshakeAttempts: (AwgActivationRequest) -> Int = { 1 },
    private val resolveWsEndpoint: (suspend (String) -> List<String>)? = null,
) {
    private var amneziaWgRuntime: RipDpiAmneziaWgRuntime? = null
    private var amneziaWgJob: Job? = null
    private var setup: Deferred<ResolvedRipDpiAmneziaWgConfig>? = null
    private var recoveryJob: Job? = null
    private val lifecycleMutex = Mutex()
    private val stopSignal = AtomicReference<RuntimeStopSignal?>()

    private class RuntimeStopSignal(
        val runtime: RipDpiAmneziaWgRuntime,
    ) {
        val result = CompletableDeferred<Result<Unit>>()
    }

    @Volatile
    private var sessionToken: Any = Any()

    @Volatile
    private var stopRequested: Boolean = false
    private var exitReporting: AtomicBoolean? = null

    val runtime: RipDpiAmneziaWgRuntime?
        get() = amneziaWgRuntime

    private var consumed: ConsumedUpstreamConfiguration? = null
    private var activeRegistration: ActiveAwgRuntimeRegistry.Registration? = null

    fun requireConsumedConfiguration(): ConsumedUpstreamConfiguration = checkNotNull(consumed)

    suspend fun start(
        request: AwgActivationRequest,
        onUnexpectedExit: suspend (SupervisorExitCause) -> Unit,
    ) {
        val token = sessionToken
        lifecycleMutex.withLock {
            repeat(handshakeAttempts(request) - 1) {
                try {
                    return startAttempt(request, onUnexpectedExit, token)
                } catch (_: SupervisorStartupFailureException) {
                    // Retry startup with fresh native state within the selected cohort budget.
                }
            }
            startAttempt(request, onUnexpectedExit, token)
        }
    }

    private suspend fun startAttempt(
        request: AwgActivationRequest,
        onUnexpectedExit: suspend (SupervisorExitCause) -> Unit,
        token: Any,
    ) {
        check(amneziaWgJob == null) { "AmneziaWG fields not null" }
        val resolvedConfig = resolveConfig(request, token)
        val runtime = createRuntime(token)
        val consumedConfiguration = ConsumedUpstreamConfiguration.awg(resolvedConfig)
        stopRequested = false
        val shouldReportExit = AtomicBoolean(true)
        exitReporting = shouldReportExit

        val exitCause = CompletableDeferred<SupervisorExitCause>()
        val startupReady = CompletableDeferred<Boolean>()
        val sessionRegistration = AtomicReference<ActiveAwgRuntimeRegistry.Registration?>()
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
        if (sessionToken !== token) {
            shouldReportExit.set(false)
            startupReady.complete(false)
            withContext(NonCancellable) { stopRuntime() }
            requireCurrentSession(token)
        }

        job.invokeOnCompletion {
            activeRegistry.revoke(sessionRegistration.get())
            scope.launch(dispatcher) {
                if (!startupReady.await() || !shouldReportExit.get()) return@launch
                val cause = exitCause.await()
                if (cause is SupervisorExitCause.ExpectedStop) {
                    onUnexpectedExit(cause)
                } else if (sessionToken === token && amneziaWgRuntime === runtime) {
                    if (request.carrier == AwgActivationRequest.CARRIER_WS) {
                        recoverWsCarrier(request, token, runtime, cause, onUnexpectedExit)
                    } else {
                        onUnexpectedExit(cause)
                    }
                }
            }
        }

        @Suppress("TooGenericExceptionCaught")
        try {
            runtime.awaitReady(resolvedConfig.carrier.readyTimeoutMillis())
            consumed = consumedConfiguration
            activeRegistration =
                activeRegistry.register(resolvedConfig) { amneziaWgRuntime === runtime && job.isActive }
            sessionRegistration.set(activeRegistration)
            if (!job.isActive) activeRegistry.revoke(sessionRegistration.get())
            startupReady.complete(true)
        } catch (cancelled: CancellationException) {
            startupReady.complete(false)
            cleanupFailedReadiness(shouldReportExit)
            throw cancelled
        } catch (readinessError: Exception) {
            startupReady.complete(false)
            cleanupFailedReadiness(shouldReportExit)
            val startupCause =
                (exitCause.await() as? SupervisorExitCause.StartupFailure)
                    ?: SupervisorExitCause.StartupFailure(readinessError)
            throw SupervisorStartupFailureException(startupCause)
        }
    }

    private fun createRuntime(token: Any): RipDpiAmneziaWgRuntime {
        val runtime = amneziaWgFactory.create()
        amneziaWgRuntime = runtime
        if (sessionToken !== token) {
            amneziaWgRuntime = null
            requireCurrentSession(token)
        }
        return runtime
    }

    private fun requireCurrentSession(token: Any) {
        if (sessionToken !== token) throw CancellationException("AmneziaWG setup stopped")
    }

    private suspend fun resolveConfig(
        request: AwgActivationRequest,
        token: Any,
    ): ResolvedRipDpiAmneziaWgConfig {
        val preparing =
            scope.async(dispatcher) {
                val config = runtimeConfigResolver.resolve(request)
                val resolver = resolveWsEndpoint
                if (request.carrier != AwgActivationRequest.CARRIER_WS || resolver == null) {
                    config
                } else {
                    val host = ("https://" + request.carrierWsUrl.substring(WssSchemePrefix.length)).toHttpUrl().host
                    val addresses = if (NumericIp.parseAddress(host) != null) listOf(host) else resolver(host)
                    require(addresses.isNotEmpty() && addresses.all { NumericIp.parseAddress(it) != null }) {
                        "AmneziaWG WS bootstrap returned no usable numeric addresses"
                    }
                    config.copy(carrierWsAddresses = addresses.distinct())
                }
            }
        setup = preparing
        val resolvedConfig =
            try {
                preparing.await()
            } finally {
                withContext(NonCancellable) { preparing.cancelAndJoin() }
                if (setup === preparing) setup = null
            }
        requireCurrentSession(token)
        return resolvedConfig
    }

    private suspend fun cleanupFailedReadiness(shouldReportExit: AtomicBoolean) {
        shouldReportExit.set(false)
        withContext(NonCancellable) { stopRuntime() }
    }

    suspend fun stop() {
        sessionToken = Any()
        recoveryJob?.cancelAndJoin()
        recoveryJob = null
        setup?.cancelAndJoin()
        val signalFailure = amneziaWgRuntime?.let { signalRuntimeStop(it).exceptionOrNull() }
        lifecycleMutex.withLock { stopRuntime() }
        signalFailure?.let { throw it }
    }

    private suspend fun stopRuntime() {
        activeRegistry.revoke(activeRegistration)
        val runtime = amneziaWgRuntime ?: return
        val job = amneziaWgJob
        stopRequested = true
        val signalFailure = signalRuntimeStop(runtime).exceptionOrNull()
        val failure =
            runCatching { awaitSupervisorWorkerCompletion(job, stopTimeoutMillis) }.exceptionOrNull()
                ?: signalFailure
        if (job?.isCompleted != true) {
            stopSignal.get()?.takeIf { it.runtime === runtime }?.let { stopSignal.compareAndSet(it, null) }
            throw RuntimeCleanupPendingException(failure)
        }
        if (amneziaWgRuntime === runtime) {
            amneziaWgJob = null
            amneziaWgRuntime = null
            consumed = null
            exitReporting = null
            stopRequested = false
        }
        stopSignal.get()?.takeIf { it.runtime === runtime }?.let { stopSignal.compareAndSet(it, null) }
        failure?.let { throw it }
    }

    private suspend fun signalRuntimeStop(runtime: RipDpiAmneziaWgRuntime): Result<Unit> {
        var selected: RuntimeStopSignal? = null
        while (selected == null) {
            if (amneziaWgRuntime !== runtime) return Result.success(Unit)
            val previous = stopSignal.get()
            if (previous?.runtime === runtime) {
                selected = previous
            } else {
                val signal = RuntimeStopSignal(runtime)
                if (stopSignal.compareAndSet(previous, signal)) {
                    stopRequested = true
                    withContext(NonCancellable) { signal.result.complete(runCatching { runtime.stop() }) }
                    selected = signal
                }
            }
        }
        return selected.result.await()
    }

    private fun recoverWsCarrier(
        request: AwgActivationRequest,
        token: Any,
        failedRuntime: RipDpiAmneziaWgRuntime,
        initialCause: SupervisorExitCause,
        onUnexpectedExit: suspend (SupervisorExitCause) -> Unit,
    ) {
        val recovery =
            scope.launch(dispatcher, start = CoroutineStart.LAZY) {
                var failure = initialCause
                try {
                    val cleanupError =
                        runCatching {
                            lifecycleMutex.withLock {
                                if (sessionToken !== token || amneziaWgRuntime !== failedRuntime) return@launch
                                exitReporting?.set(false)
                                stopRuntime()
                            }
                        }.exceptionOrNull()
                    if (cleanupError != null) {
                        if (cleanupError is CancellationException || cleanupError !is Exception) throw cleanupError
                        if (sessionToken === token) onUnexpectedExit(SupervisorExitCause.StartupFailure(cleanupError))
                        return@launch
                    }
                    repeat(WsRecoveryAttempts) { attempt ->
                        delay(WsRecoveryDelayMillis shl attempt)
                        val error =
                            runCatching {
                                lifecycleMutex.withLock {
                                    if (sessionToken !== token) return@launch
                                    startAttempt(request, onUnexpectedExit, token)
                                }
                            }.exceptionOrNull() ?: return@launch
                        if (error is CancellationException || error !is Exception) throw error
                        failure = SupervisorExitCause.StartupFailure(error)
                        if (error is RuntimeCleanupPendingException) {
                            if (sessionToken === token) onUnexpectedExit(failure)
                            return@launch
                        }
                    }
                    if (sessionToken === token) onUnexpectedExit(failure)
                } finally {
                    if (recoveryJob === currentCoroutineContext()[Job]) recoveryJob = null
                }
            }
        recoveryJob = recovery
        recovery.start()
    }

    fun detachCompleted() {
        if (amneziaWgJob?.isCompleted == true) detach()
    }

    fun detach() {
        sessionToken = Any()
        recoveryJob?.cancel()
        recoveryJob = null
        activeRegistry.revoke(activeRegistration)
        activeRegistration = null
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
        private val activeRegistry: ActiveAwgRuntimeRegistry,
        private val cohortCatalog: Provider<AwgCohortCatalog>,
    ) {
        open fun create(
            scope: CoroutineScope,
            dispatcher: CoroutineDispatcher,
            resolveWsEndpoint: (suspend (String) -> List<String>)? = null,
        ): AmneziaWgRuntimeSupervisor =
            AmneziaWgRuntimeSupervisor(
                scope = scope,
                dispatcher = dispatcher,
                amneziaWgFactory = amneziaWgFactory,
                runtimeConfigResolver = runtimeConfigResolver,
                activeRegistry = activeRegistry,
                resolveWsEndpoint = resolveWsEndpoint,
                handshakeAttempts = { request ->
                    AwgCohortCatalogData(cohortCatalog.get().all()).handshakeAttemptsFor(request.obfuscation)
                },
            )
    }

private const val WssSchemePrefix = "wss://"
private const val WsRecoveryAttempts = 3
private const val WsRecoveryDelayMillis = 1_000L
