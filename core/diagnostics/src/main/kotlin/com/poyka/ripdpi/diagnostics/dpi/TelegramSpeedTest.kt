package com.poyka.ripdpi.diagnostics.dpi

import com.poyka.ripdpi.diagnostics.ProbeDetail
import com.poyka.ripdpi.diagnostics.ProbeResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.produce
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okio.BufferedSink
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.TimeUnit

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
    SLOW,
    BLOCKED,
    ERROR,
}

data class ProbeStats(
    val status: TelegramProbeStatus,
    val bytesTotal: Long,
    val durationMs: Long,
    val avgBps: Long,
    val peakBps: Long,
    val dropAtSec: Int? = null,
)

data class DcReachability(
    val label: String,
    val ip: String,
    val reachable: Boolean,
    val rttMs: Long?,
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
                listOf(
                    ProbeDetail("verdict", verdict.name.lowercase()),
                    ProbeDetail("downloadStatus", download.status.name.lowercase()),
                    ProbeDetail("downloadBytes", download.bytesTotal.toString()),
                    ProbeDetail("downloadDurationMs", download.durationMs.toString()),
                    ProbeDetail("downloadAvgBps", download.avgBps.toString()),
                    ProbeDetail("downloadPeakBps", download.peakBps.toString()),
                    ProbeDetail("downloadDropAtSec", download.dropAtSec?.toString() ?: "none"),
                    ProbeDetail("uploadStatus", upload.status.name.lowercase()),
                    ProbeDetail("uploadBytes", upload.bytesTotal.toString()),
                    ProbeDetail("uploadDurationMs", upload.durationMs.toString()),
                    ProbeDetail("uploadAvgBps", upload.avgBps.toString()),
                    ProbeDetail("uploadPeakBps", upload.peakBps.toString()),
                    ProbeDetail("uploadDropAtSec", upload.dropAtSec?.toString() ?: "none"),
                    ProbeDetail("dcReachable", dcResults.count { dc -> dc.reachable }.toString()),
                    ProbeDetail("dcTotal", dcResults.size.toString()),
                    ProbeDetail("dcResults", dcResults.joinToString("|") { dc -> dc.toDetailValue() }),
                ),
        )
}

sealed interface TelegramTestProgress {
    data class Completed(
        val result: TelegramTestResult,
    ) : TelegramTestProgress
}

fun interface TelegramTransferClient {
    fun transfer(): Flow<Int>
}

