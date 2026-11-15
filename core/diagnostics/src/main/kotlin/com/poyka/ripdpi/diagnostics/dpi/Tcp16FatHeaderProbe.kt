package com.poyka.ripdpi.diagnostics.dpi

import com.poyka.ripdpi.data.diagnostics.DiagnosticsTlsClientState
import com.poyka.ripdpi.diagnostics.dpich.RandomHostHeaderGenerator
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.EOFException
import java.io.IOException
import java.io.InterruptedIOException
import java.net.SocketException
import java.net.SocketTimeoutException
import javax.net.ssl.SSLHandshakeException
import kotlin.math.roundToLong

class Tcp16FatHeaderProbe(
    private val paddingPool: RandomPaddingPool = RandomPaddingPool(),
    private val requestRunner: Tcp16RequestRunner = OkHttpTcp16RequestRunner(),
    private val tlsClientStateProvider: () -> DiagnosticsTlsClientState? = { null },
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val concurrency: Int = DefaultConcurrency,
) : Tcp16Probe {
    fun withConcurrency(concurrency: Int): Tcp16FatHeaderProbe =
        Tcp16FatHeaderProbe(
            paddingPool = paddingPool,
            requestRunner = requestRunner,
            tlsClientStateProvider = tlsClientStateProvider,
            dispatcher = dispatcher,
            concurrency = concurrency,
        )

    suspend fun run(
        targets: List<Tcp16Target>,
        responseTimeHints: Map<String, Long> = emptyMap(),
        randomHostname: Boolean = false,
    ): List<Tcp16ProbeResult> =
        coroutineScope {
            val semaphore = Semaphore(concurrency)
            targets
                .map { target ->
                    async {
                        semaphore.withPermit {
                            runTarget(target, responseTimeHints[target.id], randomHostname = randomHostname)
                        }
                    }
                }.awaitAll()
        }

    suspend fun runTarget(target: Tcp16Target): Tcp16ProbeResult = runTarget(target, responseTimeHintMs = null)

    override suspend fun runTarget(
        target: Tcp16Target,
        responseTimeHintMs: Long?,
    ): Tcp16ProbeResult = runTarget(target, responseTimeHintMs, randomHostname = false)

    suspend fun runTarget(
        target: Tcp16Target,
        responseTimeHintMs: Long?,
        randomHostname: Boolean = false,
    ): Tcp16ProbeResult =
        withContext(dispatcher) {
            val invalidReason = target.invalidReason()
            if (invalidReason != null) {
                return@withContext target.result(
                    alive = false,
                    end = Tcp16TrainEnd(Tcp16Verdict.INVALID_TARGET, invalidReason),
                )
            }
            if (target.transport == null || !requestRunner.supports(target)) {
                return@withContext target.result(
                    alive = false,
                    end = Tcp16TrainEnd(Tcp16Verdict.UNSUPPORTED, "TCP16 transport is unavailable for this endpoint"),
                )
            }
            val tracker = Tcp16ConnectionTracker()
            // execute() blocks and ignores coroutine cancellation, so a sibling cancels the in-flight call.
            val canceller =
                launch(start = CoroutineStart.UNDISPATCHED) {
                    try {
                        awaitCancellation()
                    } finally {
                        tracker.cancelActiveCall()
                    }
                }
            try {
                runRequestTrain(target, responseTimeHintMs, randomHostname, tracker)
            } finally {
                canceller.cancel()
                tracker.close()
            }
        }

    private suspend fun runRequestTrain(
        target: Tcp16Target,
        responseTimeHintMs: Long?,
        randomHostname: Boolean,
        tracker: Tcp16ConnectionTracker,
    ): Tcp16ProbeResult {
        val samples = mutableListOf<Long>()
        val requestedHosts = mutableListOf<String>()
        // Keep the authority constant for the entire connection.
        val requestedHost = if (randomHostname) RandomHostHeaderGenerator.next() else target.sni ?: target.ip
        var completedPaddingBytes = 0
        var httpStatus: Int? = null
        var end = Tcp16TrainEnd(Tcp16Verdict.OK)
        for (requestIndex in 0 until RequestCount) {
            currentCoroutineContext().ensureActive()
            requestedHosts += requestedHost
            val padding = if (requestIndex == 0) null else paddingPool.slice(PaddingBytes).trim()
            val request =
                Tcp16FatHeaderRequest(
                    requestIndex = requestIndex,
                    padding = padding,
                    timeoutMs = timeoutFor(responseTimeHintMs ?: samples.averageMillis()),
                    hostHeader = requestedHost,
                )
            tracker.beginRequest()
            val response =
                try {
                    requestRunner.execute(target, request, tracker)
                } catch (error: IOException) {
                    currentCoroutineContext().ensureActive()
                    end = tracker.failure(error, completedPaddingBytes)
                    break
                }
            currentCoroutineContext().ensureActive()
            httpStatus = response.statusCode
            end = response.trainEnd(tracker.connectionCount, requestIndex)
            if (response.statusCode in 200..299 && tracker.connectionCount <= 1) {
                samples += response.elapsedMs
                completedPaddingBytes += padding?.length ?: 0
            }
            if (end.verdict != Tcp16Verdict.OK) break
        }
        return target.result(
            alive = tracker.connectionCount > 0 || httpStatus != null,
            end = end,
            completedPaddingBytes = completedPaddingBytes,
            meanResponseMs = samples.averageMillis(),
            httpStatus = httpStatus,
            connectionCount = tracker.connectionCount,
            requestedHosts = requestedHosts.takeIf { randomHostname },
        )
    }

    private fun Tcp16FatHeaderResponse.trainEnd(
        connectionCount: Int,
        requestIndex: Int,
    ): Tcp16TrainEnd =
        when {
            connectionCount > 1 -> Tcp16TrainEnd(Tcp16Verdict.INVALID_RECONNECTED)
            statusCode !in 200..299 -> Tcp16TrainEnd(Tcp16Verdict.HTTP_REJECTED, "HTTP $statusCode")
            connectionClose && requestIndex < RequestCount - 1 -> Tcp16TrainEnd(Tcp16Verdict.SERVER_CLOSED)
            else -> Tcp16TrainEnd(Tcp16Verdict.OK)
        }

    private fun timeoutFor(responseTimeMs: Long?): Long {
        val bounded = (responseTimeMs ?: DefaultTimeoutBaseMs).coerceIn(MinimumResponseHintMs, MaxTimeoutMs)
        return (bounded * TimeoutMultiplier + TimeoutSafetyMarginMs).coerceIn(MinTimeoutMs, MaxTimeoutMs)
    }

    private fun Tcp16ConnectionTracker.failure(
        error: IOException,
        completedPaddingBytes: Int,
    ): Tcp16TrainEnd {
        val verdict =
            when {
                connectionCount > 1 -> {
                    Tcp16Verdict.INVALID_RECONNECTED
                }

                error is SocketTimeoutException || (error is InterruptedIOException && error.message == "timeout") -> {
                    Tcp16Verdict.TIMED_OUT
                }

                stage == Tcp16Stage.CONNECT && connectStarts > 0 -> {
                    Tcp16Verdict.DEAD
                }

                stage == Tcp16Stage.READ_RESPONSE && completedPaddingBytes >= LateStageCutoffBytes &&
                    error.isPeerClose() -> {
                    Tcp16Verdict.PADDED_TRAIN_INTERRUPTED
                }

                else -> {
                    Tcp16Verdict.ERROR
                }
            }
        return Tcp16TrainEnd(verdict, error.tcp16ErrorLabel(), stage)
    }

    private fun Tcp16Target.result(
        alive: Boolean,
        end: Tcp16TrainEnd,
        completedPaddingBytes: Int = 0,
        meanResponseMs: Long? = null,
        httpStatus: Int? = null,
        connectionCount: Int = 0,
        requestedHosts: List<String>? = null,
    ): Tcp16ProbeResult =
        Tcp16ProbeResult(
            targetId = id,
            asn = asn,
            provider = provider,
            ip = ip,
            port = port,
            alive = alive,
            verdict = end.verdict,
            completedPaddingBytes = completedPaddingBytes,
            meanResponseMs = meanResponseMs,
            errorDetail = end.detail,
            httpStatus = httpStatus,
            failedStage = end.stage,
            connectionCount = connectionCount,
            tlsClientState = tlsClientStateProvider(),
            requestedHosts = requestedHosts,
        )

    companion object {
        private const val RequestCount = 16
        private const val PaddingBytes = 4 * 1024
        private const val LateStageCutoffBytes = 8 * 1_024
        private const val DefaultConcurrency = 15
        private const val MinimumResponseHintMs = 25L
        private const val DefaultTimeoutBaseMs = 500L
        private const val TimeoutMultiplier = 4L
        private const val TimeoutSafetyMarginMs = 1_000L
        private const val MinTimeoutMs = 1_500L
        private const val MaxTimeoutMs = 10_000L
    }
}

interface Tcp16Probe {
    suspend fun runTarget(
        target: Tcp16Target,
        responseTimeHintMs: Long?,
    ): Tcp16ProbeResult
}

private data class Tcp16TrainEnd(
    val verdict: Tcp16Verdict,
    val detail: String? = null,
    val stage: Tcp16Stage? = null,
)

private fun List<Long>.averageMillis(): Long? =
    if (isEmpty()) {
        null
    } else {
        average().roundToLong()
    }

private fun IOException.isPeerClose(): Boolean =
    this is EOFException || this is SocketException || cause is EOFException

private fun IOException.tcp16ErrorLabel(): String =
    when (this) {
        is SocketTimeoutException -> "timeout"
        is SSLHandshakeException -> "tls handshake failed"
        is SocketException -> message ?: "socket error"
        is EOFException -> "unexpected eof"
        else -> message ?: "i/o error"
    }
