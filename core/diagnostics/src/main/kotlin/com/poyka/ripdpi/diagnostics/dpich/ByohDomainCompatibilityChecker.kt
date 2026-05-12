package com.poyka.ripdpi.diagnostics.dpich

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.io.InterruptedIOException
import java.net.InetAddress
import java.util.concurrent.TimeUnit

data class ByohConfig(
    val dstIp: String,
    val urlPath: String = DefaultUrlPath,
    val domainList: List<String> = emptyList(),
    val timeoutMs: Long = DefaultTimeoutMs,
    val rangeTo: Long = DefaultRangeTo,
    val concurrency: Int = 1,
    val dstPort: Int = 443,
    val scheme: String = DefaultScheme,
) {
    val normalizedUrlPath: String = if (urlPath.startsWith("/")) urlPath else "/$urlPath"
}

sealed interface ByohProgress {
    data class Probing(
        val domain: String,
        val idx: Int,
        val total: Int,
    ) : ByohProgress

    data class Compatible(
        val domain: String,
        val bytesReceived: Long,
    ) : ByohProgress

    data class Incompatible(
        val domain: String,
        val reason: ByohIncompatibleReason,
        val bytesReceived: Long,
    ) : ByohProgress

    data class Completed(
        val stats: ByohStats,
    ) : ByohProgress
}

data class ByohStats(
    val total: Int,
    val compatible: Int,
    val incompatible: Int,
    val bytesReceived: Long,
)

data class ByohProbeResult(
    val domain: String,
    val compatible: Boolean,
    val bytesReceived: Long,
    val reason: ByohIncompatibleReason? = null,
)

enum class ByohIncompatibleReason {
    PartialBody,
    Timeout,
    ConnectionError,
    InvalidResponse,
}

fun interface ByohDomainProbe {
    suspend fun probe(
        domain: String,
        config: ByohConfig,
    ): ByohProbeResult
}

class ByohDomainCompatibilityChecker(
    private val probe: ByohDomainProbe = OkHttpByohDomainProbe(),
) {
    fun run(config: ByohConfig): Flow<ByohProgress> {
        val domains = config.domainList.mapNotNull { domain -> domain.cleanDomain() }.distinct()
        return if (config.concurrency <= 1) {
            runSequential(config = config, domains = domains)
        } else {
            runConcurrent(config = config, domains = domains)
        }
    }

    private fun runSequential(
        config: ByohConfig,
        domains: List<String>,
    ): Flow<ByohProgress> =
        flow {
            val results = mutableListOf<ByohProbeResult>()
            domains.forEachIndexed { index, domain ->
                emit(ByohProgress.Probing(domain = domain, idx = index + 1, total = domains.size))
                val result = probe.probe(domain = domain, config = config)
                results += result
                emit(result.toProgress())
            }
            emit(ByohProgress.Completed(results.toStats(total = domains.size)))
        }

    private fun runConcurrent(
        config: ByohConfig,
        domains: List<String>,
    ): Flow<ByohProgress> =
        channelFlow {
            val results = mutableListOf<ByohProbeResult>()
            val semaphore = Semaphore(config.concurrency.coerceAtLeast(1))
            coroutineScope {
                domains
                    .mapIndexed { index, domain ->
                        async {
                            semaphore.withPermit {
                                send(ByohProgress.Probing(domain = domain, idx = index + 1, total = domains.size))
                                val result = probe.probe(domain = domain, config = config)
                                synchronized(results) {
                                    results += result
                                }
                                send(result.toProgress())
                            }
                        }
                    }.awaitAll()
            }
            val snapshot = synchronized(results) { results.toList() }
            send(ByohProgress.Completed(snapshot.toStats(total = domains.size)))
        }

    private fun ByohProbeResult.toProgress(): ByohProgress =
        if (compatible) {
            ByohProgress.Compatible(domain = domain, bytesReceived = bytesReceived)
        } else {
            ByohProgress.Incompatible(
                domain = domain,
                reason = reason ?: ByohIncompatibleReason.InvalidResponse,
                bytesReceived = bytesReceived,
            )
        }

    private fun List<ByohProbeResult>.toStats(total: Int): ByohStats =
        ByohStats(
            total = total,
            compatible = count { result -> result.compatible },
            incompatible = count { result -> !result.compatible },
            bytesReceived = sumOf { result -> result.bytesReceived },
        )

    private fun String.cleanDomain(): String? =
        trim()
            .lowercase()
            .takeIf { value -> value.isNotEmpty() }
}

