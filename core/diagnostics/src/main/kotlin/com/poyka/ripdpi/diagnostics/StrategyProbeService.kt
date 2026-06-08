@file:Suppress("TooManyFunctions")

package com.poyka.ripdpi.diagnostics

import com.poyka.ripdpi.core.ProcessGlobalStrategyEngineBindings
import com.poyka.ripdpi.core.StrategyEngineBindings
import com.poyka.ripdpi.core.StrategyProbeResultDto
import com.poyka.ripdpi.data.AppSettingsRepository
import com.poyka.ripdpi.data.setRawStrategyChainDsl
import com.poyka.ripdpi.proto.AppSettings
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.Callback
import okhttp3.EventListener
import okhttp3.Handshake
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.io.InterruptedIOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.TreeSet
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.math.roundToInt

private const val DefaultProbeTimeoutMs = 10_000L
private const val DefaultProbeProxyHost = "127.0.0.1"
private const val DefaultProbeProxyPort = 1080
private const val DnsTypeA = 1
private const val DnsTypeAaaa = 28

private val DefaultProbeDomains =
    listOf(
        "www.youtube.com",
        "www.facebook.com",
        "t.me",
        "twitter.com",
        "www.instagram.com",
    )

interface StrategyProbeService {
    fun run(config: StrategyProbeConfig = StrategyProbeConfig()): Flow<StrategyProbeResult>
}

data class StrategyProbeConfig(
    val testDomains: List<String> = DefaultProbeDomains,
    val timeoutMs: Long = DefaultProbeTimeoutMs,
    val maxStrategies: Int = Int.MAX_VALUE,
    val maxTotalProbes: Int = Int.MAX_VALUE,
    val probeIntervalMs: Long = 0L,
    val candidateIds: List<String> = emptyList(),
    val proxyHost: String = DefaultProbeProxyHost,
    val proxyPort: Int = DefaultProbeProxyPort,
)

data class StrategyProbeCandidate(
    val id: String,
    val label: String,
    val configDsl: String? = null,
    val luaScriptPaths: List<String> = emptyList(),
    val supportsTunEgressProbe: Boolean = true,
)

enum class StrategyProbeFailureKind {
    Timeout,
    ConnectionFailed,
    DnsTampered,
}

data class StrategyProbeResult(
    val strategyId: String,
    val strategyLabel: String,
    val domain: String,
    val success: Boolean,
    val latencyMs: Long,
    val error: String? = null,
    val failureKind: StrategyProbeFailureKind? = null,
    val dnsTampered: Boolean = false,
    val dnsComparison: StrategyProbeDnsComparison? = null,
)

data class StrategyProbeAutomationReport(
    val results: List<StrategyProbeResult>,
    val rankedStrategies: List<RankedStrategyProbeResult>,
)

data class RankedStrategyProbeResult(
    val strategyId: String,
    val strategyLabel: String,
    val total: Int,
    val successes: Int,
    val successRate: Double,
    val averageLatencyMs: Long,
    val dnsTamperedCount: Int,
)

data class StrategyProbeActivationSnapshot(
    val snapshotId: String,
    val settings: AppSettings? = null,
)

data class StrategyProbeEndpoint(
    val host: String,
    val port: Int,
)

data class StrategyProbeConnectionRequest(
    val strategy: StrategyProbeCandidate,
    val domain: String,
    val endpoint: StrategyProbeEndpoint,
    val timeoutMs: Long,
)

sealed class StrategyProbeConnectionResult {
    abstract val latencyMs: Long

    data class Success(
        override val latencyMs: Long,
    ) : StrategyProbeConnectionResult()

    data class Failure(
        override val latencyMs: Long,
        val error: String,
        val timedOut: Boolean = false,
    ) : StrategyProbeConnectionResult()
}

data class StrategyProbeDnsComparison(
    val localAddresses: Set<String>,
    val dohAddresses: Set<String>,
    val tampered: Boolean,
    val error: String? = null,
) {
    companion object {
        val NoDifference =
            StrategyProbeDnsComparison(
                localAddresses = emptySet(),
                dohAddresses = emptySet(),
                tampered = false,
            )
    }
}

