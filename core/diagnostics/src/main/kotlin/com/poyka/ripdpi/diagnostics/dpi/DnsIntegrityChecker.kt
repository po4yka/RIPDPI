package com.poyka.ripdpi.diagnostics.dpi

import com.poyka.ripdpi.diagnostics.dpich.BootstrapVerdict
import com.poyka.ripdpi.diagnostics.dpich.DohBootstrapResult
import com.poyka.ripdpi.diagnostics.dpich.DohBootstrapSpoofingDetector
import com.poyka.ripdpi.serialization.RipDpiJson
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.TimeUnit
import kotlin.random.asKotlinRandom

object DnsWireBuilder {
    const val NXDOMAIN = "NXDOMAIN"
    const val PARSE_ERR = "PARSE_ERR"

    private val random = SecureRandom().asKotlinRandom()

    fun randomTransactionId(): Int = random.nextInt(0, TransactionIdBound)

    fun buildQuery(
        domain: String,
        transactionId: Int = randomTransactionId(),
    ): ByteArray {
        val labels = domain.trimEnd('.').split('.').filter(String::isNotBlank)
        val output = ByteArrayOutputStream()
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
            output.write(label.toByteArray(StandardCharsets.US_ASCII))
        }
        output.write(0x00)
        output.write(0x00)
        output.write(ARecordType)
        output.write(0x00)
        output.write(InClass)
        return output.toByteArray()
    }

    fun parseResponse(
        bytes: ByteArray,
        transactionId: Int,
    ): List<String> =
        runCatching {
            if (bytes.size < DnsHeaderLength) return listOf(PARSE_ERR)
            if (bytes.readU16(0) != transactionId) return listOf(PARSE_ERR)
            val flags = bytes.readU16(2)
            if (flags and ResponseFlag == 0) return listOf(PARSE_ERR)
            val rcode = flags and RcodeMask
            if (rcode == NxdomainRcode) return listOf(NXDOMAIN)
            if (rcode != 0) return listOf(PARSE_ERR)

            val questionCount = bytes.readU16(QuestionCountOffset)
            val answerCount = bytes.readU16(AnswerCountOffset)
            var offset = DnsHeaderLength
            repeat(questionCount) {
                offset = bytes.skipName(offset)
                offset += QuestionTrailerLength
            }

            val answers = mutableListOf<String>()
            repeat(answerCount) {
                offset = bytes.skipName(offset)
                if (offset + AnswerHeaderLength > bytes.size) return listOf(PARSE_ERR)
                val type = bytes.readU16(offset)
                val recordClass = bytes.readU16(offset + RecordClassOffset)
                val rdLength = bytes.readU16(offset + RDataLengthOffset)
                offset += AnswerHeaderLength
                if (offset + rdLength > bytes.size) return listOf(PARSE_ERR)
                if (type == ARecordType && recordClass == InClass && rdLength == Ipv4ByteCount) {
                    answers +=
                        bytes.copyOfRange(offset, offset + Ipv4ByteCount).joinToString(".") { byte ->
                            (byte.toInt() and ByteMask).toString()
                        }
                }
                offset += rdLength
            }
            answers.ifEmpty { listOf(PARSE_ERR) }
        }.getOrDefault(listOf(PARSE_ERR))

    private fun ByteArray.readU16(offset: Int): Int =
        ((this[offset].toInt() and ByteMask) shl Byte.SIZE_BITS) or (this[offset + 1].toInt() and ByteMask)

    private fun ByteArray.skipName(startOffset: Int): Int {
        var offset = startOffset
        var jumps = 0
        var resolvedOffset = size + 1
        var scanning = true
        while (scanning && offset < size) {
            val length = this[offset].toInt() and ByteMask
            when {
                length == 0 -> {
                    resolvedOffset = offset + 1
                    scanning = false
                }

                length and CompressionPointerMask == CompressionPointerMask -> {
                    resolvedOffset = if (offset + 1 >= size) size + 1 else offset + 2
                    scanning = false
                }

                else -> {
                    offset += 1 + length
                    jumps += 1
                    if (jumps > MaxDnsNameLabels) scanning = false
                }
            }
        }
        return resolvedOffset
    }

    private const val TransactionIdBound = 0x1_0000
    private const val ByteMask = 0xFF
    private const val DnsHeaderLength = 12
    private const val QuestionCountOffset = 4
    private const val AnswerCountOffset = 6
    private const val QuestionTrailerLength = 4
    private const val AnswerHeaderLength = 10
    private const val RecordClassOffset = 2
    private const val RDataLengthOffset = 8
    private const val ResponseFlag = 0x8000
    private const val RcodeMask = 0x000F
    private const val NxdomainRcode = 3
    private const val ARecordType = 1
    private const val InClass = 1
    private const val Ipv4ByteCount = 4
    private const val MaxDnsLabelLength = 63
    private const val MaxDnsNameLabels = 128
    private const val CompressionPointerMask = 0xC0
}

