package com.poyka.ripdpi.diagnostics.dpich

import com.poyka.ripdpi.serialization.RipDpiPrettyDefaultsJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.TimeUnit
import kotlin.math.min
import kotlin.random.Random

@Serializable
data class WhitelistAsn(
    val provider: String,
    val asn: Int,
)

@Serializable
data class CachedWhitelistSubnet(
    val provider: String,
    val asn: Int,
    val cidr: String,
)

data class WhitelistedSubnetConfig(
    val timeoutMs: Long = 5_000,
    val subnetSampleSize: Int = 25,
    val subnetAliveMin: Int = 3,
    val only24Prefix: Boolean = true,
)

data class CacheProgress(
    val provider: String,
    val asn: Int,
    val cachedCidrs: List<String>,
    val totalCached: Int,
)

data class SubnetCheckProgress(
    val result: WhitelistedSubnetResult,
    val checkedCount: Int,
    val totalCount: Int,
)

data class WhitelistedSubnetResult(
    val provider: String,
    val cidr: String,
    val aliveCount: Int,
    val aliveSampled: Int,
    val whitelisted: Boolean,
)

fun interface RipeStatPrefixSource {
    suspend fun announcedPrefixes(asn: Int): List<String>
}

interface SubnetAliveProbe {
    suspend fun probe(
        ip: String,
        timeoutMs: Long,
    ): SubnetAliveProbeResult
}

class OkHttpSubnetAliveProbe(
    private val httpClient: OkHttpClient = OkHttpClient(),
) : SubnetAliveProbe {
    override suspend fun probe(
        ip: String,
        timeoutMs: Long,
    ): SubnetAliveProbeResult =
        withContext(Dispatchers.IO) {
            val request =
                Request
                    .Builder()
                    .url("https://$ip/")
                    .head()
                    .build()
            val client =
                httpClient
                    .newBuilder()
                    .connectTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                    .readTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                    .callTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                    .build()
            try {
                client.newCall(request).execute().use {
                    SubnetAliveProbeResult.Alive
                }
            } catch (error: IOException) {
                classifyProbeFailure(error)
            }
        }
}

// Some JDKs report a handshake read timeout as an SSLException; a silent peer is unreachable, not a TLS failure.
private fun classifyProbeFailure(error: IOException): SubnetAliveProbeResult {
    val timedOut = generateSequence<Throwable>(error) { it.cause }.any { it is java.io.InterruptedIOException }
    return when {
        timedOut -> SubnetAliveProbeResult.Unreachable

        error is javax.net.ssl.SSLProtocolException ||
            error is javax.net.ssl.SSLPeerUnverifiedException ||
            error is javax.net.ssl.SSLHandshakeException -> SubnetAliveProbeResult.TlsPostHandshakeFailure

        else -> SubnetAliveProbeResult.Unreachable
    }
}

enum class SubnetAliveProbeResult {
    Alive,
    TlsPostHandshakeFailure,
    Unreachable,
}

class Ipv4WhitelistedSubnetDiscoverer(
    private val asns: List<WhitelistAsn>,
    private val ripeStat: RipeStatPrefixSource,
    private val cache: SubnetsCache,
    private val aliveProbe: SubnetAliveProbe,
    private val random: Random = Random.Default,
) {
    fun cacheSubnets(config: WhitelistedSubnetConfig = WhitelistedSubnetConfig()): Flow<CacheProgress> =
        flow {
            val cached = mutableListOf<CachedWhitelistSubnet>()
            for (asn in asns) {
                val cidrs =
                    ripeStat
                        .announcedPrefixes(asn.asn)
                        .filterIpv4Cidrs(config)
                cached += cidrs.map { cidr -> CachedWhitelistSubnet(asn.provider, asn.asn, cidr) }
                emit(
                    CacheProgress(
                        provider = asn.provider,
                        asn = asn.asn,
                        cachedCidrs = cidrs,
                        totalCached = cached.size,
                    ),
                )
            }
            // Publish only a complete run, so a failed or cancelled run keeps the previous cache.
            cache.save(cached)
        }.flowOn(Dispatchers.IO) // Cache file I/O, JSON and sampling block; collectors run on Main.

    fun checkCachedSubnets(config: WhitelistedSubnetConfig = WhitelistedSubnetConfig()): Flow<SubnetCheckProgress> =
        flow {
            require(config.subnetSampleSize > 0) { "subnetSampleSize must be positive" }
            require(config.subnetAliveMin > 0) { "subnetAliveMin must be positive" }
            val cached = cache.load()
            cached.forEachIndexed { index, subnet ->
                val sampledIps = sampleIps(subnet.cidr, config.subnetSampleSize)
                // A SYN-dropping /24 costs one timeout per wave, not one per sampled address.
                val permits = Semaphore(SampleProbeConcurrency)
                val alive =
                    coroutineScope {
                        sampledIps
                            .map { ip -> async { permits.withPermit { aliveProbe.probe(ip, config.timeoutMs) } } }
                            .awaitAll()
                            .count { result -> result.countsAsAlive() }
                    }
                emit(
                    SubnetCheckProgress(
                        result =
                            WhitelistedSubnetResult(
                                provider = subnet.provider,
                                cidr = subnet.cidr,
                                aliveCount = alive,
                                aliveSampled = sampledIps.size,
                                whitelisted = alive >= config.subnetAliveMin,
                            ),
                        checkedCount = index + 1,
                        totalCount = cached.size,
                    ),
                )
            }
        }.flowOn(Dispatchers.IO)

    private fun List<String>.filterIpv4Cidrs(config: WhitelistedSubnetConfig): List<String> =
        mapNotNull { cidr ->
            runCatching { Ipv4Cidr.parse(cidr) }.getOrNull()
        }.filter { cidr ->
            !config.only24Prefix || cidr.prefixLength == Ipv4SubnetPrefix24
        }.map { cidr -> cidr.value }

    private fun sampleIps(
        cidr: String,
        sampleSize: Int,
    ): List<String> {
        val range = Ipv4Cidr.parse(cidr)
        val count = min(sampleSize, range.hostCount)
        // Draw offsets instead of listing every host: a /9 announcement would materialize ~8M strings.
        val offsets =
            if (count == range.hostCount) {
                (0 until count).shuffled(random)
            } else {
                generateSequence { random.nextInt(range.hostCount) }.distinct().take(count).toList()
            }
        return offsets.map(range::host)
    }

    private companion object {
        const val SampleProbeConcurrency = 8
    }
}