interface StrategyProbeCandidateProvider {
    suspend fun listCandidates(): List<StrategyProbeCandidate>
}

interface StrategyProbeActivator {
    suspend fun capture(): StrategyProbeActivationSnapshot

    suspend fun activate(candidate: StrategyProbeCandidate)

    suspend fun restore(snapshot: StrategyProbeActivationSnapshot)
}

interface StrategyProbeTransport {
    suspend fun probe(request: StrategyProbeConnectionRequest): StrategyProbeConnectionResult
}

interface StrategyProbeDnsComparator {
    suspend fun compare(domain: String): StrategyProbeDnsComparison
}

interface StrategyProbeResultInjector {
    suspend fun inject(results: List<StrategyProbeResult>)
}

@Singleton
class DefaultStrategyProbeService
    @Inject
    constructor(
        private val candidateProvider: StrategyProbeCandidateProvider,
        private val activator: StrategyProbeActivator,
        private val transport: StrategyProbeTransport,
        private val dnsComparator: StrategyProbeDnsComparator,
        private val resultInjector: StrategyProbeResultInjector,
    ) : StrategyProbeService {
        override fun run(config: StrategyProbeConfig): Flow<StrategyProbeResult> =
            flow {
                val snapshot = activator.capture()
                val emittedResults = mutableListOf<StrategyProbeResult>()
                try {
                    val candidates =
                        candidateProvider
                            .listCandidates()
                            .filteredBy(config.candidateIds)
                            .normalized(config.maxStrategies)
                    val domains = config.testDomains.normalizedDomains()
                    val endpoint = StrategyProbeEndpoint(config.proxyHost, config.proxyPort)
                    val maxTotalProbes = config.maxTotalProbes.coerceAtLeast(0)
                    var probeCount = 0
                    var firstProbe = true

                    for (candidate in candidates) {
                        currentCoroutineContext().ensureActiveForProbe()
                        activator.activate(candidate)

                        for (domain in domains) {
                            currentCoroutineContext().ensureActiveForProbe()
                            if (probeCount >= maxTotalProbes) {
                                break
                            }
                            if (!firstProbe) {
                                delay(config.probeIntervalMs.coerceAtLeast(0L))
                            }
                            firstProbe = false
                            val dnsComparison = dnsComparator.compare(domain)
                            val result =
                                transport
                                    .probe(
                                        StrategyProbeConnectionRequest(
                                            strategy = candidate,
                                            domain = domain,
                                            endpoint = endpoint,
                                            timeoutMs = config.timeoutMs,
                                        ),
                                    ).toProbeResult(candidate, domain, dnsComparison)
                            emittedResults += result
                            probeCount += 1
                            emit(result)
                        }
                        if (probeCount >= maxTotalProbes) {
                            break
                        }
                    }
                    resultInjector.inject(emittedResults)
                } finally {
                    withContext(NonCancellable) {
                        activator.restore(snapshot)
                    }
                }
            }
    }

class DefaultStrategyProbeCandidateProvider
    @Inject
    constructor(
        private val bindings: StrategyEngineBindings,
    ) : StrategyProbeCandidateProvider {
        override suspend fun listCandidates(): List<StrategyProbeCandidate> =
            (builtInStrategyProbeCandidates() + luaStrategyProbeCandidates())
                .filter(StrategyProbeCandidate::supportsTunEgressProbe)

        private fun luaStrategyProbeCandidates(): List<StrategyProbeCandidate> =
            runCatching {
                val scriptPaths = bindings.luaLoadedScriptPaths().map(String::trim).filter(String::isNotEmpty)
                if (scriptPaths.isEmpty()) {
                    return@runCatching emptyList()
                }
                bindings
                    .luaListStrategies()
                    .asSequence()
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                    .distinct()
                    .mapTo(mutableListOf()) { function ->
                        StrategyProbeCandidate(
                            id = "lua:$function",
                            label = function,
                            luaScriptPaths = scriptPaths,
                            supportsTunEgressProbe = false,
                        )
                    }
            }.getOrDefault(emptyList())
    }