enum class DnsIntegrityVerdict {
    DNS_OK,
    FAKE_IP,
    DNS_SUBSTITUTION,
    DNS_INTERCEPTION,
    FAKE_NXDOMAIN,
    DOH_BLOCKED,
    UNKNOWN,
}

data class DnsIntegrityDomainResult(
    val domain: String,
    val verdict: DnsIntegrityVerdict,
    val udpRecords: List<String>,
    val dohJsonIps: Set<String>,
    val dohWireIps: Set<String>,
    val notes: List<String> = emptyList(),
) {
    val udpIps: Set<String> = udpRecords.filter(::isIpv4Literal).toSet()
    val dohIps: Set<String> = dohJsonIps + dohWireIps
}

data class DnsIntegrityResult(
    val domains: List<DnsIntegrityDomainResult>,
    val stubIps: Set<String>,
    val dohBlocked: Int,
    val doqResults: List<DoqProbeResult> = emptyList(),
    val dohBootstrapResults: List<DohBootstrapResult> = emptyList(),
)

class DnsIntegrityChecker(
    private val udpProbe: DnsUdpProbe = DatagramSocketDnsUdpProbe(),
    private val dohJsonProbe: DnsAddressProbe = DohJsonAddressProbe(),
    private val dohWireProbe: DnsAddressProbe = DohWireAddressProbe(),
    private val doqProbe: DoqIntegrityProbe? = null,
    private val dohBootstrapDetector: DohBootstrapSpoofingDetector? = null,
) {
    suspend fun check(domains: List<String>): DnsIntegrityResult {
        val dohBootstrapResults = runDohBootstrapDetector()
        val excludedDohHostnames = dohBootstrapResults.excludedDohHostnames()
        val results = domains.map { domain -> checkDomain(domain, excludedDohHostnames) }
        val doqResults = runDoqProbe(domains, results)
        val stubIps =
            results
                .flatMap { result -> result.udpIps }
                .groupingBy { ip -> ip }
                .eachCount()
                .filterValues { count -> count >= StubIpMinimumOccurrences }
                .keys
                .toSortedSet()
        return DnsIntegrityResult(
            domains = results,
            stubIps = stubIps,
            dohBlocked = results.count { result -> result.verdict == DnsIntegrityVerdict.DOH_BLOCKED },
            doqResults = doqResults,
            dohBootstrapResults = dohBootstrapResults,
        )
    }

    private suspend fun runDohBootstrapDetector(): List<DohBootstrapResult> =
        dohBootstrapDetector
            ?.checkAll()
            ?.values
            ?.toList()
            .orEmpty()

    private fun List<DohBootstrapResult>.excludedDohHostnames(): Set<String> =
        filter { result -> result.verdict != BootstrapVerdict.OK }
            .mapTo(linkedSetOf()) { result -> result.dohHostname }

    private suspend fun runDoqProbe(
        domains: List<String>,
        results: List<DnsIntegrityDomainResult>,
    ): List<DoqProbeResult> {
        val probe = doqProbe ?: return emptyList()
        val dohResults = results.associate { result -> result.domain to result.dohIps.toList() }
        return probe.run(domains, dohResults)
    }

    private suspend fun checkDomain(
        domain: String,
        excludedDohHostnames: Set<String>,
    ): DnsIntegrityDomainResult =
        coroutineScope {
            val udp = async { runProbe { udpProbe.resolveA(domain) } }
            val dohJson = async { runProbe { dohJsonProbe.resolveA(domain, excludedDohHostnames) } }
            val dohWire = async { runProbe { dohWireProbe.resolveA(domain, excludedDohHostnames) } }

            val udpRecords = udp.await().getOrDefault(emptyList())
            val dohJsonIps = dohJson.await().getOrDefault(emptySet())
            val dohWireIps = dohWire.await().getOrDefault(emptySet())
            classify(
                domain = domain,
                udpRecords = udpRecords,
                dohJsonIps = dohJsonIps,
                dohWireIps = dohWireIps,
            )
        }

    private fun classify(
        domain: String,
        udpRecords: List<String>,
        dohJsonIps: Set<String>,
        dohWireIps: Set<String>,
    ): DnsIntegrityDomainResult {
        val udpIps = udpRecords.filter(::isIpv4Literal).toSet()
        val dohIps = dohJsonIps + dohWireIps
        val verdict =
            when {
                dohIps.isEmpty() -> DnsIntegrityVerdict.DOH_BLOCKED
                udpIps.any(::isFakeIp) -> DnsIntegrityVerdict.FAKE_IP
                DnsWireBuilder.NXDOMAIN in udpRecords -> DnsIntegrityVerdict.FAKE_NXDOMAIN
                udpRecords.isEmpty() -> DnsIntegrityVerdict.DNS_INTERCEPTION
                udpIps.intersect(dohIps).isNotEmpty() -> DnsIntegrityVerdict.DNS_OK
                udpIps.isNotEmpty() -> DnsIntegrityVerdict.DNS_SUBSTITUTION
                else -> DnsIntegrityVerdict.UNKNOWN
            }
        return DnsIntegrityDomainResult(
            domain = domain,
            verdict = verdict,
            udpRecords = udpRecords,
            dohJsonIps = dohJsonIps,
            dohWireIps = dohWireIps,
            notes = verdict.notes(),
        )
    }

    private suspend fun <T> runProbe(block: suspend () -> T): Result<T> =
        try {
            Result.success(block())
        } catch (error: CancellationException) {
            throw error
        } catch (error: IOException) {
            Result.failure(error)
        } catch (error: SerializationException) {
            Result.failure(error)
        } catch (error: IllegalArgumentException) {
            Result.failure(error)
        }

    private suspend fun DnsAddressProbe.resolveA(
        domain: String,
        excludedDohHostnames: Set<String>,
    ): Set<String> =
        if (this is BootstrapFilterableDnsAddressProbe) {
            resolveA(domain, excludedDohHostnames)
        } else {
            resolveA(domain)
        }

    private fun DnsIntegrityVerdict.notes(): List<String> =
        when (this) {
            DnsIntegrityVerdict.DNS_OK -> emptyList()
            DnsIntegrityVerdict.FAKE_IP -> listOf("UDP resolver returned a 198.18/15 fake-IP answer")
            DnsIntegrityVerdict.DNS_SUBSTITUTION -> listOf("UDP and DoH answers have no shared A record")
            DnsIntegrityVerdict.DNS_INTERCEPTION -> listOf("UDP/53 failed while DoH returned A records")
            DnsIntegrityVerdict.FAKE_NXDOMAIN -> listOf("UDP returned NXDOMAIN while DoH resolved the domain")
            DnsIntegrityVerdict.DOH_BLOCKED -> listOf("Both DoH comparison methods failed")
            DnsIntegrityVerdict.UNKNOWN -> listOf("DNS comparison was inconclusive")
        }

    private companion object {
        private const val StubIpMinimumOccurrences = 2
    }
}