class SubnetsCache(
    private val file: File,
    private val json: Json = RipDpiPrettyDefaultsJson,
) {
    fun load(): List<CachedWhitelistSubnet> =
        if (!file.exists()) {
            emptyList()
        } else {
            try {
                json.decodeFromString(ListSerializer(CachedWhitelistSubnet.serializer()), file.readText())
            } catch (_: SerializationException) {
                // A torn or foreign file is a regenerable cache: drop it instead of failing every check.
                file.delete()
                emptyList()
            }
        }

    fun save(subnets: List<CachedWhitelistSubnet>) {
        val directory = requireNotNull(file.absoluteFile.parentFile)
        directory.mkdirs()
        val staged = File.createTempFile(file.name, ".tmp", directory)
        try {
            staged.writeText(json.encodeToString(ListSerializer(CachedWhitelistSubnet.serializer()), subnets))
            Files.move(
                staged.toPath(),
                file.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } finally {
            staged.delete()
        }
    }
}

fun List<WhitelistedSubnetResult>.toWhitelistedSubnetCsv(): String =
    buildString {
        appendLine("provider,cidr,alive_count,whitelisted")
        this@toWhitelistedSubnetCsv.forEach { result ->
            append(result.provider.csvCell())
            append(',')
            append(result.cidr.csvCell())
            append(',')
            append(result.aliveCount)
            append(',')
            appendLine(result.whitelisted)
        }
    }

private fun SubnetAliveProbeResult.countsAsAlive(): Boolean =
    this == SubnetAliveProbeResult.Alive || this == SubnetAliveProbeResult.TlsPostHandshakeFailure

private fun String.csvCell(): String =
    if (any { char -> char == ',' || char == '"' || char == '\n' }) {
        "\"" + replace("\"", "\"\"") + "\""
    } else {
        this
    }

private data class Ipv4Cidr(
    val value: String,
    val network: Long,
    val prefixLength: Int,
) {
    val hostCount: Int
        get() = (lastHost - firstHost + 1).toInt().coerceAtLeast(0)

    private val mask: Long
        get() =
            if (prefixLength == 0) {
                0
            } else {
                MaxIpv4 shl (Ipv4Bits - prefixLength) and MaxIpv4
            }
    private val broadcast: Long
        get() = network or (mask xor MaxIpv4)
    private val firstHost: Long
        get() = if (prefixLength >= 31) network else network + 1
    private val lastHost: Long
        get() = if (prefixLength >= 31) broadcast else broadcast - 1

    fun host(offset: Int): String = (firstHost + offset).toIpv4String()

    companion object {
        fun parse(cidr: String): Ipv4Cidr {
            val parts = cidr.split("/")
            require(parts.size == 2) { "invalid IPv4 CIDR: $cidr" }
            val prefix = parts[1].toInt()
            require(prefix in MinIpv4PrefixLength..Ipv4Bits) { "invalid IPv4 prefix length: $cidr" }
            val rawAddress = parts[0].toIpv4Long()
            val mask =
                if (prefix == 0) {
                    0
                } else {
                    MaxIpv4 shl (Ipv4Bits - prefix) and MaxIpv4
                }
            val network = rawAddress and mask
            return Ipv4Cidr(
                value = "${network.toIpv4String()}/$prefix",
                network = network,
                prefixLength = prefix,
            )
        }
    }
}

private fun String.toIpv4Long(): Long {
    val octets = split(".")
    require(octets.size == Ipv4OctetCount) { "invalid IPv4 address: $this" }
    return octets.fold(0L) { acc, octet ->
        val value = octet.toInt()
        require(value in MinIpv4OctetValue..MaxIpv4OctetValue) { "invalid IPv4 octet: $this" }
        (acc shl BitsPerOctet) or value.toLong()
    }
}

private fun Long.toIpv4String(): String =
    listOf(
        (this ushr ThreeOctetShift) and OctetMask,
        (this ushr TwoOctetShift) and OctetMask,
        (this ushr BitsPerOctet) and OctetMask,
        this and OctetMask,
    ).joinToString(".")

private const val MinIpv4PrefixLength = 0
private const val Ipv4Bits = 32
private const val Ipv4SubnetPrefix24 = 24
private const val Ipv4OctetCount = 4
private const val MinIpv4OctetValue = 0
private const val MaxIpv4OctetValue = 255
private const val BitsPerOctet = 8
private const val TwoOctetShift = 16
private const val ThreeOctetShift = 24
private const val MaxIpv4 = 0xFFFF_FFFFL
private const val OctetMask = 0xFFL
