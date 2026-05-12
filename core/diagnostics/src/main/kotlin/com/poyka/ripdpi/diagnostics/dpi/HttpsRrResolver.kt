package com.poyka.ripdpi.diagnostics.dpi

import android.net.DnsResolver
import android.net.Network
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.Base64
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class HttpsRrRecord(
    val priority: Int,
    val targetName: String,
    val echConfig: ByteArray?,
) {
    override fun equals(other: Any?): Boolean =
        other is HttpsRrRecord &&
            priority == other.priority &&
            targetName == other.targetName &&
            echConfig.contentEqualsNullable(other.echConfig)

    override fun hashCode(): Int = 31 * (31 * priority + targetName.hashCode()) + (echConfig?.contentHashCode() ?: 0)
}

fun interface HttpsRrQuery {
    suspend fun query(host: String): List<HttpsRrRecord>
}

class HttpsRrResolver(
    private val query: HttpsRrQuery = DohWireHttpsRrQuery(),
    private val fallbackQuery: HttpsRrQuery? = AndroidSystemHttpsRrQuery(),
) {
    suspend fun fetch(host: String): HttpsRrRecord? {
        val primary =
            runCatching {
                query.query(host).selectEchRecord()
            }.getOrNull()
        if (primary != null) return primary
        return fallbackQuery?.query(host)?.selectEchRecord()
    }

    private fun List<HttpsRrRecord>.selectEchRecord(): HttpsRrRecord? =
        filter { record -> !record.echConfig.isNullOrEmptyBytes() }.minByOrNull { record -> record.priority }
}

object Rfc9460HttpsRecordCodec {
    fun parseHttpsRdata(rdata: ByteArray): HttpsRrRecord? {
        if (rdata.size < ShortByteCount + 1) return null
        val priority = rdata.readU16(0)
        val target = rdata.readDnsName(ShortByteCount) ?: return null
        var offset = target.nextOffset
        var echConfig: ByteArray? = null
        while (offset < rdata.size) {
            if (offset + SvcParamHeaderLength > rdata.size) return null
            val key = rdata.readU16(offset)
            val length = rdata.readU16(offset + ShortByteCount)
            offset += SvcParamHeaderLength
            if (offset + length > rdata.size) return null
            if (key == SvcParamKeyEch) {
                echConfig = rdata.copyOfRange(offset, offset + length)
            }
            offset += length
        }
        return HttpsRrRecord(priority = priority, targetName = target.name, echConfig = echConfig)
    }

    private fun ByteArray.readDnsName(startOffset: Int): ParsedDnsName? {
        var offset = startOffset
        val labels = mutableListOf<String>()
        while (offset < size) {
            val length = this[offset].toInt() and ByteMask
            offset += 1
            if (length == 0) {
                return ParsedDnsName(name = labels.joinToString(".").ifBlank { "." }, nextOffset = offset)
            }
            if (length and CompressionPointerMask == CompressionPointerMask) return null
            if (length > MaxDnsLabelLength || offset + length > size) return null
            labels += copyOfRange(offset, offset + length).decodeToString()
            offset += length
        }
        return null
    }

    private data class ParsedDnsName(
        val name: String,
        val nextOffset: Int,
    )

    private const val ByteMask = 0xff
    private const val ShortByteCount = 2
    private const val SvcParamHeaderLength = 4
    private const val SvcParamKeyEch = 5
    private const val MaxDnsLabelLength = 63
    private const val CompressionPointerMask = 0xc0
}

class DohWireHttpsRrQuery(
    private val client: OkHttpClient =
        OkHttpClient
            .Builder()
            .connectTimeout(DefaultTimeoutMs, TimeUnit.MILLISECONDS)
            .readTimeout(DefaultTimeoutMs, TimeUnit.MILLISECONDS)
            .callTimeout(DefaultTimeoutMs * 2, TimeUnit.MILLISECONDS)
            .build(),
    private val endpoint: String = DefaultDohEndpoint,
) : HttpsRrQuery {
    override suspend fun query(host: String): List<HttpsRrRecord> =
        withContext(Dispatchers.IO) {
            val transactionId = DnsWireBuilder.randomTransactionId()
            val query = HttpsDnsWireBuilder.buildQuery(host, transactionId)
            val request =
                Request
                    .Builder()
                    .url(endpoint)
                    .post(query.toRequestBody(DnsMessageMediaType))
                    .header("Accept", DnsMessageContentType)
                    .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw IOException("DoH HTTPS RR query failed: ${response.code}")
                val bytes = response.body.bytes()
                HttpsDnsWireParser.parseResponse(bytes, transactionId)
            }
        }

    private companion object {
        private const val DefaultTimeoutMs = 3_000L
        private const val DefaultDohEndpoint = "https://cloudflare-dns.com/dns-query"
        private const val DnsMessageContentType = "application/dns-message"
        private val DnsMessageMediaType = DnsMessageContentType.toMediaType()
    }
}

