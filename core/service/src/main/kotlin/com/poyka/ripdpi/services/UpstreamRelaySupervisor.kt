package com.poyka.ripdpi.services

import com.poyka.ripdpi.core.RipDpiRelayConfig
import com.poyka.ripdpi.core.RipDpiRelayFactory
import com.poyka.ripdpi.core.RipDpiRelayRuntime
import com.poyka.ripdpi.data.NativeRuntimeSnapshot
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject

internal class UpstreamRelaySupervisor(
    private val scope: CoroutineScope,
    private val dispatcher: CoroutineDispatcher,
    private val relayFactory: RipDpiRelayFactory,
    private val stopTimeoutMillis: Long = 5_000L,
) {
    private var relayRuntime: RipDpiRelayRuntime? = null
    private var relayJob: Job? = null

    suspend fun start(
        config: RipDpiRelayConfig,
        onUnexpectedExit: suspend (Result<Int>) -> Unit,
    ) {
        check(relayJob == null) { "Relay fields not null" }
        val runtime = relayFactory.create()
        relayRuntime = runtime

        val exitResult = CompletableDeferred<Result<Int>>()
        val job =
            scope.launch(dispatcher, start = CoroutineStart.UNDISPATCHED) {
                try {
                    exitResult.complete(runCatching { runtime.start(config) })
                } finally {
                    if (!exitResult.isCompleted) {
                        exitResult.complete(Result.failure(IllegalStateException("Relay job cancelled")))
                    }
                }
            }
        relayJob = job

        try {
            runtime.awaitReady()
        } catch (readinessError: Exception) {
            try {
                runCatching { runtime.stop() }
                job.join()
            } finally {
                relayJob = null
                relayRuntime = null
            }
            throw readinessError
        }

        job.invokeOnCompletion {
            scope.launch(dispatcher) {
                onUnexpectedExit(exitResult.await())
            }
        }
    }

    suspend fun stop() {
        val runtime = relayRuntime
        if (runtime == null) {
            relayJob = null
            return
        }

        try {
            runtime.stop()
            withTimeoutOrNull(stopTimeoutMillis) {
                relayJob?.join()
            }
        } finally {
            relayJob = null
            relayRuntime = null
        }
    }

    fun detach() {
        relayJob = null
        relayRuntime = null
    }

    suspend fun pollTelemetry(): NativeRuntimeSnapshot? = runCatching { relayRuntime?.pollTelemetry() }.getOrNull()
}

internal open class UpstreamRelaySupervisorFactory
    @Inject
    constructor(
        private val relayFactory: RipDpiRelayFactory,
    ) {
        open fun create(
            scope: CoroutineScope,
            dispatcher: CoroutineDispatcher,
        ): UpstreamRelaySupervisor =
            UpstreamRelaySupervisor(
                scope = scope,
                dispatcher = dispatcher,
                relayFactory = relayFactory,
            )
    }