class SettingsStrategyProbeActivator
    @Inject
    constructor(
        private val appSettingsRepository: AppSettingsRepository,
    ) : StrategyProbeActivator {
        override suspend fun capture(): StrategyProbeActivationSnapshot =
            StrategyProbeActivationSnapshot(
                snapshotId = "app-settings",
                settings = appSettingsRepository.snapshot(),
            )

        override suspend fun activate(candidate: StrategyProbeCandidate) {
            appSettingsRepository.update {
                strategyChainYaml = candidate.toStrategyActivationYaml()
                candidate.configDsl?.let(::setRawStrategyChainDsl)
            }
        }

        override suspend fun restore(snapshot: StrategyProbeActivationSnapshot) {
            snapshot.settings?.let { appSettingsRepository.replace(it) }
        }
    }

class OkHttpStrategyProbeTransport
    @Inject
    constructor(
        private val baseClient: OkHttpClient,
    ) : StrategyProbeTransport {
        override suspend fun probe(request: StrategyProbeConnectionRequest): StrategyProbeConnectionResult =
            suspendCancellableCoroutine { continuation ->
                val startedAt = System.nanoTime()
                val listener = ProbeEventListener(startedAt)
                val client =
                    baseClient
                        .newBuilder()
                        .proxy(Proxy(Proxy.Type.SOCKS, InetSocketAddress(request.endpoint.host, request.endpoint.port)))
                        .callTimeout(request.timeoutMs, TimeUnit.MILLISECONDS)
                        .connectTimeout(request.timeoutMs, TimeUnit.MILLISECONDS)
                        .readTimeout(request.timeoutMs, TimeUnit.MILLISECONDS)
                        .eventListener(listener)
                        .build()
                val call =
                    client.newCall(
                        Request
                            .Builder()
                            .url("https://${request.domain}/")
                            .head()
                            .build(),
                    )
                continuation.invokeOnCancellation { call.cancel() }
                call.enqueue(
                    object : Callback {
                        override fun onFailure(
                            call: Call,
                            e: IOException,
                        ) {
                            if (!continuation.isActive) {
                                return
                            }
                            continuation.resume(e.toConnectionFailure(startedAt))
                        }

                        override fun onResponse(
                            call: Call,
                            response: Response,
                        ) {
                            response.close()
                            if (!continuation.isActive) {
                                return
                            }
                            continuation.resume(listener.toResult())
                        }
                    },
                )
            }

        private class ProbeEventListener(
            private val startedAt: Long,
        ) : EventListener() {
            private var secureLatencyMs: Long? = null

            override fun secureConnectEnd(
                call: Call,
                handshake: Handshake?,
            ) {
                secureLatencyMs = elapsedMs(startedAt)
            }

            override fun connectEnd(
                call: Call,
                inetSocketAddress: InetSocketAddress,
                proxy: Proxy,
                protocol: Protocol?,
            ) {
                if (protocol == Protocol.HTTP_2 || protocol == Protocol.HTTP_1_1) {
                    secureLatencyMs = secureLatencyMs ?: elapsedMs(startedAt)
                }
            }

            fun toResult(): StrategyProbeConnectionResult =
                secureLatencyMs?.let { StrategyProbeConnectionResult.Success(it) }
                    ?: StrategyProbeConnectionResult.Failure(
                        latencyMs = elapsedMs(startedAt),
                        error = "TLS handshake did not complete",
                    )
        }
    }

