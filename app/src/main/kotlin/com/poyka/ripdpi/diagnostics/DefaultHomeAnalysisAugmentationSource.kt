package com.poyka.ripdpi.diagnostics

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.NetworkCapabilities
import com.poyka.ripdpi.data.NetworkFingerprintProvider
import com.poyka.ripdpi.services.RoutingProtectionCatalogService
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.InetAddress
import java.net.Socket
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

private const val BufferbloatProbes = 4
private const val BufferbloatProbeTimeoutMs = 1_500L
private const val BufferbloatLoadedTimeoutMs = 8_000L
private const val BufferbloatHost = "1.1.1.1"
private const val BufferbloatTcpPort = 443
private const val BufferbloatLoadedUrl = "https://speed.cloudflare.com/__down?bytes=2000000"
private const val DnsControlHost = "cloudflare.com"
private const val DnsCanaryHost = "youtube.com"
private const val DohEndpoint = "https://1.1.1.1/dns-query"
private const val NetworkProbeOverallTimeoutMs = 15_000L
private const val MaxRoutingFindings = 6

@Singleton
class DefaultHomeAnalysisAugmentationSource
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
        private val networkFingerprintProvider: NetworkFingerprintProvider,
        private val catalogService: RoutingProtectionCatalogService,
    ) : HomeAnalysisAugmentationSource {
        private val httpClient: OkHttpClient by lazy {
            OkHttpClient
                .Builder()
                .connectTimeout(BufferbloatProbeTimeoutMs, TimeUnit.MILLISECONDS)
                .readTimeout(BufferbloatLoadedTimeoutMs, TimeUnit.MILLISECONDS)
                .callTimeout(BufferbloatLoadedTimeoutMs, TimeUnit.MILLISECONDS)
                .retryOnConnectionFailure(false)
                .build()
        }

        override suspend fun networkCharacter(): HomeNetworkCharacterSummary? =
            withContext(Dispatchers.IO) {
                runCatching {
                    val fingerprint = networkFingerprintProvider.capture()
                    val cm = context.getSystemService(ConnectivityManager::class.java)
                    val activeNetwork = cm?.activeNetwork
                    val capabilities: NetworkCapabilities? = activeNetwork?.let { cm.getNetworkCapabilities(it) }
                    val linkProps: LinkProperties? = activeNetwork?.let { cm.getLinkProperties(it) }
                    val transport = describeTransport(capabilities, fingerprint?.transport)
                    val operator =
                        describeOperatorOrSsid(
                            transport = transport,
                            cellularOperatorCode = fingerprint?.cellular?.operatorCode,
                        )
                    val captivePortal =
                        capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL) == true
                    val mtu = linkPropertiesMtuOrNull(linkProps)
                    val ipv6Reachable =
                        measureHomeIpv6Reachability(
                            socketFactory = activeNetwork?.let { network -> { network.socketFactory.createSocket() } },
                            isNetworkCurrent = { activeNetwork != null && cm?.activeNetwork == activeNetwork },
                        )
                    val notes = mutableListOf<String>()
                    if (capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN) == false) {
                        notes += "Active network is a VPN"
                    }
                    if (capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) == false) {
                        notes += "Network reports as metered"
                    }
                    HomeNetworkCharacterSummary(
                        transport = transport,
                        operatorOrSsid = operator,
                        asn = null,
                        publicIp = null,
                        ipv6Reachable = ipv6Reachable,
                        captivePortalDetected = captivePortal,
                        mtu = mtu,
                        transparentProxyDetected = null,
                        notes = notes,
                    )
                }.getOrNull()
            }

        override suspend fun routingSanity(): HomeRoutingSanitySummary? =
            withContext(Dispatchers.Default) {
                runCatching {
                    val snapshot = catalogService.snapshot()
                    val detectorApps = snapshot.detectedApps.filter { it.vpnDetection }
                    val configured = snapshot.detectedApps.size
                    val findings =
                        detectorApps
                            .take(MaxRoutingFindings)
                            .map { app ->
                                HomeRoutingSanityFinding(
                                    packageName = app.packageName,
                                    severity = app.severity,
                                    description =
                                        "VPN-detector app — verify per-app routing override " +
                                            "(detection: ${app.detectionMethod})",
                                )
                            }
                    HomeRoutingSanitySummary(
                        totalConfiguredApps = configured,
                        confirmedDetectorCount = detectorApps.size,
                        findings = findings,
                    )
                }.getOrNull()
            }

        override suspend fun bufferbloat(): HomeBufferbloatResult? =
            withTimeoutOrNull(NetworkProbeOverallTimeoutMs) {
                withContext(Dispatchers.IO) {
                    runCatching {
                        val idle = measureRttSamples(BufferbloatProbes)
                        val (loaded, load) = withLoad { measureRttSamples(BufferbloatProbes) }
                        homeBufferbloatResult(idle, loaded, load)
                    }.getOrNull()
                }
            }

        override suspend fun dnsCharacterization(): HomeDnsCharacterization? =
            withTimeoutOrNull(NetworkProbeOverallTimeoutMs) {
                withContext(Dispatchers.IO) {
                    runCatching {
                        val cm = context.getSystemService(ConnectivityManager::class.java)
                        val network = cm?.activeNetwork
                        val systemResolver = homeSystemResolver(network?.let { cm.getLinkProperties(it)?.dnsServers })
                        val systemIps = resolveSystem(DnsControlHost)
                        val canarySystemIps = resolveSystem(DnsCanaryHost)
                        val dohControlIps = resolveDoh(DnsControlHost)
                        val dohCanaryIps = resolveDoh(DnsCanaryHost)
                        characterizeHomeDns(systemIps, canarySystemIps, dohControlIps, dohCanaryIps).copy(
                            systemResolver = systemResolver,
                        )
                    }.getOrNull()
                }
            }

        private fun describeTransport(
            caps: NetworkCapabilities?,
            fallback: String?,
        ): String? {
            caps ?: return fallback
            return when {
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "vpn"
                else -> fallback
            }
        }

        private fun describeOperatorOrSsid(
            transport: String?,
            cellularOperatorCode: String?,
        ): String? =
            homeNetworkIdentitySignal(
                transport = transport,
                cellularOperatorCode = cellularOperatorCode,
            )

        private fun resolveSystem(host: String): List<String> =
            runCatching { InetAddress.getAllByName(host).map { it.hostAddress.orEmpty() }.filter { it.isNotBlank() } }
                .getOrDefault(emptyList())

        private fun resolveDoh(host: String): List<String>? {
            val request =
                Request
                    .Builder()
                    .url("$DohEndpoint?name=$host&type=A")
                    .header("accept", "application/dns-json")
                    .build()
            return runCatching {
                httpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@use null
                    val body = response.body.string()
                    val matches = Regex("\"data\":\"([^\"]+)\"").findAll(body)
                    matches.mapNotNull { match -> match.groupValues.getOrNull(1) }.toList().ifEmpty { null }
                }
            }.getOrNull()
        }

        private fun measureRttSamples(probes: Int): List<HomeRttSample> =
            buildList {
                repeat(probes) {
                    measureSingleRtt()?.let { add(it) }
                }
            }

        private fun measureSingleRtt(): HomeRttSample? =
            runCatching {
                Socket().use { socket ->
                    val startedNs = System.nanoTime()
                    socket.connect(
                        java.net.InetSocketAddress(BufferbloatHost, BufferbloatTcpPort),
                        BufferbloatProbeTimeoutMs.toInt(),
                    )
                    val finishedNs = System.nanoTime()
                    HomeRttSample(startedNs, finishedNs, TimeUnit.NANOSECONDS.toMillis(finishedNs - startedNs))
                }
            }.getOrNull()

        private suspend fun withLoad(block: () -> List<HomeRttSample>): Pair<List<HomeRttSample>, HomeLoadEvidence> =
            coroutineScope {
                val started = CompletableDeferred<Boolean>()
                val loadJob = async(Dispatchers.IO) { downloadLoad(started) }
                val result = if (started.await()) block() else emptyList()
                result to loadJob.await()
            }

        private fun downloadLoad(started: CompletableDeferred<Boolean>): HomeLoadEvidence {
            var bytesRead = 0L
            var firstByteNs: Long? = null
            var lastByteNs: Long? = null
            val successful =
                runCatching {
                    val request = Request.Builder().url(BufferbloatLoadedUrl).build()
                    httpClient.newCall(request).execute().use { response ->
                        if (!response.isSuccessful) return@use false
                        response.body.byteStream().use { stream ->
                            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                            while (true) {
                                val count = stream.read(buffer)
                                if (count < 0) break
                                if (count > 0) {
                                    bytesRead += count
                                    lastByteNs = System.nanoTime()
                                    if (firstByteNs == null) {
                                        firstByteNs = lastByteNs
                                        started.complete(true)
                                    }
                                }
                            }
                        }
                        true
                    }
                }.getOrDefault(false)
            started.complete(false)
            return HomeLoadEvidence(successful, bytesRead, firstByteNs, lastByteNs)
        }
    }

internal fun homeNetworkIdentitySignal(
    transport: String?,
    cellularOperatorCode: String? = null,
): String? =
    when (transport) {
        "cellular" -> {
            cellularOperatorCode
                ?.takeUnless { it.isBlank() || it == "unknown" }
                ?.let { "Carrier $it" }
        }

        else -> {
            null
        }
    }
