package com.poyka.ripdpi.diagnostics.dpich

import com.poyka.ripdpi.diagnostics.dpi.DpiAssetLoader
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeout
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit
import kotlin.random.Random

sealed class PtVerdict {
    data class PtOk(
        val detail: String,
    ) : PtVerdict()

    data class PtBridgeBlocked(
        val reason: String,
    ) : PtVerdict()

    data class PtBrokerBlocked(
        val reason: String,
    ) : PtVerdict()

    data class PtFrontBlocked(
        val reason: String,
    ) : PtVerdict()

    data class PtStunBlocked(
        val reason: String,
    ) : PtVerdict()

    data class PtError(
        val reason: String,
    ) : PtVerdict()
}

data class PtProbeTrace(
    val leg: String,
    val target: String,
    val ok: Boolean,
    val latencyMs: Long,
    val error: String? = null,
)

data class PluggableTransportResult(
    val obfs4: PtVerdict,
    val snowflake: PtVerdict,
    val meek: PtVerdict,
    val traces: List<PtProbeTrace>,
)

fun interface PtSubprobe {
    suspend fun run(timeoutMs: Long): PtProbeTrace
}

class PluggableTransportReachabilityProbe(
    private val obfs4Probe: PtSubprobe = Obfs4ReachabilityProbe(),
    private val snowflakeBrokerProbe: PtSubprobe = SnowflakeBrokerReachabilityProbe(),
    private val snowflakeStunProbe: PtSubprobe = SnowflakeStunReachabilityProbe(),
    private val meekProbe: PtSubprobe = MeekReachabilityProbe(),
) {
    suspend fun run(timeoutMs: Long = DefaultTimeoutMs): PluggableTransportResult =
        coroutineScope {
            val obfs4 = async { runLeg { obfs4Probe.run(timeoutMs) } }
            val snowflakeBroker = async { runLeg { snowflakeBrokerProbe.run(timeoutMs) } }
            val snowflakeStun = async { runLeg { snowflakeStunProbe.run(timeoutMs) } }
            val meek = async { runLeg { meekProbe.run(timeoutMs) } }

            val obfs4Trace = obfs4.await()
            val brokerTrace = snowflakeBroker.await()
            val stunTrace = snowflakeStun.await()
            val meekTrace = meek.await()
            PluggableTransportResult(
                obfs4 = obfs4Trace.toObfs4Verdict(),
                snowflake = brokerTrace.toSnowflakeVerdict(stunTrace),
                meek = meekTrace.toMeekVerdict(),
                traces = listOf(obfs4Trace, brokerTrace, stunTrace, meekTrace),
            )
        }

    private suspend fun runLeg(block: suspend () -> PtProbeTrace): PtProbeTrace =
        runCatching {
            block()
        }.getOrElse { error ->
            error.throwIfCancellation()
            PtProbeTrace(
                leg = "unknown",
                target = "unknown",
                ok = false,
                latencyMs = 0,
                error = error.message ?: error.javaClass.simpleName,
            )
        }

    private fun PtProbeTrace.toObfs4Verdict(): PtVerdict =
        if (ok) {
            PtVerdict.PtOk(target)
        } else if (leg == UnknownLeg) {
            PtVerdict.PtError(error.orEmpty())
        } else {
            PtVerdict.PtBridgeBlocked(error.orEmpty())
        }

    private fun PtProbeTrace.toSnowflakeVerdict(stun: PtProbeTrace): PtVerdict =
        when {
            leg == UnknownLeg -> PtVerdict.PtError(error.orEmpty())
            !ok -> PtVerdict.PtBrokerBlocked(error.orEmpty())
            stun.leg == UnknownLeg -> PtVerdict.PtError(stun.error.orEmpty())
            !stun.ok -> PtVerdict.PtStunBlocked(stun.error.orEmpty())
            else -> PtVerdict.PtOk("$target + ${stun.target}")
        }

    private fun PtProbeTrace.toMeekVerdict(): PtVerdict =
        if (ok) {
            PtVerdict.PtOk(target)
        } else if (leg == UnknownLeg) {
            PtVerdict.PtError(error.orEmpty())
        } else {
            PtVerdict.PtFrontBlocked(error.orEmpty())
        }

    private companion object {
        private const val DefaultTimeoutMs = 8_000L
        private const val UnknownLeg = "unknown"
    }
}