class OkHttpByohDomainProbe(
    private val clientBuilder: (OkHttpClient.Builder.() -> Unit) -> OkHttpClient =
        { configure -> OkHttpClient.Builder().apply(configure).build() },
    private val dnsFactory: (ByohConfig) -> ByohDns = { config -> FixedByohDns(config.dstIp) },
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ByohDomainProbe {
    override suspend fun probe(
        domain: String,
        config: ByohConfig,
    ): ByohProbeResult =
        withContext(ioDispatcher) {
            runCatching {
                execute(domain = domain, config = config)
            }.getOrElse { error ->
                when (error) {
                    is CancellationException -> {
                        throw error
                    }

                    is InterruptedIOException -> {
                        ByohProbeResult(
                            domain = domain,
                            compatible = false,
                            bytesReceived = 0,
                            reason = ByohIncompatibleReason.Timeout,
                        )
                    }

                    is IOException -> {
                        ByohProbeResult(
                            domain = domain,
                            compatible = false,
                            bytesReceived = 0,
                            reason = ByohIncompatibleReason.ConnectionError,
                        )
                    }

                    else -> {
                        ByohProbeResult(
                            domain = domain,
                            compatible = false,
                            bytesReceived = 0,
                            reason = ByohIncompatibleReason.InvalidResponse,
                        )
                    }
                }
            }
        }

    private fun execute(
        domain: String,
        config: ByohConfig,
    ): ByohProbeResult {
        val client =
            clientBuilder {
                connectTimeout(config.timeoutMs, TimeUnit.MILLISECONDS)
                readTimeout(config.timeoutMs, TimeUnit.MILLISECONDS)
                callTimeout(config.timeoutMs, TimeUnit.MILLISECONDS)
                dns(OkHttpByohDns(dnsFactory(config)))
                hostnameVerifier { _, _ -> true }
            }
        val request =
            Request
                .Builder()
                .url(config.requestUrl(domain))
                .header("Host", config.hostHeader(domain))
                .header("Range", "bytes=0-${config.rangeTo - 1}")
                .get()
                .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                return ByohProbeResult(
                    domain = domain,
                    compatible = false,
                    bytesReceived = 0,
                    reason = ByohIncompatibleReason.InvalidResponse,
                )
            }
            val bytesReceived = response.body.byteStream().countUntil(config.rangeTo)
            return ByohProbeResult(
                domain = domain,
                compatible = bytesReceived >= config.rangeTo,
                bytesReceived = bytesReceived,
                reason = if (bytesReceived >= config.rangeTo) null else ByohIncompatibleReason.PartialBody,
            )
        }
    }

    private fun ByohConfig.requestUrl(domain: String): String = "$scheme://$domain${portSuffix()}$normalizedUrlPath"

    private fun ByohConfig.hostHeader(domain: String): String = if (usesDefaultPort()) domain else "$domain:$dstPort"

    private fun ByohConfig.portSuffix(): String = if (usesDefaultPort()) "" else ":$dstPort"

    private fun ByohConfig.usesDefaultPort(): Boolean =
        (scheme == "https" && dstPort == 443) || (scheme == "http" && dstPort == 80)
}

fun interface ByohDns {
    fun lookup(hostname: String): List<InetAddress>
}

private class FixedByohDns(
    private val dstIp: String,
) : ByohDns {
    override fun lookup(hostname: String): List<InetAddress> = listOf(InetAddress.getByName(dstIp))
}

private class OkHttpByohDns(
    private val delegate: ByohDns,
) : Dns {
    override fun lookup(hostname: String): List<InetAddress> = delegate.lookup(hostname)
}

object ByohCsvExporter {
    fun toCsv(results: List<ByohProbeResult>): String =
        buildString {
            appendLine("domain,compatible,bytes_received")
            results.forEach { result ->
                append(result.domain.csvCell())
                append(',')
                append(result.compatible)
                append(',')
                appendLine(result.bytesReceived)
            }
        }.trimEnd()

    private fun String.csvCell(): String =
        if (any { char -> char == ',' || char == '"' || char == '\n' || char == '\r' }) {
            "\"" + replace("\"", "\"\"") + "\""
        } else {
            this
        }
}

private fun java.io.InputStream.countUntil(limit: Long): Long {
    var total = 0L
    val buffer = ByteArray(8 * 1024)
    while (total < limit) {
        val maxRead = minOf(buffer.size.toLong(), limit - total).toInt()
        val read = read(buffer, 0, maxRead)
        if (read < 0) break
        total += read.toLong()
    }
    return total
}

private const val DefaultScheme = "https"
private const val DefaultUrlPath = "/1MB.bin"
private const val DefaultTimeoutMs = 5_000L
private const val DefaultRangeTo = 128 * 1024L