class DefaultStrategyProbeDnsComparator
    @Inject
    constructor(
        private val localResolver: LocalDnsAddressResolver,
        private val dohResolver: DnsOverHttpsAddressResolver,
    ) : StrategyProbeDnsComparator {
        override suspend fun compare(domain: String): StrategyProbeDnsComparison {
            val local = runCatching { localResolver.resolve(domain) }
            val doh = runCatching { dohResolver.resolve(domain) }
            val localAddresses = local.getOrDefault(emptySet())
            val dohAddresses = doh.getOrDefault(emptySet())
            val tampered = localAddresses.isNotEmpty() && dohAddresses.isNotEmpty() && localAddresses != dohAddresses
            return StrategyProbeDnsComparison(
                localAddresses = localAddresses,
                dohAddresses = dohAddresses,
                tampered = tampered,
                error =
                    listOfNotNull(
                        local.exceptionOrNull()?.message,
                        doh.exceptionOrNull()?.message,
                    ).joinToString("; ").ifBlank { null },
            )
        }
    }

class NativeStrategyProbeResultInjector
    @Inject
    constructor(
        private val bindings: StrategyEngineBindings,
    ) : StrategyProbeResultInjector {
        override suspend fun inject(results: List<StrategyProbeResult>) {
            if (results.isEmpty()) {
                return
            }
            val injectionError =
                bindings.injectProbeResults(
                    results
                        .map {
                            StrategyProbeResultDto(
                                strategyId = it.strategyId,
                                domain = it.domain,
                                success = it.success,
                                latencyMs = it.latencyMs,
                            )
                        }.toTypedArray(),
                )
            if (injectionError != null) {
                error(injectionError)
            }
        }
    }

class LocalDnsAddressResolver
    @Inject
    constructor() {
        suspend fun resolve(domain: String): Set<String> =
            withContext(Dispatchers.IO) {
                InetAddress.getAllByName(domain).mapNotNullTo(linkedSetOf()) { it.hostAddress }
            }
    }

class DnsOverHttpsAddressResolver
    @Inject
    constructor(
        private val client: OkHttpClient,
        @param:Named("diagnosticsJson")
        private val json: Json,
    ) {
        suspend fun resolve(domain: String): Set<String> =
            withContext(Dispatchers.IO) {
                buildSet {
                    addAll(query(domain, "A", DnsTypeA))
                    addAll(query(domain, "AAAA", DnsTypeAaaa))
                }
            }

        private fun query(
            domain: String,
            typeName: String,
            typeCode: Int,
        ): Set<String> {
            val request = Request.Builder().url(dnsGoogleUrl(domain, typeName)).build()
            return client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return emptySet()
                }
                val body = response.body.string()
                json
                    .decodeFromString<DnsGoogleResponse>(body)
                    .answers
                    .orEmpty()
                    .asSequence()
                    .filter { it.type == typeCode }
                    .map { it.data.trimEnd('.') }
                    .filter { it.isNotBlank() }
                    .toSet()
            }
        }

        private fun dnsGoogleUrl(
            domain: String,
            typeName: String,
        ): HttpUrl =
            HttpUrl
                .Builder()
                .scheme("https")
                .host("dns.google")
                .addPathSegment("resolve")
                .addQueryParameter("name", domain)
                .addQueryParameter("type", typeName)
                .build()
    }

@Serializable
private data class DnsGoogleResponse(
    @SerialName("Answer")
    val answers: List<DnsGoogleAnswer>? = null,
)

@Serializable
private data class DnsGoogleAnswer(
    val type: Int,
    val data: String,
)

fun summarizeStrategyProbeResults(results: List<StrategyProbeResult>): StrategyProbeAutomationReport {
    val summaries = StrategyProbeRankingSummaries()
    results.forEach(summaries::add)
    return StrategyProbeAutomationReport(results = results, rankedStrategies = summaries.rankedStrategies())
}

class StrategyProbeRankingAccumulator {
    private val summaries = StrategyProbeRankingSummaries()
    private val rankedSummaries = TreeSet(StrategyProbeRankingComparator)

    fun add(result: StrategyProbeResult) {
        summaries.currentRanked(result.strategyId)?.let(rankedSummaries::remove)
        rankedSummaries += summaries.add(result)
    }

    fun rankedStrategies(): List<RankedStrategyProbeResult> = rankedSummaries.toList()
}

private class StrategyProbeRankingSummaries {
    private val summariesByStrategyId = mutableMapOf<String, MutableStrategyProbeRankingSummary>()