class TelegramSpeedTest(
    private val downloadClient: TelegramTransferClient = OkHttpTelegramDownloadClient(),
    private val uploadClient: TelegramTransferClient = OkHttpTelegramUploadClient(),
    private val dcPinger: TelegramDcPinger = TelegramDcPinger(),
    private val clockMs: () -> Long = System::currentTimeMillis,
) {
    suspend fun run(): TelegramTestResult =
        coroutineScope {
            val results =
                awaitAll(
                    async { measureTransfer(DownloadBytes, downloadClient.transfer()) },
                    async { measureTransfer(UploadBytes, uploadClient.transfer()) },
                    async { dcPinger.pingAll() },
                )
            val download = results[0] as ProbeStats
            val upload = results[1] as ProbeStats

            @Suppress("UNCHECKED_CAST")
            val dcResults = results[2] as List<DcReachability>
            TelegramTestResult(
                verdict = classifyVerdict(download, upload, dcResults),
                download = download,
                upload = upload,
                dcResults = dcResults,
            )
        }

    fun runWithProgress(): Flow<TelegramTestProgress> =
        flow {
            emit(TelegramTestProgress.Completed(run()))
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun measureTransfer(
        expectedBytes: Long,
        chunks: Flow<Int>,
    ): ProbeStats =
        coroutineScope {
            val startMs = clockMs()
            var lastDataMs = startMs
            var bytesTotal = 0L
            var peakChunk = 0L
            val channel =
                produce {
                    chunks.collect { bytes -> send(bytes) }
                }
            val completed =
                withTimeoutOrNull(TotalTimeoutMs) {
                    while (true) {
                        val next =
                            withTimeoutOrNull(StallTimeoutMs) { channel.receiveCatching() }
                                ?: return@withTimeoutOrNull buildStats(
                                    status = TelegramProbeStatus.STALLED,
                                    bytesTotal = bytesTotal,
                                    startMs = startMs,
                                    peakBps = peakChunk,
                                    dropAtSec = ((lastDataMs - startMs) / 1_000).toInt(),
                                )
                        val chunk = next.getOrNull() ?: break
                        if (chunk > 0) {
                            bytesTotal += chunk
                            peakChunk = maxOf(peakChunk, chunk.toLong())
                            lastDataMs = clockMs()
                        }
                    }
                    buildStats(
                        status = classifyTransfer(bytesTotal, expectedBytes),
                        bytesTotal = bytesTotal,
                        startMs = startMs,
                        peakBps = peakChunk,
                    )
                }
            channel.cancel()
            completed ?: buildStats(
                status = TelegramProbeStatus.STALLED,
                bytesTotal = bytesTotal,
                startMs = startMs,
                peakBps = peakChunk,
                dropAtSec = ((lastDataMs - startMs) / 1_000).toInt(),
            )
        }

    private fun buildStats(
        status: TelegramProbeStatus,
        bytesTotal: Long,
        startMs: Long,
        peakBps: Long,
        dropAtSec: Int? = null,
    ): ProbeStats {
        val durationMs = maxOf(1, clockMs() - startMs)
        return ProbeStats(
            status = status,
            bytesTotal = bytesTotal,
            durationMs = durationMs,
            avgBps = bytesTotal * 1_000 / durationMs,
            peakBps = peakBps,
            dropAtSec = dropAtSec,
        )
    }

    private fun classifyTransfer(
        bytesTotal: Long,
        expectedBytes: Long,
    ): TelegramProbeStatus =
        when {
            bytesTotal == 0L -> TelegramProbeStatus.BLOCKED
            bytesTotal >= (expectedBytes * CompleteThreshold) -> TelegramProbeStatus.OK
            else -> TelegramProbeStatus.SLOW
        }

    private fun classifyVerdict(
        download: ProbeStats,
        upload: ProbeStats,
        dcResults: List<DcReachability>,
    ): TelegramTestVerdict {
        val reachable = dcResults.count { result -> result.reachable }
        return when {
            reachable == 0 &&
                (download.status == TelegramProbeStatus.BLOCKED || upload.status == TelegramProbeStatus.BLOCKED) -> {
                TelegramTestVerdict.BLOCKED
            }

            download.status in SlowStatuses || upload.status in SlowStatuses -> {
                TelegramTestVerdict.SLOW
            }

            reachable in 1 until dcResults.size -> {
                TelegramTestVerdict.PARTIAL
            }

            download.status == TelegramProbeStatus.OK &&
                upload.status == TelegramProbeStatus.OK &&
                reachable == dcResults.size -> {
                TelegramTestVerdict.OK
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
        private const val CompleteThreshold = 0.98
        private val SlowStatuses = setOf(TelegramProbeStatus.STALLED, TelegramProbeStatus.SLOW)
    }
}

private fun DcReachability.toDetailValue(): String =
    if (reachable) {
        "$label:ok:${rttMs ?: 0}ms"
    } else {
        "$label:fail"
    }

class TelegramDcPinger(
    private val socketConnector: TelegramDcSocketConnector = RealTelegramDcSocketConnector(),
    private val clockMs: () -> Long = System::currentTimeMillis,
) {
    fun pingAll(): List<DcReachability> =
        TelegramDcEndpoints.map { endpoint ->
            val start = clockMs()
            val rtt =
                runCatching {
                    socketConnector.connect(endpoint.ip, TelegramDcPort, DcPingTimeoutMs)
                }.getOrNull()
            DcReachability(
                label = endpoint.label,
                ip = endpoint.ip,
                reachable = rtt != null,
                rttMs = rtt ?: (clockMs() - start).takeIf { false },
            )
        }

    private data class TelegramDcEndpoint(
        val label: String,
        val ip: String,
    )

    companion object {
        private const val TelegramDcPort = 443
        private const val DcPingTimeoutMs = 5_000
        private val TelegramDcEndpoints =
            listOf(
                TelegramDcEndpoint("DC1", "149.154.175.53"),
                TelegramDcEndpoint("DC2", "149.154.167.51"),
                TelegramDcEndpoint("DC3", "149.154.175.100"),
                TelegramDcEndpoint("DC4", "149.154.167.91"),
                TelegramDcEndpoint("DC5", "91.108.56.130"),
            )
    }
}

fun interface TelegramDcSocketConnector {
    fun connect(
        ip: String,
        port: Int,
        timeoutMs: Int,
    ): Long?
}

private class RealTelegramDcSocketConnector(
    private val clockMs: () -> Long = System::currentTimeMillis,
) : TelegramDcSocketConnector {
    override fun connect(
        ip: String,
        port: Int,
        timeoutMs: Int,
    ): Long? {
        val start = clockMs()
        Socket().use { socket ->
            socket.connect(InetSocketAddress(ip, port), timeoutMs)
        }
        return clockMs() - start
    }
}

class OkHttpTelegramDownloadClient(
    private val client: OkHttpClient = defaultClient(),
    private val url: String = "https://telegram.org/img/Telegram200million.png",
) : TelegramTransferClient {
    override fun transfer(): Flow<Int> =
        channelFlow {
            withContext(Dispatchers.IO) {
                val request =
                    Request
                        .Builder()
                        .url(url)
                        .get()
                        .build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) throw IOException("download http ${response.code}")
                    val buffer = ByteArray(64 * 1024)
                    response.body.byteStream().use { input ->
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            trySend(read)
                        }
                    }
                }
            }
        }
}

