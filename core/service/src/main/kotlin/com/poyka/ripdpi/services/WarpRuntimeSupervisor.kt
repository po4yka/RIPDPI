package com.poyka.ripdpi.services

import com.poyka.ripdpi.core.RipDpiWarpConfig
import com.poyka.ripdpi.core.RipDpiWarpFactory
import com.poyka.ripdpi.core.RipDpiWarpRuntime
import com.poyka.ripdpi.data.NativeRuntimeSnapshot
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject

internal class WarpRuntimeSupervisor(
    private val scope: CoroutineScope,
    private val dispatcher: CoroutineDispatcher,
    private val warpFactory: RipDpiWarpFactory,
    private val stopTimeoutMillis: Long = 5_000L,
) {
    private var warpRuntime: RipDpiWarpRuntime? = null
    private var warpJob: Job? = null

    val runtime: RipDpiWarpRuntime?
        get() = warpRuntime

    suspend fun start(
        config: RipDpiWarpConfig,
        onUnexpectedExit: suspend (Result<Int>) -> Unit,
    ) {
        check(warpJob == null) { "WARP fields not null" }
        val runtime = warpFactory.create()
        warpRuntime = runtime

        val exitResult = CompletableDeferred<Result<Int>>()
        val job =
            scope.launch(dispatcher, start = CoroutineStart.UNDISPATCHED) {
                try {
                    exitResult.complete(runCatching { runtime.start(config) })
                } finally {
                    if (!exitResult.isCompleted) {
                        exitResult.complete(Result.failure(IllegalStateException("WARP job cancelled")))
                    }
                }
            }
        warpJob = job

        try {
            runtime.awaitReady()
        } catch (readinessError: Exception) {
            try {
                runCatching { runtime.stop() }
                job.join()
            } finally {
                warpJob = null
                warpRuntime = null
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
        val runtime = warpRuntime
        if (runtime == null) {
            warpJob = null
            return
        }

        try {
            runtime.stop()
            withTimeoutOrNull(stopTimeoutMillis) {
                warpJob?.join()
            }
        } finally {
            warpJob = null
            warpRuntime = null
        }
    }

    fun detach() {
        warpJob = null
        warpRuntime = null
    }

    suspend fun pollTelemetry(): NativeRuntimeSnapshot? = runCatching { warpRuntime?.pollTelemetry() }.getOrNull()
}

internal open class WarpRuntimeSupervisorFactory
    @Inject
    constructor(
        private val warpFactory: RipDpiWarpFactory,
    ) {
        open fun create(
            scope: CoroutineScope,
            dispatcher: CoroutineDispatcher,
        ): WarpRuntimeSupervisor =
            WarpRuntimeSupervisor(
                scope = scope,
                dispatcher = dispatcher,
                warpFactory = warpFactory,
            )
    }