class Obfs4ReachabilityProbe(
    private val bridges: List<PtEndpoint> = DefaultObfs4Bridges,
    private val socketFactory: () -> Socket = { Socket() },
    private val randomBytes: (Int) -> ByteArray = { size -> Random.nextBytes(size) },
) : PtSubprobe {
    override suspend fun run(timeoutMs: Long): PtProbeTrace =
        withTimeout(timeoutMs) {
            val startedAt = System.currentTimeMillis()
            val errors = mutableListOf<String>()
            bridges.forEach { bridge ->
                val trace = bridge.tryTcpHandshake(timeoutMs)
                if (trace.ok) return@withTimeout trace.copy(latencyMs = System.currentTimeMillis() - startedAt)
                errors += "${bridge.host}:${bridge.port}:${trace.error.orEmpty()}"
            }
            PtProbeTrace(
                Obfs4Leg,
                bridges.joinToString {
                    it.host
                },
                false,
                System.currentTimeMillis() - startedAt,
                errors.joinToString(),
            )
        }

    private fun PtEndpoint.tryTcpHandshake(timeoutMs: Long): PtProbeTrace {
        val startedAt = System.currentTimeMillis()
        return try {
            socketFactory().use { socket ->
                socket.soTimeout = timeoutMs.toInt()
                socket.connect(InetSocketAddress(host, port), timeoutMs.toInt())
                socket.getOutputStream().write(randomBytes(MockHandshakeBytes))
                socket.getOutputStream().flush()
                val response = socket.getInputStream().read()
                PtProbeTrace(Obfs4Leg, "$host:$port", response >= 0, System.currentTimeMillis() - startedAt)
            }
        } catch (error: IOException) {
            PtProbeTrace(Obfs4Leg, "$host:$port", false, System.currentTimeMillis() - startedAt, error.message)
        }
    }

    private companion object {
        private const val Obfs4Leg = "obfs4"
        private const val MockHandshakeBytes = 32
    }
}

fun DpiAssetLoader.loadObfs4BridgeEndpoints(): List<PtEndpoint> = loadObfs4Bridges().mapNotNull(String::toPtEndpoint)

fun DpiAssetLoader.loadMeekFrontUrls(): List<String> = loadMeekFronts()

private fun String.toPtEndpoint(): PtEndpoint? {
    val (host, port) = trim().substringBefore(' ') to trim().substringAfterLast(':', missingDelimiterValue = "")
    return PtEndpoint(host = host.substringBeforeLast(':'), port = port.toIntOrNull() ?: return null)
}

class SnowflakeBrokerReachabilityProbe(
    private val brokerUrl: String = DefaultSnowflakeBrokerUrl,
    private val clientBuilder: (OkHttpClient.Builder.() -> Unit) -> OkHttpClient =
        { configure -> OkHttpClient.Builder().apply(configure).build() },
) : PtSubprobe {
    override suspend fun run(timeoutMs: Long): PtProbeTrace {
        val startedAt = System.currentTimeMillis()
        return try {
            val client =
                clientBuilder {
                    callTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                    connectTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                    readTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                }
            val request =
                Request
                    .Builder()
                    .url(brokerUrl)
                    .post("{}".toRequestBody(JsonMediaType))
                    .build()
            client.newCall(request).execute().use { response ->
                PtProbeTrace(
                    leg = SnowflakeBrokerLeg,
                    target = brokerUrl,
                    ok = response.code in BrokerReachableStatusRange,
                    latencyMs = System.currentTimeMillis() - startedAt,
                    error = response.takeUnless { it.code in BrokerReachableStatusRange }?.code?.toString(),
                )
            }
        } catch (error: IOException) {
            PtProbeTrace(SnowflakeBrokerLeg, brokerUrl, false, System.currentTimeMillis() - startedAt, error.message)
        }
    }

    private companion object {
        private const val SnowflakeBrokerLeg = "snowflake_broker"
        private const val DefaultSnowflakeBrokerUrl = "https://snowflake-broker.torproject.net/"
        private val BrokerReachableStatusRange = 200..499
        private val JsonMediaType = "application/json".toMediaType()
    }
}

