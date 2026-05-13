package com.poyka.ripdpi.debug

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.SystemClock
import com.poyka.ripdpi.BuildConfig
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.net.URL
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.time.Instant
import java.time.format.DateTimeFormatter
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

private const val EmulatorHost = "10.0.2.2"
private const val DefaultTimeoutMs = 5_000L
private const val DefaultProxyPort = 1080
private const val DnsPort = 53
private const val TcpProbePayload = "ripdpi-tcp-probe-v1"
private const val UdpProbePayload = "ripdpi-udp-probe-v1"

enum class LabProfile {
    Emulator,
    PhysicalDevice,
    Custom,
    ;

    companion object {
        fun fromExtra(value: String?): LabProfile =
            when (value?.trim()?.lowercase()) {
                "device", "physical", "physicaldevice" -> PhysicalDevice
                "custom" -> Custom
                else -> Emulator
            }
    }
}

enum class ProbeMode {
    Proxy,
    Vpn,
    Relay,
    Diagnostics,
    ;

    companion object {
        fun fromExtra(value: String?): ProbeMode =
            entries.firstOrNull { it.name.equals(value?.trim(), ignoreCase = true) } ?: Vpn
    }
}

enum class ProbeVerdict {
    Pass,
    Degraded,
    Fail,
    Inconclusive,
}

data class NetworkProbeConfig(
    val profile: LabProfile,
    val mode: ProbeMode,
    val dnsServer: String,
    val httpUrl: String,
    val httpsUrl: String,
    val tcpHost: String,
    val tcpPort: Int,
    val udpHost: String,
    val udpPort: Int,
    val quicUrl: String?,
    val relayEndpoint: String?,
    val timeoutMs: Long,
    val requireVpnActive: Boolean,
    val requireProxyReady: Boolean,
    val dnsHostname: String = "ok.test",
    val proxyHost: String = "127.0.0.1",
    val proxyPort: Int = DefaultProxyPort,
) {
    companion object {
        fun fromIntent(intent: Intent): NetworkProbeConfig {
            val profile = LabProfile.fromExtra(intent.getStringExtra("profile"))
            val mode = ProbeMode.fromExtra(intent.getStringExtra("mode"))
            val labHost =
                when (profile) {
                    LabProfile.Emulator -> {
                        EmulatorHost
                    }

                    LabProfile.PhysicalDevice -> {
                        requireHost(intent.getStringExtra("lab_host") ?: intent.getStringExtra("host"), profile)
                    }

                    LabProfile.Custom -> {
                        requireHost(intent.getStringExtra("lab_host") ?: intent.getStringExtra("host"), profile)
                    }
                }
            val timeoutMs = intent.getLongExtra("timeout_ms", DefaultTimeoutMs).coerceAtLeast(1_000L)
            val requireVpnActive = intent.getBooleanExtra("require_vpn_active", mode == ProbeMode.Vpn)
            val requireProxyReady =
                intent.getBooleanExtra(
                    "require_proxy_ready",
                    mode == ProbeMode.Vpn || mode == ProbeMode.Proxy,
                )

            return NetworkProbeConfig(
                profile = profile,
                mode = mode,
                dnsServer =
                    intent.getStringExtra("dnsServer")
                        ?: intent.getStringExtra("dns_server")
                        ?: labHost,
                httpUrl =
                    intent.getStringExtra("httpUrl")
                        ?: intent.getStringExtra("http_url")
                        ?: "http://$labHost:8080/get",
                httpsUrl =
                    intent.getStringExtra("httpsUrl")
                        ?: intent.getStringExtra("https_url")
                        ?: "https://$labHost:8443/",
                tcpHost = intent.getStringExtra("tcpHost") ?: intent.getStringExtra("tcp_host") ?: labHost,
                tcpPort = intent.getIntExtra("tcpPort", intent.getIntExtra("tcp_port", 9000)),
                udpHost = intent.getStringExtra("udpHost") ?: intent.getStringExtra("udp_host") ?: labHost,
                udpPort = intent.getIntExtra("udpPort", intent.getIntExtra("udp_port", 9001)),
                quicUrl =
                    intent.getStringExtra("quicUrl")
                        ?: intent.getStringExtra("quic_url")
                        ?: "https://$labHost:9443/h3/ok",
                relayEndpoint =
                    intent.getStringExtra("relayEndpoint")
                        ?: intent.getStringExtra("relay_endpoint")
                        ?: "$labHost:10080",
                timeoutMs = timeoutMs,
                requireVpnActive = requireVpnActive,
                requireProxyReady = requireProxyReady,
                dnsHostname =
                    intent.getStringExtra("dnsHostname")
                        ?: intent.getStringExtra("dns_hostname")
                        ?: "ok.test",
                proxyHost =
                    intent.getStringExtra("proxyHost")
                        ?: intent.getStringExtra("proxy_host")
                        ?: "127.0.0.1",
                proxyPort = intent.getIntExtra("proxyPort", intent.getIntExtra("proxy_port", DefaultProxyPort)),
            )
        }

        private fun requireHost(
            host: String?,
            profile: LabProfile,
        ): String {
            require(!host.isNullOrBlank()) { "profile=$profile requires lab_host or host extra" }
            return host
        }
    }
}

