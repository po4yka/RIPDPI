package com.poyka.ripdpi.diagnostics.dpi

import com.poyka.ripdpi.diagnostics.ProbeDetail
import com.poyka.ripdpi.diagnostics.ProbeResult
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ProtocolException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException

enum class TelegramTestVerdict {
    OK,
    SLOW,
    PARTIAL,
    BLOCKED,
    ERROR,
}

enum class TelegramProbeStatus {
    OK,
    STALLED,
    INCOMPLETE,
    ERROR,
}

enum class TelegramTransferFailure {
    HTTP_REJECTED,
    TIMEOUT,
    DNS,
    TLS,
    PROTOCOL,
    IO,
    LOCAL_POLICY,
}

data class ProbeStats(
    val status: TelegramProbeStatus,
    /** Payload bytes read by GET or accepted by completed local POST sink writes. */
    val bytesTotal: Long,
    val durationMs: Long,
    val avgBps: Long,
    val peakBps: Long,
    val dropAtSec: Int? = null,
    val httpStatus: Int? = null,
    val failure: TelegramTransferFailure? = null,
    val firstPayloadDelayMs: Long? = null,
    /** GET setup to response headers or final local POST write to response headers. */
    val responseDelayMs: Long? = null,
    val payloadTargetBytes: Long? = null,
)

data class TelegramTestResult(
    val verdict: TelegramTestVerdict,
    val download: ProbeStats,
    val upload: ProbeStats,
    val dcResults: List<DcReachability>,
) {
    fun toProbeResult(target: String = "telegram"): ProbeResult =
        ProbeResult(
            probeType = "telegram_availability",
            target = target,
            outcome = verdict.name.lowercase(),
            details =
                listOf(ProbeDetail("verdict", verdict.name.lowercase())) +
                    download.toDetails("download") +
                    upload.toDetails("upload") +
                    listOf(
                        ProbeDetail("dcReachable", dcResults.count { dc -> dc.reachable }.toString()),
                        ProbeDetail("dcTotal", dcResults.size.toString()),
                        ProbeDetail("dcResults", dcResults.joinToString("|") { dc -> dc.toDetailValue() }),
                    ),
        )
}

private fun ProbeStats.toDetails(prefix: String): List<ProbeDetail> =
    listOf(
        ProbeDetail("${prefix}Status", status.name.lowercase()),
        ProbeDetail("${prefix}Bytes", bytesTotal.toString()),
        ProbeDetail("${prefix}DurationMs", durationMs.toString()),
        ProbeDetail("${prefix}AvgBps", avgBps.toString()),
        ProbeDetail("${prefix}PeakBps", peakBps.toString()),
        ProbeDetail("${prefix}DropAtSec", dropAtSec?.toString() ?: "none"),
        ProbeDetail("${prefix}HttpStatus", httpStatus?.toString() ?: "none"),
        ProbeDetail("${prefix}Error", failure?.name?.lowercase() ?: "none"),
        ProbeDetail("${prefix}FirstPayloadDelayMs", firstPayloadDelayMs?.toString() ?: "none"),
        ProbeDetail("${prefix}ResponseDelayMs", responseDelayMs?.toString() ?: "none"),
        ProbeDetail("${prefix}PayloadTargetBytes", payloadTargetBytes?.toString() ?: "none"),
    )

enum class TelegramTransferKind {
    DOWNLOAD,
    UPLOAD,
}

sealed interface TelegramTestProgress {
    data class Transfer(
        val kind: TelegramTransferKind,
        val bytesTotal: Long,
        val elapsedMs: Long,
        val avgBps: Long,
    ) : TelegramTestProgress

    data class Completed(
        val result: TelegramTestResult,
    ) : TelegramTestProgress
}

fun interface TelegramTransferClient {
    suspend fun transfer(observer: TelegramTransferObserver)
}

interface TelegramTransferObserver {
    fun payloadStarted()

    fun transferred(byteCount: Int)