class DatagramSocketDnsUdpProbe(
    private val servers: List<String> = DefaultUdpDnsServers,
    private val timeoutMs: Int = DefaultUdpTimeoutMs,
    private val retries: Int = DefaultUdpRetries,
    private val retryDelayMs: Long = DefaultRetryDelayMs,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : DnsUdpProbe {
    override suspend fun resolveA(domain: String): List<String> =
        withContext(dispatcher) {
            servers.forEach { server ->
                repeat(retries + 1) { attempt ->
                    val records = runCatching { queryServer(domain, server) }.getOrNull()
                    if (!records.isNullOrEmpty() && DnsWireBuilder.PARSE_ERR !in records) return@withContext records
                    if (attempt < retries) delay(retryDelayMs)
                }
            }
            emptyList()
        }

    private fun queryServer(
        domain: String,
        server: String,
    ): List<String> {
        val transactionId = DnsWireBuilder.randomTransactionId()
        val query = DnsWireBuilder.buildQuery(domain, transactionId)
        DatagramSocket().use { socket ->
            socket.soTimeout = timeoutMs
            val address = InetAddress.getByName(server)
            socket.send(DatagramPacket(query, query.size, InetSocketAddress(address, DnsPort)))
            val buffer = ByteArray(MaxDnsPayloadBytes)
            val response = DatagramPacket(buffer, buffer.size)
            socket.receive(response)
            return DnsWireBuilder.parseResponse(buffer.copyOf(response.length), transactionId)
        }
    }

    private companion object {
        private const val DnsPort = 53
        private const val MaxDnsPayloadBytes = 4096
        private const val DefaultUdpTimeoutMs = 3_000
        private const val DefaultUdpRetries = 2
        private const val DefaultRetryDelayMs = 500L
    }
}

class DohJsonAddressProbe(
    private val client: OkHttpClient = defaultDnsHttpClient(),
    private val endpoints: List<String> = DefaultDohJsonEndpoints,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val json: Json = RipDpiJson,
) : BootstrapFilterableDnsAddressProbe {
    override suspend fun resolveA(domain: String): Set<String> = resolveA(domain, emptySet())

    override suspend fun resolveA(
        domain: String,
        excludedDohHostnames: Set<String>,
    ): Set<String> = withContext(dispatcher) { queryFirstEndpoint(domain, excludedDohHostnames) }

    private fun queryFirstEndpoint(
        domain: String,
        excludedDohHostnames: Set<String>,
    ): Set<String> =
        endpoints
            .filterNot { endpoint -> endpoint.dohHostname() in excludedDohHostnames }
            .firstNotNullOfOrNull { endpoint ->
                queryEndpoint(domain, endpoint).takeIf(Set<String>::isNotEmpty)
            }
            ?: emptySet()

    private fun queryEndpoint(
        domain: String,
        endpoint: String,
    ): Set<String> {
        val encoded = URLEncoder.encode(domain, StandardCharsets.UTF_8.name())
        val request =
            Request
                .Builder()
                .url("$endpoint?name=$encoded&type=A")
                .get()
                .header("Accept", "application/dns-json")
                .build()
        return client.newCall(request).execute().use { response ->
            if (response.isSuccessful) {
                parseJsonAnswers(response.body.string())
            } else {
                emptySet()
            }
        }
    }

    private fun parseJsonAnswers(payload: String): Set<String> =
        json
            .parseToJsonElement(payload)
            .jsonObject["Answer"]
            ?.jsonArray
            ?.mapNotNull { answer ->
                val item = answer.jsonObject
                item["data"]?.jsonPrimitive?.content?.takeIf(::isIpv4Literal)
            }?.toCollection(linkedSetOf())
            ?: emptySet()
}

class DohWireAddressProbe(
    private val client: OkHttpClient = defaultDnsHttpClient(),
    private val endpoints: List<String> = DefaultDohWireEndpoints,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val timeoutMs: Long = DefaultDohWireTimeoutMs,
) : BootstrapFilterableDnsAddressProbe {
    override suspend fun resolveA(domain: String): Set<String> = resolveA(domain, emptySet())

    override suspend fun resolveA(
        domain: String,
        excludedDohHostnames: Set<String>,
    ): Set<String> =
        withTimeout(timeoutMs) { withContext(dispatcher) { queryFirstEndpoint(domain, excludedDohHostnames) } }

    private fun queryFirstEndpoint(
        domain: String,
        excludedDohHostnames: Set<String>,
    ): Set<String> =
        endpoints
            .filterNot { endpoint -> endpoint.dohHostname() in excludedDohHostnames }
            .firstNotNullOfOrNull { endpoint ->
                queryEndpoint(domain, endpoint).takeIf(Set<String>::isNotEmpty)
            }
            ?: emptySet()

    private fun queryEndpoint(
        domain: String,
        endpoint: String,
    ): Set<String> {
        val transactionId = DnsWireBuilder.randomTransactionId()
        val query = DnsWireBuilder.buildQuery(domain, transactionId)
        val postRequest =
            Request
                .Builder()
                .url(endpoint)
                .post(query.toRequestBody(DnsMessageMediaType))
                .header("Accept", DnsMessageContentType)
                .build()
        val postResult = executeWireRequest(postRequest, transactionId)
        if (postResult.isNotEmpty()) return postResult

        val encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(query)
        val getRequest =
            Request
                .Builder()
                .url("$endpoint?dns=$encoded")
                .get()
                .header("Accept", DnsMessageContentType)
                .build()
        return executeWireRequest(getRequest, transactionId)
    }

    private fun executeWireRequest(
        request: Request,
        transactionId: Int,
    ): Set<String> =
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return emptySet()
            DnsWireBuilder
                .parseResponse(response.body.bytes(), transactionId)
                .filter(::isIpv4Literal)
                .toCollection(linkedSetOf())
        }

    private companion object {
        private const val DefaultDohWireTimeoutMs = 7_000L
    }
}