class SnowflakeStunReachabilityProbe(
    private val endpoint: PtEndpoint = PtEndpoint(DefaultSnowflakeStunHost, DefaultSnowflakeStunPort),
    private val socketFactory: () -> DatagramSocket = { DatagramSocket() },
    private val transactionId: () -> ByteArray = { Random.nextBytes(StunTransactionIdBytes) },
) : PtSubprobe {
    override suspend fun run(timeoutMs: Long): PtProbeTrace {
        val startedAt = System.currentTimeMillis()
        return try {
            socketFactory().use { socket ->
                socket.soTimeout = timeoutMs.toInt()
                val request = stunBindingRequest(transactionId())
                socket.send(DatagramPacket(request, request.size, InetSocketAddress(endpoint.host, endpoint.port)))
                val response = DatagramPacket(ByteArray(StunResponseBytes), StunResponseBytes)
                socket.receive(response)
                PtProbeTrace(
                    leg = SnowflakeStunLeg,
                    target = "${endpoint.host}:${endpoint.port}",
                    ok = response.length >= StunHeaderBytes,
                    latencyMs = System.currentTimeMillis() - startedAt,
                )
            }
        } catch (_: SocketTimeoutException) {
            PtProbeTrace(
                SnowflakeStunLeg,
                endpoint.toString(),
                false,
                System.currentTimeMillis() - startedAt,
                "timeout",
            )
        } catch (error: IOException) {
            PtProbeTrace(
                SnowflakeStunLeg,
                endpoint.toString(),
                false,
                System.currentTimeMillis() - startedAt,
                error.message,
            )
        }
    }

    private fun stunBindingRequest(id: ByteArray): ByteArray = StunBindingRequestPrefix + id

    private companion object {
        private const val SnowflakeStunLeg = "snowflake_stun"
        private const val StunHeaderBytes = 20
        private const val StunResponseBytes = 512
        private const val StunTransactionIdBytes = 12
        private val StunBindingRequestPrefix =
            byteArrayOf(
                0x00,
                0x01,
                0x00,
                0x00,
                0x21,
                0x12,
                0xA4.toByte(),
                0x42,
            )
    }
}

class MeekReachabilityProbe(
    private val fronts: List<String> = DefaultMeekFronts,
    private val clientBuilder: (OkHttpClient.Builder.() -> Unit) -> OkHttpClient =
        { configure -> OkHttpClient.Builder().apply(configure).build() },
) : PtSubprobe {
    override suspend fun run(timeoutMs: Long): PtProbeTrace =
        coroutineScope {
            val startedAt = System.currentTimeMillis()
            val results =
                fronts
                    .map { front -> async { probeFront(front, timeoutMs, startedAt) } }
                    .map { deferred -> deferred.await() }
            results.firstOrNull { result -> result.ok }
                ?: PtProbeTrace(
                    MeekLeg,
                    fronts.joinToString(),
                    false,
                    System.currentTimeMillis() - startedAt,
                    results
                        .joinToString {
                            it.error.orEmpty()
                        },
                )
        }

    private fun probeFront(
        front: String,
        timeoutMs: Long,
        startedAt: Long,
    ): PtProbeTrace =
        try {
            val client =
                clientBuilder {
                    callTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                    connectTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                    readTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                }
            val request =
                Request
                    .Builder()
                    .url(front)
                    .head()
                    .build()
            client.newCall(request).execute().use { response ->
                PtProbeTrace(
                    MeekLeg,
                    front,
                    response.code in SuccessStatusRange,
                    System.currentTimeMillis() - startedAt,
                    response.code.toString(),
                )
            }
        } catch (error: IOException) {
            PtProbeTrace(MeekLeg, front, false, System.currentTimeMillis() - startedAt, error.message)
        }

    private companion object {
        private const val MeekLeg = "meek"
        private val SuccessStatusRange = 200..399
    }
}

data class PtEndpoint(
    val host: String,
    val port: Int,
) {
    override fun toString(): String = "$host:$port"
}

private fun Throwable.throwIfCancellation() {
    if (this is CancellationException) {
        throw this
    }
}

private const val DefaultSnowflakeStunHost = "stun.l.google.com"
private const val DefaultSnowflakeStunPort = 19302

private val DefaultObfs4Bridges =
    listOf(
        PtEndpoint("192.0.2.10", 443),
        PtEndpoint("192.0.2.11", 443),
        PtEndpoint("192.0.2.12", 443),
    )

private val DefaultMeekFronts =
    listOf(
        "https://ajax.aspnetcdn.com/",
        "https://www.fastly.com/",
        "https://d2zfqthxsdq309.cloudfront.net/",
    )