    fun responseHeaders(status: Int)
}

class TelegramSpeedTest(
    private val downloadClient: TelegramTransferClient = OkHttpTelegramDownloadClient(),
    private val uploadClient: TelegramTransferClient = OkHttpTelegramUploadClient(),
    private val dcPinger: TelegramDcPinger = TelegramDcPinger(),
    private val clockNs: () -> Long = System::nanoTime,
) {
    suspend fun run(): TelegramTestResult = runInternal(onProgress = null)

    private suspend fun runInternal(onProgress: ((TelegramTestProgress.Transfer) -> Unit)?): TelegramTestResult =
        coroutineScope {
            val download =
                async { measureTransfer(TelegramTransferKind.DOWNLOAD, DownloadBytes, downloadClient, onProgress) }
            val upload =
                async { measureTransfer(TelegramTransferKind.UPLOAD, UploadBytes, uploadClient, onProgress) }
            val peers = async { dcPinger.pingAll() }
            val downloadStats = download.await()
            val uploadStats = upload.await()
            val dcResults = peers.await()
            TelegramTestResult(
                classifyVerdict(downloadStats, uploadStats, dcResults),
                downloadStats,
                uploadStats,
                dcResults,
            )
        }

    fun runWithProgress(): Flow<TelegramTestProgress> =
        channelFlow {
            val result = runInternal { progress -> trySend(progress) }
            send(TelegramTestProgress.Completed(result))
        }

    private suspend fun measureTransfer(
        kind: TelegramTransferKind,
        expectedBytes: Long,
        client: TelegramTransferClient,
        onProgress: ((TelegramTestProgress.Transfer) -> Unit)?,
    ): ProbeStats {
        val sample = TelegramTransferSample(kind, clockNs, onProgress)
        val failure =
            try {
                val completed = withTimeoutOrNull(TotalTimeoutMs) { client.transfer(sample) } != null
                if (completed) sample.responseFailure() else TelegramTransferFailure.TIMEOUT
            } catch (error: IOException) {
                error.transferFailure()
            } catch (_: SecurityException) {
                TelegramTransferFailure.LOCAL_POLICY
            }
        return sample.stats(expectedBytes, failure)
    }

    private fun classifyVerdict(
        download: ProbeStats,
        upload: ProbeStats,
        dcResults: List<DcReachability>,
    ): TelegramTestVerdict {
        val complete = download.status == TelegramProbeStatus.OK && upload.status == TelegramProbeStatus.OK
        val reachable = dcResults.count { it.reachable }
        return when {
            complete && reachable > 0 && reachable == dcResults.size -> {
                TelegramTestVerdict.OK
            }

            download.httpStatus.isAccepted() || upload.httpStatus.isAccepted() || reachable > 0 -> {
                TelegramTestVerdict.PARTIAL
            }

            else -> {
                TelegramTestVerdict.ERROR
            }
        }
    }

    companion object {
        const val DownloadBytes = 30_970_000L
        const val UploadBytes = 10L * 1024L * 1024L
        const val StallTimeoutMs = 10_000L
        const val TotalTimeoutMs = 60_000L
    }
}

