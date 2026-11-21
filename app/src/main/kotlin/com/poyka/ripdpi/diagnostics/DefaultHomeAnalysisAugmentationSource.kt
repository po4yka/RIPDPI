package com.poyka.ripdpi.diagnostics

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.NetworkCapabilities
import com.poyka.ripdpi.data.NetworkFingerprintProvider
import com.poyka.ripdpi.diagnostics.rkn.DohReferenceResolver
import com.poyka.ripdpi.services.RoutingProtectionCatalogService
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.net.InetAddress
import java.net.Socket
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

private const val BufferbloatProbes = 4
private const val BufferbloatProbeTimeoutMs = 1_500L
private const val BufferbloatLoadedTimeoutMs = 8_000L
private const val BufferbloatTcpPort = 443
private const val BufferbloatLoadBytes = 2_000_000L

// Cloudflare is blocked in Russia, so it is the last option: the first target that answers is used.
private val BufferbloatRttHosts = listOf("8.8.8.8", "1.1.1.1")
private val BufferbloatLoadedUrls =
    listOf("https://proof.ovh.net/files/10Mb.dat", "https://speed.cloudflare.com/__down?bytes=2000000")

// dns.google has the same anycast answer everywhere, so system and DoH answers for it must overlap.
private const val DnsControlHost = "dns.google"
private const val DnsCanaryHost = "youtube.com"
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
        private val dohReference = DohReferenceResolver()

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
                        capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL)
                    val mtu = linkPropertiesMtuOrNull(linkProps)
                    val ipv6Reachable =
                        measureHomeIpv6Reachability(
                            socketFactory = activeNetwork?.let { network -> { network.socketFactory.createSocket() } },
                            isNetworkCurrent = { activeNetwork != null && cm.activeNetwork == activeNetwork },
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
            awaitBlockingProbeWithin(NetworkProbeOverallTimeoutMs) {
                // Idle and loaded samples use one host, so the latency increase is not a host difference.
                val firstSample = BufferbloatRttHosts.firstNotNullOfOrNull { host -> measureSingleRtt(host)?.to(host) }
                val rttHost = firstSample?.second ?: BufferbloatRttHosts.first()
                val idle = listOfNotNull(firstSample?.first) + measureRttSamples(rttHost, BufferbloatProbes - 1)
                val (loaded, load) = withLoad { measureRttSamples(rttHost, BufferbloatProbes) }
                homeBufferbloatResult(idle, loaded, load)
            }

        override suspend fun dnsCharacterization(): HomeDnsCharacterization? =
            awaitBlockingProbeWithin(NetworkProbeOverallTimeoutMs) {
                coroutineScope {
                    val cm = context.getSystemService(ConnectivityManager::class.java)
                    val network = cm?.activeNetwork
                    val systemResolver = homeSystemResolver(network?.let { cm.getLinkProperties(it)?.dnsServers })
                    // The four lookups are independent; run them at once so one slow resolver does not add up.
                    val systemIps = async { resolveSystem(DnsControlHost) }
                    val canarySystemIps = async { resolveSystem(DnsCanaryHost) }
                    val dohControlIps = async { resolveDoh(DnsControlHost) }
                    val dohCanaryIps = async { resolveDoh(DnsCanaryHost) }
                    characterizeHomeDns(
                        systemIps.await(),
                        canarySystemIps.await(),
                        dohControlIps.await(),
                        dohCanaryIps.await(),
                    ).copy(systemResolver = systemResolver)
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

        private suspend fun resolveDoh(host: String): List<String>? =
            try {
                dohReference.resolve(host).toList().ifEmpty { null }
            } catch (_: IOException) {
                null
            }

        private fun measureRttSamples(
            host: String,
            probes: Int,
        ): List<HomeRttSample> =
            buildList {
                repeat(probes) {
                    measureSingleRtt(host)?.let { add(it) }
                }
            }

        private fun measureSingleRtt(host: String): HomeRttSample? =
            runCatching {
                Socket().use { socket ->
                    val startedNs = System.nanoTime()
                    socket.connect(
                        java.net.InetSocketAddress(host, BufferbloatTcpPort),
                        BufferbloatProbeTimeoutMs.toInt(),
                    )
                    val finishedNs = System.nanoTime()
                    HomeRttSample(startedNs, finishedNs, TimeUnit.NANOSECONDS.toMillis(finishedNs - startedNs))
                }
            }.getOrNull()

        private suspend fun withLoad(block: () -> List<HomeRttSample>): Pair<List<HomeRttSample>, HomeLoadEvidence> =
            coroutineScope {
                val started = CompletableDeferred<Boolean>()
                val loadJob = async(Dispatchers.IO) { downloadLoad(started) { isActive } }
                val result = if (started.await()) block() else emptyList()
                result to loadJob.await()
            }

        private fun downloadLoad(
            started: CompletableDeferred<Boolean>,
            isActive: () -> Boolean,
        ): HomeLoadEvidence {
            var bytesRead = 0L
            var firstByteNs: Long? = null
            var lastByteNs: Long? = null

            fun download(url: String): Boolean =
                runCatching {
                    val request = Request.Builder().url(url).build()
                    httpClient.newCall(request).execute().use { response ->
                        if (!response.isSuccessful) return@use false
                        response.body.byteStream().use { stream ->
                            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                            // A cancelled probe stops downloading at the next chunk; the cap keeps the data cost fixed.
                            while (isActive() && bytesRead < BufferbloatLoadBytes) {
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
            // The next target is tried only when the previous one sent no byte; a partial load is still a load.
            var successful = false
            for (url in BufferbloatLoadedUrls) {
                if (successful || firstByteNs != null || !isActive()) break
                successful = download(url)
            }
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