data class DnsProbeResult(
    val attempted: Boolean,
    val ok: Boolean,
    val server: String,
    val hostname: String,
    val addresses: List<String>,
    val latencyMs: Long?,
    val errorCode: String?,
)

data class HttpProbeResult(
    val attempted: Boolean,
    val ok: Boolean,
    val url: String,
    val statusCode: Int?,
    val bodyBytes: Long?,
    val latencyMs: Long?,
    val errorCode: String?,
)

data class TlsProbeResult(
    val attempted: Boolean,
    val ok: Boolean,
    val url: String,
    val tlsVersion: String?,
    val alpn: String?,
    val latencyMs: Long?,
    val errorCode: String?,
)

data class TcpProbeResult(
    val attempted: Boolean,
    val ok: Boolean,
    val host: String,
    val port: Int,
    val echoed: Boolean,
    val latencyMs: Long?,
    val errorCode: String?,
)

data class UdpProbeResult(
    val attempted: Boolean,
    val ok: Boolean,
    val host: String,
    val port: Int,
    val echoed: Boolean,
    val latencyMs: Long?,
    val errorCode: String?,
)

data class QuicProbeResult(
    val attempted: Boolean,
    val ok: Boolean,
    val url: String,
    val httpStatus: Int?,
    val fallbackUsed: Boolean,
    val latencyMs: Long?,
    val errorCode: String?,
)

data class ProbeError(
    val stage: String,
    val code: String,
    val message: String,
    val recoverable: Boolean,
)

data class NetworkProbeResult(
    val runId: String,
    val timestampEpochMs: Long,
    val appVersionName: String,
    val appVersionCode: Long,
    val profile: LabProfile,
    val mode: ProbeMode,
    val vpnEstablished: Boolean,
    val tunActive: Boolean,
    val proxyReady: Boolean,
    val relayReady: Boolean?,
    val dns: DnsProbeResult,
    val http: HttpProbeResult,
    val https: TlsProbeResult,
    val tcp: TcpProbeResult,
    val udp: UdpProbeResult,
    val quic: QuicProbeResult?,
    val egressProtected: Boolean?,
    val ipv4RouteInstalled: Boolean?,
    val ipv6RouteInstalled: Boolean?,
    val fallbackUsed: Boolean?,
    val verdict: ProbeVerdict,
    val errors: List<ProbeError>,
) {
    fun toJson(): String =
        buildJsonObject {
            put("runId", JsonPrimitive(runId))
            put("timestampEpochMs", JsonPrimitive(timestampEpochMs))
            put("appVersionName", JsonPrimitive(appVersionName))
            put("appVersionCode", JsonPrimitive(appVersionCode))
            put("profile", JsonPrimitive(profile.name))
            put("mode", JsonPrimitive(mode.name))
            put("vpnEstablished", JsonPrimitive(vpnEstablished))
            put("tunActive", JsonPrimitive(tunActive))
            put("proxyReady", JsonPrimitive(proxyReady))
            put("relayReady", relayReady?.let(::JsonPrimitive) ?: JsonNull)
            put("dns", dns.toJson())
            put("http", http.toJson())
            put("https", https.toJson())
            put("tcp", tcp.toJson())
            put("udp", udp.toJson())
            put("quic", quic?.toJson() ?: JsonNull)
            put("egressProtected", egressProtected?.let(::JsonPrimitive) ?: JsonNull)
            put("ipv4RouteInstalled", ipv4RouteInstalled?.let(::JsonPrimitive) ?: JsonNull)
            put("ipv6RouteInstalled", ipv6RouteInstalled?.let(::JsonPrimitive) ?: JsonNull)
            put("fallbackUsed", fallbackUsed?.let(::JsonPrimitive) ?: JsonNull)
            put("verdict", JsonPrimitive(verdict.name))
            put(
                "errors",
                buildJsonArray {
                    errors.forEach { error ->
                        add(
                            buildJsonObject {
                                put("stage", JsonPrimitive(error.stage))
                                put("code", JsonPrimitive(error.code))
                                put("message", JsonPrimitive(error.message))
                                put("recoverable", JsonPrimitive(error.recoverable))
                            },
                        )
                    }
                },
            )
        }.toString()
}