class AndroidSystemHttpsRrQuery(
    private val network: Network? = null,
    private val resolverProvider: () -> DnsResolver = DnsResolver::getInstance,
    private val executor: Executor = Executor { command -> command.run() },
) : HttpsRrQuery {
    override suspend fun query(host: String): List<HttpsRrRecord> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return emptyList()
        val bytes =
            suspendCancellableCoroutine { continuation ->
                resolverProvider().rawQuery(
                    network,
                    host,
                    DnsResolver.CLASS_IN,
                    HttpsDnsWireBuilder.HttpsRecordType,
                    DnsResolver.FLAG_EMPTY,
                    executor,
                    null,
                    object : DnsResolver.Callback<ByteArray> {
                        override fun onAnswer(
                            answer: ByteArray,
                            rcode: Int,
                        ) {
                            if (rcode == 0) {
                                continuation.resume(answer)
                            } else {
                                continuation.resume(ByteArray(0))
                            }
                        }

                        override fun onError(error: DnsResolver.DnsException) {
                            continuation.resumeWithException(error)
                        }
                    },
                )
            }
        return HttpsDnsWireParser.parseResponse(bytes)
    }
}

private object HttpsDnsWireParser {
    fun parseResponse(
        bytes: ByteArray,
        transactionId: Int? = null,
    ): List<HttpsRrRecord> =
        runCatching {
            if (bytes.size < DnsHeaderLength) return emptyList()
            if (transactionId != null && bytes.readU16(0) != transactionId) return emptyList()
            val flags = bytes.readU16(2)
            if (flags and ResponseFlag == 0) return emptyList()
            if (flags and RcodeMask != 0) return emptyList()

            val questionCount = bytes.readU16(4)
            val answerCount = bytes.readU16(6)
            var offset = DnsHeaderLength
            repeat(questionCount) {
                offset = bytes.skipName(offset)
                offset += QuestionTrailerLength
            }

            val answers = mutableListOf<HttpsRrRecord>()
            repeat(answerCount) {
                offset = bytes.skipName(offset)
                if (offset + AnswerHeaderLength > bytes.size) return emptyList()
                val type = bytes.readU16(offset)
                val recordClass = bytes.readU16(offset + 2)
                val rdLength = bytes.readU16(offset + 8)
                offset += AnswerHeaderLength
                if (offset + rdLength > bytes.size) return emptyList()
                if (type == HttpsDnsWireBuilder.HttpsRecordType && recordClass == InClass) {
                    val rdata = bytes.copyOfRange(offset, offset + rdLength)
                    Rfc9460HttpsRecordCodec.parseHttpsRdata(rdata)?.let(answers::add)
                }
                offset += rdLength
            }
            answers
        }.getOrDefault(emptyList())

    private fun ByteArray.skipName(startOffset: Int): Int {
        var offset = startOffset
        var jumps = 0
        while (offset < size) {
            val length = this[offset].toInt() and ByteMask
            if (length == 0) return offset + 1
            if (length and CompressionPointerMask == CompressionPointerMask) {
                if (offset + 1 >= size) return size + 1
                return offset + 2
            }
            offset += 1 + length
            jumps += 1
            if (jumps > MaxDnsNameLabels) return size + 1
        }
        return size + 1
    }

    private const val ByteMask = 0xff
    private const val DnsHeaderLength = 12
    private const val QuestionTrailerLength = 4
    private const val AnswerHeaderLength = 10
    private const val ResponseFlag = 0x8000
    private const val RcodeMask = 0x000f
    private const val InClass = 1
    private const val MaxDnsNameLabels = 128
    private const val CompressionPointerMask = 0xc0
}

object HttpsDnsWireBuilder {
    const val HttpsRecordType = 65

    fun buildQuery(
        domain: String,
        transactionId: Int = DnsWireBuilder.randomTransactionId(),
    ): ByteArray {
        val labels = domain.trimEnd('.').split('.').filter(String::isNotBlank)
        val output = java.io.ByteArrayOutputStream()
        output.write((transactionId shr Byte.SIZE_BITS) and ByteMask)
        output.write(transactionId and ByteMask)
        output.write(0x01)
        output.write(0x00)
        output.write(0x00)
        output.write(0x01)
        output.write(0x00)
        output.write(0x00)
        output.write(0x00)
        output.write(0x00)
        output.write(0x00)
        output.write(0x00)
        labels.forEach { label ->
            require(label.length <= MaxDnsLabelLength) { "DNS label is too long: $label" }
            output.write(label.length)
            output.write(label.toByteArray(java.nio.charset.StandardCharsets.US_ASCII))
        }
        output.write(0x00)
        output.write((HttpsRecordType shr Byte.SIZE_BITS) and ByteMask)
        output.write(HttpsRecordType and ByteMask)
        output.write(0x00)
        output.write(InClass)
        return output.toByteArray()
    }

    private const val ByteMask = 0xff
    private const val InClass = 1
    private const val MaxDnsLabelLength = 63
}

