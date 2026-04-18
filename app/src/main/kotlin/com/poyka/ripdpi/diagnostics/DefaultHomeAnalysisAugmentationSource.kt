package com.poyka.ripdpi.diagnostics

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.NetworkCapabilities
import android.os.Build
import com.poyka.ripdpi.data.NetworkFingerprintProvider
import com.poyka.ripdpi.services.RoutingProtectionCatalogService
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.Socket
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.system.measureTimeMillis

private const val BufferbloatProbes = 4
private const val BufferbloatProbeTimeoutMs = 1_500L
private const val BufferbloatLoadedTimeoutMs = 8_000L
private const val BufferbloatHost = "1.1.1.1"
private const val BufferbloatTcpPort = 443
private const val BufferbloatLoadedUrl = "https://speed.cloudflare.com/__down?bytes=2000000"
private const val BufferbloatGradeAMaxDelta = 5
private const val BufferbloatGradeBMaxDelta = 30
private const val BufferbloatGradeCMaxDelta = 100
private const val BufferbloatGradeDMaxDelta = 250
private const val DnsCharacterTimeoutMs = 4_000L
private const val DnsControlHost = "cloudflare.com"
private const val DnsCanaryHost = "youtube.com"
private const val DohEndpoint = "https://1.1.1.1/dns-query"
private const val NetworkProbeOverallTimeoutMs = 15_000L
private const val IPv6ProbeHost = "ipv6.google.com"
private const val IPv6ProbeTimeoutMs = 1_500L
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
                    val operator = describeOperatorOrSsid(capabilities)
                    val captivePortal =
                        capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL) == true
                    val mtu = linkProps?.mtu?.takeIf { it > 0 }
                    val ipv6Reachable =
                        withTimeoutOrNull(IPv6ProbeTimeoutMs) {
                            runCatching {
                                InetAddress.getAllByName(IPv6ProbeHost).any { it is Inet6Address }
                            }.getOrDefault(false)
                        }
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
                        val idle = measureRttMs(BufferbloatProbes)
                        val (loaded, _) =
                            withLoad {
                                measureRttMs(BufferbloatProbes)
                            }
                        val idleMs = idle?.toInt()
                        val loadedMs = loaded?.toInt()
                        val deltaMs = if (idleMs != null && loadedMs != null) loadedMs - idleMs else null
                        val grade =
                            when {
                                deltaMs == null -> HomeBufferbloatGrade.UNKNOWN
                                deltaMs <= BufferbloatGradeAMaxDelta -> HomeBufferbloatGrade.A
                                deltaMs <= BufferbloatGradeBMaxDelta -> HomeBufferbloatGrade.B
                                deltaMs <= BufferbloatGradeCMaxDelta -> HomeBufferbloatGrade.C
                                deltaMs <= BufferbloatGradeDMaxDelta -> HomeBufferbloatGrade.D
                                else -> HomeBufferbloatGrade.F
                            }
                        HomeBufferbloatResult(
                            grade = grade,
                            idleRttMs = idleMs,
                            loadedRttMs = loadedMs,
                            deltaMs = deltaMs,
                        )
                    }.getOrNull()
                }
            }

        override suspend fun dnsCharacterization(): HomeDnsCharacterization? =
            withTimeoutOrNull(NetworkProbeOverallTimeoutMs) {
                withContext(Dispatchers.IO) {
                    runCatching {
                        val systemIps = resolveSystem(DnsControlHost)
                        val canarySystemIps = resolveSystem(DnsCanaryHost)
                        val dohControlIps = resolveDoh(DnsControlHost)
                        val dohCanaryIps = resolveDoh(DnsCanaryHost)
                        val notes = mutableListOf<String>()
                        val poisoned = mutableListOf<String>()
                        val resolverClass =
                            when {
                                dohControlIps == null -> {
                                    notes += "DoH endpoint $DohEndpoint unreachable"
                                    HomeDnsResolverClass.DOH_UNREACHABLE
                                }

                                systemIps.isEmpty() -> {
                                    HomeDnsResolverClass.UNKNOWN
                                }

                                differingIpSets(systemIps, dohControlIps) -> {
                                    notes += "$DnsControlHost: system vs DoH disagree"
                                    poisoned += DnsControlHost
                                    if (dohCanaryIps != null && differingIpSets(canarySystemIps, dohCanaryIps)) {
                                        poisoned += DnsCanaryHost
                                    }
                                    HomeDnsResolverClass.POSSIBLE_POISONING
                                }

                                dohCanaryIps != null && differingIpSets(canarySystemIps, dohCanaryIps) -> {
                                    notes += "$DnsCanaryHost differs vs DoH ground truth"
                                    poisoned += DnsCanaryHost
                                    HomeDnsResolverClass.POSSIBLE_TRANSPARENT_PROXY
                                }

                                else -> {
                                    HomeDnsResolverClass.SYSTEM_RESOLVER_OK
                                }
                            }
                        HomeDnsCharacterization(
                            resolverClass = resolverClass,
                            systemResolver = systemIps.firstOrNull(),
                            dohEndpoint = DohEndpoint,
                            poisonedHosts = poisoned.distinct(),
                            notes = notes,
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

        @Suppress("DEPRECATION")
        private fun describeOperatorOrSsid(caps: NetworkCapabilities?): String? {
            caps ?: return null
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val info = caps.transportInfo
                if (info != null) {
                    val infoString = info.toString()
                    val ssidMatch = Regex("ssid=([^,\\s]+)").find(infoString)?.groupValues?.getOrNull(1)
                    if (!ssidMatch.isNullOrBlank()) return ssidMatch.trim('"')
                }
            }
            return null
        }

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

        private suspend fun measureRttMs(probes: Int): Long? {
            val samples = mutableListOf<Long>()
            repeat(probes) {
                val ms = measureSingleRtt() ?: return@repeat
                samples += ms
            }
            return samples.takeIf { it.isNotEmpty() }?.average()?.toLong()
        }

        private fun measureSingleRtt(): Long? =
            runCatching {
                Socket().use { socket ->
                    val elapsed =
                        measureTimeMillis {
                            socket.connect(
                                java.net.InetSocketAddress(BufferbloatHost, BufferbloatTcpPort),
                                BufferbloatProbeTimeoutMs.toInt(),
                            )
                        }
                    elapsed
                }
            }.getOrNull()

        private suspend fun <T> withLoad(block: suspend () -> T): Pair<T, Boolean> =
            coroutineScope {
                val loadJob =
                    async {
                        runCatching {
                            val request = Request.Builder().url(BufferbloatLoadedUrl).build()
                            httpClient.newCall(request).execute().use { response ->
                                response.body.bytes()
                                response.isSuccessful
                            }
                        }.getOrDefault(false)
                    }
                val result = block()
                val ok = loadJob.await()
                result to ok
            }

        private fun differingIpSets(
            a: List<String>,
            b: List<String>,
        ): Boolean {
            if (a.isEmpty() || b.isEmpty()) return false
            val aV4 = a.filter { runCatching { InetAddress.getByName(it) is Inet4Address }.getOrDefault(false) }.toSet()
            val bV4 = b.filter { runCatching { InetAddress.getByName(it) is Inet4Address }.getOrDefault(false) }.toSet()
            if (aV4.isEmpty() || bV4.isEmpty()) return false
            return (aV4 intersect bV4).isEmpty()
        }
    }