class OkHttpTelegramUploadClient(
    private val client: OkHttpClient = defaultClient(),
    private val url: String = "https://149.154.167.220:443/upload",
) : TelegramTransferClient {
    override fun transfer(): Flow<Int> =
        channelFlow {
            withContext(Dispatchers.IO) {
                val body =
                    CountingZeroRequestBody(
                        totalBytes = TelegramSpeedTest.UploadBytes,
                        chunkBytes = 16 * 1024,
                        onChunk = { bytes -> trySend(bytes) },
                    )
                val request =
                    Request
                        .Builder()
                        .url(url)
                        .post(body)
                        .build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) throw IOException("upload http ${response.code}")
                }
            }
        }
}

private class CountingZeroRequestBody(
    private val totalBytes: Long,
    private val chunkBytes: Int,
    private val onChunk: (Int) -> Unit,
) : RequestBody() {
    override fun contentType() = "application/octet-stream".toMediaType()

    override fun contentLength(): Long = totalBytes

    override fun writeTo(sink: BufferedSink) {
        val chunk = ByteArray(chunkBytes)
        var remaining = totalBytes
        while (remaining > 0) {
            val toWrite = minOf(chunk.size.toLong(), remaining).toInt()
            sink.write(chunk, 0, toWrite)
            onChunk(toWrite)
            remaining -= toWrite
        }
    }
}

private fun defaultClient(): OkHttpClient =
    OkHttpClient
        .Builder()
        .connectTimeout(TelegramSpeedTest.TotalTimeoutMs, TimeUnit.MILLISECONDS)
        .readTimeout(TelegramSpeedTest.StallTimeoutMs, TimeUnit.MILLISECONDS)
        .writeTimeout(TelegramSpeedTest.StallTimeoutMs, TimeUnit.MILLISECONDS)
        .callTimeout(TelegramSpeedTest.TotalTimeoutMs, TimeUnit.MILLISECONDS)
        .build()