private class TelegramTransferSample(
    private val kind: TelegramTransferKind,
    private val clockNs: () -> Long,
    private val onProgress: ((TelegramTestProgress.Transfer) -> Unit)?,
) : TelegramTransferObserver {
    private val startNs = clockNs()
    private var payloadStartNs: Long? = null
    private var lastDataNs: Long? = null
    private var responseNs: Long? = null
    private var bytesTotal = 0L
    private var windowStartNs = startNs
    private var windowStartBytes = 0L
    private var peakBps = 0L

    var httpStatus: Int? = null
        private set

    override fun payloadStarted() {
        val now = clockNs()
        payloadStartNs = now
        windowStartNs = now
    }

    override fun transferred(byteCount: Int) {
        val now = clockNs()
        bytesTotal += byteCount
        lastDataNs = now
        val windowNs = now - windowStartNs
        if (windowNs >= MinimumPeakWindowNs) {
            peakBps = maxOf(peakBps, bytesPerSecond(bytesTotal - windowStartBytes, windowNs))
            windowStartNs = now
            windowStartBytes = bytesTotal
        }
        val elapsedNs = payloadDurationNs()
        onProgress?.invoke(
            TelegramTestProgress.Transfer(kind, bytesTotal, elapsedNs.millis(), bytesPerSecond(bytesTotal, elapsedNs)),
        )
    }

    override fun responseHeaders(status: Int) {
        httpStatus = status
        responseNs = clockNs()
    }

    fun responseFailure(): TelegramTransferFailure? =
        when {
            httpStatus == null -> {
                TelegramTransferFailure.PROTOCOL
            }

            !httpStatus.isAccepted() -> {
                TelegramTransferFailure.HTTP_REJECTED
            }

            kind == TelegramTransferKind.DOWNLOAD && httpStatus != HttpFullResponse -> {
                TelegramTransferFailure.PROTOCOL
            }

            else -> {
                null
            }
        }

    fun stats(
        expectedBytes: Long,
        failure: TelegramTransferFailure?,
    ): ProbeStats {
        val durationNs = payloadDurationNs()
        val status =
            when {
                failure == TelegramTransferFailure.TIMEOUT -> TelegramProbeStatus.STALLED
                failure != null -> TelegramProbeStatus.ERROR
                bytesTotal < expectedBytes -> TelegramProbeStatus.INCOMPLETE
                else -> TelegramProbeStatus.OK
            }
        val average = bytesPerSecond(bytesTotal, durationNs)
        val responseStartNs = if (kind == TelegramTransferKind.UPLOAD) lastDataNs else startNs
        return ProbeStats(
            status = status,
            bytesTotal = bytesTotal,
            durationMs = durationNs.millis(),
            avgBps = average,
            peakBps = maxOf(average, peakBps),
            dropAtSec =
                lastDataNs?.takeIf { status == TelegramProbeStatus.STALLED }?.let {
                    TimeUnit.NANOSECONDS.toSeconds(it - startNs).toInt()
                },
            httpStatus = httpStatus,
            failure = failure,
            firstPayloadDelayMs = payloadStartNs?.let { (it - startNs).millis() },
            responseDelayMs = responseStartNs?.let { from -> responseNs?.let { (it - from).millis() } },
            payloadTargetBytes = expectedBytes,
        )
    }

    private fun payloadDurationNs(): Long = payloadStartNs?.let { start -> lastDataNs?.let { it - start } } ?: 0

    private fun Long.millis(): Long = TimeUnit.NANOSECONDS.toMillis(this)

    private fun bytesPerSecond(
        bytes: Long,
        durationNs: Long,
    ): Long = if (durationNs >= MinimumRateDurationNs) bytes * NanosecondsPerSecond / durationNs else 0

    private companion object {
        const val MinimumRateDurationNs = 1_000_000L
        const val MinimumPeakWindowNs = 250_000_000L
        const val NanosecondsPerSecond = 1_000_000_000L
    }
}

internal const val HttpFullResponse = 200

private fun Int?.isAccepted(): Boolean = this != null && this in 200..299

private fun IOException.transferFailure(): TelegramTransferFailure =
    when (this) {
        is InterruptedIOException -> TelegramTransferFailure.TIMEOUT
        is UnknownHostException -> TelegramTransferFailure.DNS
        is SSLException -> TelegramTransferFailure.TLS
        is ProtocolException -> TelegramTransferFailure.PROTOCOL
        else -> TelegramTransferFailure.IO
    }

private fun DcReachability.toDetailValue(): String =
    if (reachable) {
        "$label:ok:${rttMs ?: 0}ms"
    } else {
        "$label:fail:${failure?.name?.lowercase() ?: "unknown"}"
    }