enum class EchProbeVerdict {
    ECH_OK,
    ECH_REJECTED,
    ECH_NO_CONFIG,
    ECH_UNKNOWN_KEY,
    ECH_NETWORK_BLOCK,
}

data class EchProbeResult(
    val target: String,
    val verdict: EchProbeVerdict,
    val httpsRrFetched: Boolean,
    val echConfigBytesB64: String?,
    val tlsLatencyMs: Long?,
    val negotiatedEch: Boolean,
    val errorDetail: String?,
    val vanillaTlsOk: Boolean? = null,
) {
    val echSucceededWhenBaselineTlsFailed: Boolean = vanillaTlsOk == false && verdict == EchProbeVerdict.ECH_OK
}

data class EchTlsHandshakeResult(
    val negotiatedEch: Boolean,
    val latencyMs: Long?,
)

interface EchTlsHandshake {
    suspend fun connect(
        target: String,
        echConfig: ByteArray,
    ): EchTlsHandshakeResult
}

class EchReadinessProbe(
    private val resolver: HttpsRrResolver = HttpsRrResolver(),
    private val tlsHandshake: EchTlsHandshake = UnsupportedEchTlsHandshake,
    private val concurrency: Int = DefaultConcurrency,
) {
    suspend fun check(
        target: String,
        vanillaTlsOk: Boolean? = null,
    ): EchProbeResult {
        val record =
            try {
                resolver.fetch(target)
            } catch (error: Exception) {
                null
            }
        val echConfig = record?.echConfig
        if (echConfig.isNullOrEmptyBytes()) {
            return EchProbeResult(
                target = target,
                verdict = EchProbeVerdict.ECH_NO_CONFIG,
                httpsRrFetched = false,
                echConfigBytesB64 = null,
                tlsLatencyMs = null,
                negotiatedEch = false,
                errorDetail = null,
                vanillaTlsOk = vanillaTlsOk,
            )
        }
        val configBytes = requireNotNull(echConfig)

        return try {
            val result = tlsHandshake.connect(target, configBytes)
            EchProbeResult(
                target = target,
                verdict = if (result.negotiatedEch) EchProbeVerdict.ECH_OK else EchProbeVerdict.ECH_REJECTED,
                httpsRrFetched = true,
                echConfigBytesB64 = Base64.getEncoder().encodeToString(configBytes),
                tlsLatencyMs = result.latencyMs,
                negotiatedEch = result.negotiatedEch,
                errorDetail = null,
                vanillaTlsOk = vanillaTlsOk,
            )
        } catch (error: Exception) {
            val verdict = classifyEchFailure(error)
            EchProbeResult(
                target = target,
                verdict = verdict,
                httpsRrFetched = true,
                echConfigBytesB64 = Base64.getEncoder().encodeToString(configBytes),
                tlsLatencyMs = null,
                negotiatedEch = false,
                errorDetail = error.message ?: error.javaClass.simpleName,
                vanillaTlsOk = vanillaTlsOk,
            )
        }
    }

    suspend fun checkAll(
        targets: List<String>,
        vanillaTlsByTarget: Map<String, Boolean> = emptyMap(),
    ): List<EchProbeResult> =
        coroutineScope {
            val permits = Semaphore(concurrency.coerceIn(MinConcurrency, DefaultConcurrency))
            val checks =
                targets.map { target ->
                    async {
                        permits.withPermit {
                            check(
                                target = target,
                                vanillaTlsOk = vanillaTlsByTarget[target],
                            )
                        }
                    }
                }
            checks.awaitAll()
        }

    private fun classifyEchFailure(error: Exception): EchProbeVerdict {
        val message = (error.message ?: "").lowercase()
        return when {
            "ech_required" in message -> {
                EchProbeVerdict.ECH_UNKNOWN_KEY
            }

            "encrypted_client_hello" in message -> {
                EchProbeVerdict.ECH_REJECTED
            }

            error is SocketTimeoutException || "connect" in message || "timeout" in message -> {
                EchProbeVerdict.ECH_NETWORK_BLOCK
            }

            else -> {
                EchProbeVerdict.ECH_REJECTED
            }
        }
    }

    private companion object {
        private const val MinConcurrency = 1
        private const val DefaultConcurrency = 4
    }
}

private object UnsupportedEchTlsHandshake : EchTlsHandshake {
    override suspend fun connect(
        target: String,
        echConfig: ByteArray,
    ): EchTlsHandshakeResult = throw UnsupportedOperationException("ECH TLS handshake bridge is not available")
}

private fun ByteArray?.contentEqualsNullable(other: ByteArray?): Boolean =
    when {
        this == null && other == null -> true
        this == null || other == null -> false
        else -> contentEquals(other)
    }

private fun ByteArray?.isNullOrEmptyBytes(): Boolean = this == null || isEmpty()

private fun ByteArray.readU16(offset: Int): Int =
    ((this[offset].toInt() and 0xff) shl Byte.SIZE_BITS) or (this[offset + 1].toInt() and 0xff)