    fun add(result: StrategyProbeResult): RankedStrategyProbeResult {
        val summary =
            summariesByStrategyId.getOrPut(result.strategyId) {
                MutableStrategyProbeRankingSummary(
                    strategyId = result.strategyId,
                    strategyLabel = result.strategyLabel,
                )
            }
        return summary.add(result)
    }

    fun currentRanked(strategyId: String): RankedStrategyProbeResult? = summariesByStrategyId[strategyId]?.currentRanked

    fun rankedStrategies(): List<RankedStrategyProbeResult> =
        summariesByStrategyId
            .values
            .mapNotNull { it.currentRanked }
            .sortedWith(StrategyProbeRankingComparator)
}

private class MutableStrategyProbeRankingSummary(
    private val strategyId: String,
    private val strategyLabel: String,
) {
    private var total = 0
    private var successes = 0
    private var latencySumMs = 0.0
    private var dnsTamperedCount = 0

    var currentRanked: RankedStrategyProbeResult? = null
        private set

    fun add(result: StrategyProbeResult): RankedStrategyProbeResult {
        total += 1
        if (result.success) {
            successes += 1
        }
        latencySumMs += result.latencyMs.toDouble()
        if (result.dnsTampered) {
            dnsTamperedCount += 1
        }
        return toRanked().also { currentRanked = it }
    }

    private fun toRanked(): RankedStrategyProbeResult =
        RankedStrategyProbeResult(
            strategyId = strategyId,
            strategyLabel = strategyLabel,
            total = total,
            successes = successes,
            successRate = successes.toDouble() / total.toDouble(),
            averageLatencyMs = (latencySumMs / total.toDouble()).roundToLong(),
            dnsTamperedCount = dnsTamperedCount,
        )
}

private val StrategyProbeRankingComparator =
    compareByDescending<RankedStrategyProbeResult> { it.successRate }
        .thenBy { it.averageLatencyMs }
        .thenBy { it.strategyId }

private fun StrategyProbeConnectionResult.toProbeResult(
    candidate: StrategyProbeCandidate,
    domain: String,
    dnsComparison: StrategyProbeDnsComparison,
): StrategyProbeResult =
    when (this) {
        is StrategyProbeConnectionResult.Success -> {
            StrategyProbeResult(
                strategyId = candidate.id,
                strategyLabel = candidate.label,
                domain = domain,
                success = !dnsComparison.tampered,
                latencyMs = latencyMs,
                failureKind = dnsComparison.toFailureKind(),
                dnsTampered = dnsComparison.tampered,
                dnsComparison = dnsComparison,
            )
        }

        is StrategyProbeConnectionResult.Failure -> {
            StrategyProbeResult(
                strategyId = candidate.id,
                strategyLabel = candidate.label,
                domain = domain,
                success = false,
                latencyMs = latencyMs,
                error = error,
                failureKind =
                    when {
                        timedOut -> StrategyProbeFailureKind.Timeout
                        dnsComparison.tampered -> StrategyProbeFailureKind.DnsTampered
                        else -> StrategyProbeFailureKind.ConnectionFailed
                    },
                dnsTampered = dnsComparison.tampered,
                dnsComparison = dnsComparison,
            )
        }
    }

private fun StrategyProbeDnsComparison.toFailureKind(): StrategyProbeFailureKind? =
    if (tampered) StrategyProbeFailureKind.DnsTampered else null

private fun IOException.toConnectionFailure(startedAt: Long): StrategyProbeConnectionResult.Failure {
    val timedOut = this is InterruptedIOException
    return StrategyProbeConnectionResult.Failure(
        latencyMs = elapsedMs(startedAt),
        error = if (timedOut) "timeout" else message ?: javaClass.simpleName,
        timedOut = timedOut,
    )
}

private fun List<StrategyProbeCandidate>.normalized(maxStrategies: Int): List<StrategyProbeCandidate> =
    asSequence()
        .distinctBy { it.id }
        .take(maxStrategies.coerceAtLeast(0))
        .toList()