private fun DnsProbeResult.toJson() =
    buildJsonObject {
        put("attempted", JsonPrimitive(attempted))
        put("ok", JsonPrimitive(ok))
        put("server", JsonPrimitive(server))
        put("hostname", JsonPrimitive(hostname))
        put(
            "addresses",
            buildJsonArray {
                addresses.forEach { address ->
                    add(JsonPrimitive(address))
                }
            },
        )
        put("latencyMs", latencyMs?.let(::JsonPrimitive) ?: JsonNull)
        put("errorCode", errorCode?.let(::JsonPrimitive) ?: JsonNull)
    }

private fun HttpProbeResult.toJson() =
    buildJsonObject {
        put("attempted", JsonPrimitive(attempted))
        put("ok", JsonPrimitive(ok))
        put("url", JsonPrimitive(redactUrl(url)))
        put("statusCode", statusCode?.let(::JsonPrimitive) ?: JsonNull)
        put("bodyBytes", bodyBytes?.let(::JsonPrimitive) ?: JsonNull)
        put("latencyMs", latencyMs?.let(::JsonPrimitive) ?: JsonNull)
        put("errorCode", errorCode?.let(::JsonPrimitive) ?: JsonNull)
    }

private fun TlsProbeResult.toJson() =
    buildJsonObject {
        put("attempted", JsonPrimitive(attempted))
        put("ok", JsonPrimitive(ok))
        put("url", JsonPrimitive(redactUrl(url)))
        put("tlsVersion", tlsVersion?.let(::JsonPrimitive) ?: JsonNull)
        put("alpn", alpn?.let(::JsonPrimitive) ?: JsonNull)
        put("latencyMs", latencyMs?.let(::JsonPrimitive) ?: JsonNull)
        put("errorCode", errorCode?.let(::JsonPrimitive) ?: JsonNull)
    }

private fun TcpProbeResult.toJson() =
    buildJsonObject {
        put("attempted", JsonPrimitive(attempted))
        put("ok", JsonPrimitive(ok))
        put("host", JsonPrimitive(host))
        put("port", JsonPrimitive(port))
        put("echoed", JsonPrimitive(echoed))
        put("latencyMs", latencyMs?.let(::JsonPrimitive) ?: JsonNull)
        put("errorCode", errorCode?.let(::JsonPrimitive) ?: JsonNull)
    }

private fun UdpProbeResult.toJson() =
    buildJsonObject {
        put("attempted", JsonPrimitive(attempted))
        put("ok", JsonPrimitive(ok))
        put("host", JsonPrimitive(host))
        put("port", JsonPrimitive(port))
        put("echoed", JsonPrimitive(echoed))
        put("latencyMs", latencyMs?.let(::JsonPrimitive) ?: JsonNull)
        put("errorCode", errorCode?.let(::JsonPrimitive) ?: JsonNull)
    }

private fun QuicProbeResult.toJson() =
    buildJsonObject {
        put("attempted", JsonPrimitive(attempted))
        put("ok", JsonPrimitive(ok))
        put("url", JsonPrimitive(redactUrl(url)))
        put("httpStatus", httpStatus?.let(::JsonPrimitive) ?: JsonNull)
        put("fallbackUsed", JsonPrimitive(fallbackUsed))
        put("latencyMs", latencyMs?.let(::JsonPrimitive) ?: JsonNull)
        put("errorCode", errorCode?.let(::JsonPrimitive) ?: JsonNull)
    }