private val DefaultUdpDnsServers =
    listOf(
        "8.8.8.8",
        "1.1.1.1",
        "9.9.9.9",
        "94.140.14.14",
        "77.88.8.8",
        "208.67.222.222",
    )

private val DefaultDohJsonEndpoints =
    listOf(
        "https://cloudflare-dns.com/dns-query",
        "https://dns.google/resolve",
    )

private val DefaultDohWireEndpoints =
    listOf(
        "https://cloudflare-dns.com/dns-query",
        "https://dns.google/dns-query",
        "https://dns.adguard-dns.com/dns-query",
        "https://dns.quad9.net/dns-query",
    )

private const val DnsMessageContentType = "application/dns-message"
private val DnsMessageMediaType = DnsMessageContentType.toMediaType()

private fun defaultDnsHttpClient(): OkHttpClient =
    OkHttpClient
        .Builder()
        .connectTimeout(DefaultConnectTimeoutSeconds, TimeUnit.SECONDS)
        .readTimeout(DefaultReadTimeoutSeconds, TimeUnit.SECONDS)
        .callTimeout(DefaultCallTimeoutSeconds, TimeUnit.SECONDS)
        .build()

private fun String.dohHostname(): String? = runCatching { URI(this).host }.getOrNull()

private fun isIpv4Literal(value: String): Boolean =
    value
        .split('.')
        .takeIf { parts -> parts.size == Ipv4PartCount }
        ?.all { part -> part.toIntOrNull() in Ipv4ByteRange }
        ?: false

private fun isFakeIp(ip: String): Boolean =
    ip
        .split('.')
        .mapNotNull(String::toIntOrNull)
        .takeIf { parts -> parts.size == Ipv4PartCount }
        ?.let { parts -> parts[0] == FakeIpFirstOctet && parts[1] in FakeIpSecondOctetRange }
        ?: false

private const val Ipv4PartCount = 4
private const val DefaultConnectTimeoutSeconds = 5L
private const val DefaultReadTimeoutSeconds = 5L
private const val DefaultCallTimeoutSeconds = 7L
private const val FakeIpFirstOctet = 198
private val Ipv4ByteRange = 0..255
private val FakeIpSecondOctetRange = 18..19