private fun List<StrategyProbeCandidate>.filteredBy(candidateIds: List<String>): List<StrategyProbeCandidate> {
    val allowedIds = candidateIds.mapTo(linkedSetOf()) { it.trim() }.filter(String::isNotEmpty)
    if (allowedIds.isEmpty()) {
        return this
    }
    return filter { it.id in allowedIds }
}

private fun List<String>.normalizedDomains(): List<String> =
    asSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .distinct()
        .toList()

private fun builtInStrategyProbeCandidates(): List<StrategyProbeCandidate> =
    listOf(
        StrategyProbeCandidate("split", "Split", "[tcp]\nsplit auto(balanced)"),
        StrategyProbeCandidate("fake", "Fake packet", "[tcp]\nfake auto(host)"),
        StrategyProbeCandidate("oob", "TCP urgent/OOB", "[tcp]\noob auto(host)"),
        StrategyProbeCandidate("tls_rec_split", "TLS record split", "[tcp]\ntlsrec extlen\nsplit auto(balanced)"),
        StrategyProbeCandidate("seq_overlap", "Sequence overlap", "[tcp]\nseqovl midsld overlap=16 fake=rand"),
        StrategyProbeCandidate("udp_fake_burst", "UDP fake burst", "[udp]\nfake_burst 3"),
    )

fun StrategyProbeCandidate.toStrategyActivationYaml(): String =
    if (id.startsWith(LuaStrategyIdPrefix)) {
        luaActivationYaml()
    } else {
        """
        strategies:
          - id: "$id"
            label: "$label"
        """.trimIndent()
    }

private const val LuaStrategyIdPrefix = "lua:"

private fun StrategyProbeCandidate.luaActivationYaml(): String {
    val function = id.removePrefix(LuaStrategyIdPrefix)
    return (
        listOf(
            "version: 1",
            "strategies:",
            "  - id: \"${id.yamlQuote()}\"",
            "    steps:",
            "      - type: lua",
            "        function: \"${function.yamlQuote()}\"",
            "        script_paths:",
        ) +
            luaScriptPaths.map { path -> "          - \"${path.yamlQuote()}\"" }
    ).joinToString(separator = "\n")
}

private fun String.yamlQuote(): String = replace("\\", "\\\\").replace("\"", "\\\"")

private fun elapsedMs(startedAt: Long): Long =
    TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt).coerceAtLeast(0L)

private fun Double.roundToLong(): Long = roundToInt().toLong()

private fun kotlin.coroutines.CoroutineContext.ensureActiveForProbe() {
    if (!isActive) {
        throw CancellationException("Strategy probe cancelled")
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class StrategyProbeModule {
    @Binds
    @Singleton
    abstract fun bindStrategyProbeService(service: DefaultStrategyProbeService): StrategyProbeService

    @Binds
    @Singleton
    abstract fun bindStrategyProbeCandidateProvider(
        provider: DefaultStrategyProbeCandidateProvider,
    ): StrategyProbeCandidateProvider

    @Binds
    @Singleton
    abstract fun bindStrategyProbeActivator(activator: SettingsStrategyProbeActivator): StrategyProbeActivator

    @Binds
    @Singleton
    abstract fun bindStrategyProbeTransport(transport: OkHttpStrategyProbeTransport): StrategyProbeTransport

    @Binds
    @Singleton
    abstract fun bindStrategyProbeDnsComparator(
        comparator: DefaultStrategyProbeDnsComparator,
    ): StrategyProbeDnsComparator

    @Binds
    @Singleton
    abstract fun bindStrategyProbeResultInjector(
        injector: NativeStrategyProbeResultInjector,
    ): StrategyProbeResultInjector

    companion object {
        @Provides
        @Singleton
        fun provideStrategyEngineBindings(): StrategyEngineBindings = ProcessGlobalStrategyEngineBindings()

        @Provides
        @Singleton
        fun provideStrategyProbeOkHttpClient(): OkHttpClient = OkHttpClient()
    }
}