class DebugLocalNetworkProbeRunner(
    private val context: Context,
) {
    fun run(config: NetworkProbeConfig): NetworkProbeResult {
        val vpnActive = isVpnActive()
        val proxyReady = checkProxyReady(config)
        val errors = mutableListOf<ProbeError>()

        if (config.requireVpnActive && !vpnActive) {
            errors += ProbeError("vpn", "VPN_NOT_ACTIVE", "Active network is not VPN transport", recoverable = true)
        }
        if (config.requireProxyReady && !proxyReady) {
            errors +=
                ProbeError(
                    "proxy",
                    "PROXY_NOT_READY",
                    "Local proxy did not accept a TCP connection",
                    recoverable = true,
                )
        }

        val dns = runDnsProbe(config).also { if (!it.ok) errors += it.toError("dns") }
        val http = runHttpProbe(config).also { if (!it.ok) errors += it.toError("http") }
        val https = runHttpsProbe(config).also { if (!it.ok) errors += it.toError("https") }
        val tcp = runTcpProbe(config).also { if (!it.ok) errors += it.toError("tcp") }
        val udp = runUdpProbe(config).also { if (!it.ok) errors += it.toError("udp") }
        val quic = runQuicProbe(config)
        if (quic != null && !quic.ok && quic.attempted) {
            errors += ProbeError("quic", quic.errorCode ?: "QUIC_FAILED", "QUIC probe failed", recoverable = true)
        }

        val hardFailure = errors.any { it.stage in setOf("vpn", "proxy", "dns", "http", "tcp") }
        val degraded = errors.isNotEmpty() || udp.errorCode != null || quic?.errorCode != null
        val verdict =
            when {
                hardFailure -> ProbeVerdict.Fail
                degraded -> ProbeVerdict.Degraded
                else -> ProbeVerdict.Pass
            }

        return NetworkProbeResult(
            runId =
                DateTimeFormatter.ISO_INSTANT.format(Instant.now()) +
                    "-${config.profile.name.lowercase()}-${config.mode.name.lowercase()}",
            timestampEpochMs = System.currentTimeMillis(),
            appVersionName = BuildConfig.VERSION_NAME,
            appVersionCode = BuildConfig.VERSION_CODE.toLong(),
            profile = config.profile,
            mode = config.mode,
            vpnEstablished = vpnActive,
            tunActive = vpnActive,
            proxyReady = proxyReady,
            relayReady = null,
            dns = dns,
            http = http,
            https = https,
            tcp = tcp,
            udp = udp,
            quic = quic,
            egressProtected = if (vpnActive) true else null,
            ipv4RouteInstalled = if (vpnActive) true else null,
            ipv6RouteInstalled = null,
            fallbackUsed = quic?.fallbackUsed,
            verdict = verdict,
            errors = errors,
        )
    }

    private fun isVpnActive(): Boolean {
        val connectivityManager = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val activeNetwork = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(activeNetwork) ?: return false
        return capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
    }

    private fun checkProxyReady(config: NetworkProbeConfig): Boolean =
        runCatching {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(config.proxyHost, config.proxyPort), config.timeoutMs.toInt())
            }
            true
        }.getOrDefault(false)

    private fun runDnsProbe(config: NetworkProbeConfig): DnsProbeResult {
        val startMs = SystemClock.elapsedRealtime()
        return runCatching {
            val requestId = (System.nanoTime() and 0xffff).toInt()
            val query = DebugDnsPacketCodec.buildQuery(config.dnsHostname, requestId)
            DatagramSocket().use { socket ->
                socket.soTimeout = config.timeoutMs.toInt()
                socket.connect(InetSocketAddress(config.dnsServer, DnsPort))
                socket.send(DatagramPacket(query, query.size))
                val response = ByteArray(1500)
                val packet = DatagramPacket(response, response.size)
                socket.receive(packet)
                val decoded =
                    DebugDnsPacketCodec.decodeResponse(
                        packet = response.copyOf(packet.length),
                        expectedRequestId = requestId,
                    )
                DnsProbeResult(
                    attempted = true,
                    ok = decoded.rcode == 0 && decoded.answers.isNotEmpty(),
                    server = config.dnsServer,
                    hostname = config.dnsHostname,
                    addresses = decoded.answers,
                    latencyMs = SystemClock.elapsedRealtime() - startMs,
                    errorCode = if (decoded.rcode == 0) null else "DNS_RCODE_${decoded.rcode}",
                )
            }
        }.getOrElse { error ->
            DnsProbeResult(
                true,
                false,
                config.dnsServer,
                config.dnsHostname,
                emptyList(),
                elapsedSince(startMs),
                error.errorCode(),
            )
        }
    }

    private fun runHttpProbe(config: NetworkProbeConfig): HttpProbeResult =
        runUrlProbe(config.httpUrl, config.timeoutMs, trustLabCertificate = false).toHttpProbeResult(config.httpUrl)

    private fun runHttpsProbe(config: NetworkProbeConfig): TlsProbeResult {
        val response = runUrlProbe(config.httpsUrl, config.timeoutMs, trustLabCertificate = true)
        return TlsProbeResult(
            attempted = true,
            ok = response.ok,
            url = config.httpsUrl,
            tlsVersion = response.tlsVersion,
            alpn = response.alpn,
            latencyMs = response.latencyMs,
            errorCode = response.errorCode,
        )
    }

    private fun runTcpProbe(config: NetworkProbeConfig): TcpProbeResult {
        val startMs = SystemClock.elapsedRealtime()
        return runCatching {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(config.tcpHost, config.tcpPort), config.timeoutMs.toInt())
                socket.soTimeout = config.timeoutMs.toInt()
                val payload = TcpProbePayload.toByteArray(StandardCharsets.UTF_8)
                socket.getOutputStream().write(payload)
                socket.getOutputStream().flush()
                val response = ByteArray(payload.size)
                var offset = 0
                while (offset < response.size) {
                    val read = socket.getInputStream().read(response, offset, response.size - offset)
                    if (read <= 0) break
                    offset += read
                }
                val echoed = response.copyOf(offset).contentEquals(payload)
                TcpProbeResult(true, echoed, config.tcpHost, config.tcpPort, echoed, elapsedSince(startMs), null)
            }
        }.getOrElse { error ->
            TcpProbeResult(true, false, config.tcpHost, config.tcpPort, false, elapsedSince(startMs), error.errorCode())
        }
    }

    private fun runUdpProbe(config: NetworkProbeConfig): UdpProbeResult {
        val startMs = SystemClock.elapsedRealtime()
        return runCatching {
            DatagramSocket().use { socket ->
                socket.soTimeout = config.timeoutMs.toInt()
                socket.connect(InetSocketAddress(config.udpHost, config.udpPort))
                val payload = UdpProbePayload.toByteArray(StandardCharsets.UTF_8)
                socket.send(DatagramPacket(payload, payload.size))
                val response = ByteArray(payload.size)
                val packet = DatagramPacket(response, response.size)
                socket.receive(packet)
                val echoed = response.copyOf(packet.length).contentEquals(payload)
                UdpProbeResult(true, echoed, config.udpHost, config.udpPort, echoed, elapsedSince(startMs), null)
            }
        }.getOrElse { error ->
            UdpProbeResult(true, false, config.udpHost, config.udpPort, false, elapsedSince(startMs), error.errorCode())
        }
    }

    private fun runQuicProbe(config: NetworkProbeConfig): QuicProbeResult? =
        config.quicUrl?.let {
            QuicProbeResult(
                attempted = false,
                ok = false,
                url = it,
                httpStatus = null,
                fallbackUsed = false,
                latencyMs = null,
                errorCode = "QUIC_UNSUPPORTED_ANDROID_DEBUG_PROBE",
            )
        }
}

