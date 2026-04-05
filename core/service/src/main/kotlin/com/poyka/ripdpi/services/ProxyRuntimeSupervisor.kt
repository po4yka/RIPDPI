package com.poyka.ripdpi.services

import com.poyka.ripdpi.core.RipDpiProxyFactory
import com.poyka.ripdpi.core.RipDpiProxyPreferences
import com.poyka.ripdpi.core.RipDpiProxyRuntime
import com.poyka.ripdpi.data.NativeNetworkSnapshotProvider
import com.poyka.ripdpi.data.NativeRuntimeSnapshot
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

internal class ProxyRuntimeSupervisor(
    private val scope: CoroutineScope,
    private val dispatcher: CoroutineDispatcher,
    private val ripDpiProxyFactory: RipDpiProxyFactory,
    private val networkSnapshotProvider: NativeNetworkSnapshotProvider,
    private val stopTimeoutMillis: Long = 5_000L,
) {
    private var proxyRuntime: RipDpiProxyRuntime? = null
    private var proxyJob: Job? = null

    val runtime: RipDpiProxyRuntime?
        get() = proxyRuntime

    suspend fun start(
        preferences: RipDpiProxyPreferences,
        onUnexpectedExit: suspend (Result<Int>) -> Unit,
    ) {
        check(proxyJob == null) { "Proxy fields not null" }

        val proxyInstance = ripDpiProxyFactory.create()
        proxyRuntime = proxyInstance

        val exitResult = CompletableDeferred<Result<Int>>()
        val job =
            scope.launch(dispatcher, start = CoroutineStart.UNDISPATCHED) {
                try {
                    exitResult.complete(runCatching { proxyInstance.startProxy(preferences) })
                } finally {
                    if (!exitResult.isCompleted) {
                        exitResult.complete(Result.failure(IllegalStateException("Proxy job cancelled")))
                    }
                }
            }
        proxyJob = job

        @Suppress("TooGenericExceptionCaught")
        try {
            proxyInstance.awaitReady()
        } catch (readinessError: Exception) {
            val proxyStartWasActive = job.isActive
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
            }
            throw resolveProxyStartupFailure(
                readinessError = readinessError,
                proxyStartWasActive = proxyStartWasActive,
                proxyStartResult = exitResult.await(),
            )
        }

        job.invokeOnCompletion {
            scope.launch(dispatcher) {
                onUnexpectedExit(exitResult.await())
            }
        }

        runCatching { proxyInstance.updateNetworkSnapshot(networkSnapshotProvider.capture()) }
    }

    suspend fun stop() {
        val proxyInstance = proxyRuntime
        if (proxyInstance == null) {
            proxyJob = null
            return
        }

        try {
            proxyInstance.stopProxy()
            withTimeoutOrNull(stopTimeoutMillis) {
                proxyJob?.join()
            }
        } finally {
            proxyJob = null
            proxyRuntime = null
        }
    }

    fun detach() {
        proxyJob = null
        proxyRuntime = null
    }

    suspend fun pollTelemetry(): NativeRuntimeSnapshot? = runCatching { proxyRuntime?.pollTelemetry() }.getOrNull()
}