fun NetworkProbeResult.writeToOutput(
    context: Context,
    outputPath: String?,
): File {
    val output =
        if (outputPath.isNullOrBlank()) {
            File(context.getExternalFilesDir(null) ?: context.filesDir, "probe-result.json")
        } else {
            File(outputPath)
        }
    output.parentFile?.mkdirs()
    output.writeText(toJson(), Charsets.UTF_8)
    return output
}

private data class UrlProbeResponse(
    val ok: Boolean,
    val statusCode: Int?,
    val bodyBytes: Long?,
    val latencyMs: Long?,
    val errorCode: String?,
    val tlsVersion: String? = null,
    val alpn: String? = null,
)

private fun runUrlProbe(
    url: String,
    timeoutMs: Long,
    trustLabCertificate: Boolean,
): UrlProbeResponse {
    val startMs = SystemClock.elapsedRealtime()
    return runCatching {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = timeoutMs.toInt()
        connection.readTimeout = timeoutMs.toInt()
        connection.requestMethod = "GET"
        if (trustLabCertificate && connection is HttpsURLConnection) {
            connection.sslSocketFactory = DebugLabTls.sslContext.socketFactory
            connection.hostnameVerifier = DebugLabTls.hostnameVerifier
        }
        val status = connection.responseCode
        val stream =
            if (status >=
                HttpURLConnection.HTTP_BAD_REQUEST
            ) {
                connection.errorStream
            } else {
                connection.inputStream
            }
        val bytes =
            stream?.use { input ->
                val buffer = ByteArrayOutputStream()
                input.copyTo(buffer)
                buffer.size().toLong()
            } ?: 0L
        val tlsVersion = (connection as? HttpsURLConnection)?.cipherSuite?.takeIf { it.isNotBlank() }
        connection.disconnect()
        UrlProbeResponse(
            ok = status in 200..399,
            statusCode = status,
            bodyBytes = bytes,
            latencyMs = elapsedSince(startMs),
            errorCode = if (status in 200..399) null else "HTTP_STATUS_$status",
            tlsVersion = tlsVersion,
            alpn = null,
        )
    }.getOrElse { error ->
        UrlProbeResponse(false, null, null, elapsedSince(startMs), error.errorCode())
    }
}

private object DebugLabTls {
    val sslContext: SSLContext =
        SSLContext.getInstance("TLS").apply {
            init(null, arrayOf(TrustAllManager), SecureRandom())
        }
    val hostnameVerifier = HostnameVerifier { _, _ -> true }

    private object TrustAllManager : X509TrustManager {
        override fun checkClientTrusted(
            chain: Array<out X509Certificate>?,
            authType: String?,
        ) = Unit

        override fun checkServerTrusted(
            chain: Array<out X509Certificate>?,
            authType: String?,
        ) = Unit

        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }
}

private fun UrlProbeResponse.toHttpProbeResult(url: String): HttpProbeResult =
    HttpProbeResult(
        attempted = true,
        ok = ok,
        url = url,
        statusCode = statusCode,
        bodyBytes = bodyBytes,
        latencyMs = latencyMs,
        errorCode = errorCode,
    )

private fun DnsProbeResult.toError(stage: String): ProbeError =
    ProbeError(stage, errorCode ?: "DNS_FAILED", "DNS probe failed for $hostname via $server", recoverable = true)

private fun HttpProbeResult.toError(stage: String): ProbeError =
    ProbeError(stage, errorCode ?: "HTTP_FAILED", "HTTP probe failed for ${redactUrl(url)}", recoverable = true)

private fun TlsProbeResult.toError(stage: String): ProbeError =
    ProbeError(stage, errorCode ?: "TLS_FAILED", "TLS probe failed for ${redactUrl(url)}", recoverable = true)

private fun TcpProbeResult.toError(stage: String): ProbeError =
    ProbeError(stage, errorCode ?: "TCP_FAILED", "TCP probe failed for $host:$port", recoverable = true)

private fun UdpProbeResult.toError(stage: String): ProbeError =
    ProbeError(stage, errorCode ?: "UDP_FAILED", "UDP probe failed for $host:$port", recoverable = true)

private fun Throwable.errorCode(): String =
    this::class.java.simpleName
        .ifBlank { "ERROR" }
        .uppercase()

private fun elapsedSince(startMs: Long): Long = SystemClock.elapsedRealtime() - startMs

private fun redactUrl(value: String): String =
    runCatching {
        val uri = URI(value)
        "${uri.scheme}://${uri.host}${if (uri.port > 0) ":${uri.port}" else ""}${uri.path.orEmpty()}"
    }.getOrDefault("<redacted-url>")
