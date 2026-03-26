package com.poyka.ripdpi.e2e

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import com.poyka.ripdpi.data.AppSettingsRepository
import com.poyka.ripdpi.data.DnsModeEncrypted
import com.poyka.ripdpi.data.DnsModePlainUdp
import com.poyka.ripdpi.data.DnsProviderCustom
import com.poyka.ripdpi.data.EncryptedDnsProtocolDnsCrypt
import com.poyka.ripdpi.data.EncryptedDnsProtocolDoh
import com.poyka.ripdpi.data.EncryptedDnsProtocolDoq
import com.poyka.ripdpi.data.EncryptedDnsProtocolDot
import com.poyka.ripdpi.data.ServiceTelemetrySnapshot
import com.poyka.ripdpi.debug.PacketSmokeEncryptedDnsPreset
import com.poyka.ripdpi.debug.PacketSmokeEncryptedDnsPresets
import com.poyka.ripdpi.debug.PacketSmokeMapDnsAddress
import com.poyka.ripdpi.debug.PacketSmokeMapDnsPort
import com.poyka.ripdpi.debug.DebugDnsPacketCodec
import java.io.BufferedReader
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet6Address
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.regex.Pattern
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509TrustManager
import kotlin.random.Random

private const val DefaultFixtureControlHost = "10.0.2.2"
private const val LoopbackFixtureHost = "127.0.0.1"
private const val DefaultFixtureControlPort = 46090
private const val FixtureControlRetryCount = 3
private const val FixtureControlRetryDelayMs = 100L
private const val PhysicalDeviceVpnConsentTimeoutMs = 20_000L
private const val EmulatorVpnConsentTimeoutMs = 10_000L
private const val VpnConsentRetryCount = 2
private const val VpnConsentShellGrantTimeoutMs = 2_000L
private const val LocalNetworkPermissionGrantTimeoutMs = 2_000L
private const val VpnConsentTimeoutArg = "ripdpi.vpnConsentTimeoutMs"
private const val VpnConsentPackageHintsArg = "ripdpi.vpnConsentPackageHints"
private const val PacketSmokeDeviceProfileArg = "ripdpi.packetSmokeDeviceProfile"
private const val NearbyWifiDevicesPermission = "android.permission.NEARBY_WIFI_DEVICES"
private const val DebugNetworkProbeAction = "com.poyka.ripdpi.debug.PROBE_TCP"
private const val DebugDnsProbeAction = "com.poyka.ripdpi.debug.PROBE_DNS"
private const val DebugNetworkProbeReceiverClass = "com.poyka.ripdpi.debug.DebugNetworkProbeReceiver"
private const val DebugNetworkProbeExtraHost = "host"
private const val DebugNetworkProbeExtraPort = "port"
private const val DebugNetworkProbeExtraConnectTimeoutMs = "connect_timeout_ms"
private const val DebugNetworkProbeExtraReadTimeoutMs = "read_timeout_ms"
private const val DebugNetworkProbeExtraPayload = "payload"
private const val DebugNetworkProbeExtraQueryHost = "query_host"
private const val DebugNetworkProbeExtraOk = "ok"
private const val DebugNetworkProbeExtraLocalAddress = "local_address"
private const val DebugNetworkProbeExtraLocalPort = "local_port"
private const val DebugNetworkProbeExtraResponse = "response"
private const val DebugNetworkProbeExtraDnsRcode = "rcode"
private const val DebugNetworkProbeExtraDnsAnswers = "answers"
private const val DebugNetworkProbeExtraDnsLatencyMs = "latency_ms"
private const val DebugNetworkProbeExtraErrorClass = "error_class"
private const val DebugNetworkProbeExtraErrorMessage = "error_message"
private const val DebugNetworkProbeTimeoutMs = 3_000L
private const val DebugNetworkProbeBroadcastTimeoutMs = 10_000L

data class FixtureManifestDto(
    val bindHost: String,
    val androidHost: String,
    val tcpEchoPort: Int,
    val udpEchoPort: Int,
    val tlsEchoPort: Int,
    val dnsUdpPort: Int,
    val dnsHttpPort: Int,
    val dnsDotPort: Int,
    val dnsDnscryptPort: Int,
    val dnsDoqPort: Int,
    val socks5Port: Int,
    val controlPort: Int,
    val fixtureDomain: String,
    val fixtureIpv4: String,
    val dnsAnswerIpv4: String,
    val tlsCertificatePem: String,
    val dnscryptProviderName: String,
    val dnscryptPublicKey: String,
)

data class FixtureEventDto(
    val service: String,
    val protocol: String,
    val peer: String,
    val target: String,
    val detail: String,
    val bytes: Int,
    val sni: String? = null,
    val createdAt: Long,
)

data class AppProcessTcpProbeResult(
    val host: String,
    val port: Int,
    val ok: Boolean,
    val localAddress: String? = null,
    val localPort: Int? = null,
    val response: String? = null,
    val errorClass: String? = null,
    val errorMessage: String? = null,
)

data class AppProcessDnsProbeResult(
    val queryHost: String,
    val serverHost: String,
    val serverPort: Int,
    val ok: Boolean,
    val rcode: Int? = null,
    val answers: List<String> = emptyList(),
    val latencyMs: Long? = null,
    val localAddress: String? = null,
    val localPort: Int? = null,
    val errorClass: String? = null,
    val errorMessage: String? = null,
)

data class PacketSmokeTelemetryDelta(
    val txPackets: Long,
    val rxPackets: Long,
    val txBytes: Long,
    val rxBytes: Long,
    val dnsQueriesTotal: Long,
    val dnsFailuresTotal: Long,
)

enum class PacketSmokeDeviceProfile(
    val argumentValue: String,
) {
    EMULATOR_RAW("emulator_raw"),
    ROOTED_ANDROID_PCAP("rooted_android_pcap"),
    PHYSICAL_INDIRECT("physical_indirect"),
    ;

    companion object {
        fun fromInstrumentationArgs(): PacketSmokeDeviceProfile {
            val value = InstrumentationRegistry.getArguments().getString(PacketSmokeDeviceProfileArg)?.trim()
            return entries.firstOrNull { it.argumentValue == value }
                ?: if (isLikelyEmulator()) {
                    EMULATOR_RAW
                } else {
                    PHYSICAL_INDIRECT
                }
        }
    }
}

enum class FixtureFaultScopeDto {
    ONE_SHOT,
    PERSISTENT,
}

enum class FixtureFaultTargetDto {
    TCP_ECHO,
    UDP_ECHO,
    TLS_ECHO,
    DNS_UDP,
    DNS_HTTP,
    DNS_DOT,
    DNS_DNSCRYPT,
    DNS_DOQ,
    SOCKS5_RELAY,
}

enum class FixtureFaultOutcomeDto {
    TCP_RESET,
    TCP_TRUNCATE,
    UDP_DROP,
    UDP_DELAY,
    TLS_ABORT,
    DNS_NX_DOMAIN,
    DNS_SERV_FAIL,
    DNS_TIMEOUT,
    SOCKS_REJECT_CONNECT,
}

data class FixtureFaultSpecDto(
    val target: FixtureFaultTargetDto,
    val outcome: FixtureFaultOutcomeDto,
    val scope: FixtureFaultScopeDto = FixtureFaultScopeDto.ONE_SHOT,
    val delayMs: Long? = null,
)

class LocalFixtureClient(
    private val controlHost: String,
    private val controlPort: Int,
) {
    fun manifest(): FixtureManifestDto =
        request("/manifest") { body ->
            parseManifest(body).normalizeForControlHost(controlHost)
        }

    fun events(): List<FixtureEventDto> =
        request("/events") { body ->
            parseEvents(body)
        }

    fun resetEvents() {
        withRetry {
            val connection = openConnection("/events/reset", method = "POST")
            connection.outputStream.use { output ->
                output.write(ByteArray(0))
            }
            connection.inputStream.close()
            connection.disconnect()
        }
    }

    fun setFault(spec: FixtureFaultSpecDto) {
        withRetry {
            val connection = openConnection("/faults", method = "POST")
            connection.outputStream.use { output ->
                val body =
                    org.json
                        .JSONObject()
                        .put("target", spec.target.name.lowercase())
                        .put("outcome", spec.outcome.name.lowercase())
                        .put("scope", spec.scope.name.lowercase())
                        .apply {
                            spec.delayMs?.let { put("delayMs", it) }
                        }.toString()
                output.write(body.toByteArray(StandardCharsets.UTF_8))
            }
            connection.inputStream.close()
            connection.disconnect()
        }
    }

    fun resetFaults() {
        withRetry {
            val connection = openConnection("/faults/reset", method = "POST")
            connection.outputStream.use { output ->
                output.write(ByteArray(0))
            }
            connection.inputStream.close()
            connection.disconnect()
        }
    }

    private fun <T> request(
        path: String,
        decode: (String) -> T,
    ): T =
        withRetry {
            val connection = openConnection(path)
            val body =
                connection.inputStream.bufferedReader().use { reader -> reader.readText() }
            connection.disconnect()
            decode(body)
        }

    private fun openConnection(
        path: String,
        method: String = "GET",
    ): HttpURLConnection =
        (URL("http://$controlHost:$controlPort$path").openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 5_000
            readTimeout = 5_000
            doInput = true
            if (method == "POST") {
                doOutput = true
            }
        }

    companion object {
        fun fromInstrumentationArgs(): LocalFixtureClient {
            val args = InstrumentationRegistry.getArguments()
            val host = args.getString("ripdpi.fixtureControlHost") ?: defaultFixtureControlHost()
            val port = args.getString("ripdpi.fixtureControlPort")?.toIntOrNull() ?: DefaultFixtureControlPort
            return LocalFixtureClient(host, port)
        }
    }
}

private inline fun <T> withRetry(block: () -> T): T {
    repeat(FixtureControlRetryCount - 1) {
        try {
            return block()
        } catch (_: IOException) {
            Thread.sleep(FixtureControlRetryDelayMs)
        }
    }
    return block()
}

private fun FixtureManifestDto.normalizeForControlHost(controlHost: String): FixtureManifestDto {
    if (androidHost == DefaultFixtureControlHost && controlHost != DefaultFixtureControlHost) {
        return copy(androidHost = controlHost)
    }
    return this
}

private fun defaultFixtureControlHost(): String =
    if (isLikelyEmulator()) {
        DefaultFixtureControlHost
    } else {
        LoopbackFixtureHost
    }

fun isLikelyEmulator(): Boolean {
    val fingerprint = Build.FINGERPRINT.lowercase()
    val hardware = Build.HARDWARE.lowercase()
    val model = Build.MODEL.lowercase()
    val product = Build.PRODUCT.lowercase()
    return "generic" in fingerprint ||
        "emulator" in fingerprint ||
        "ranchu" in hardware ||
        "goldfish" in hardware ||
        "sdk" in product ||
        "sdk_gphone" in product ||
        "emulator" in model ||
        "android sdk built for" in model
}

suspend fun AppSettingsRepository.applyFixtureEncryptedDns(
    fixture: FixtureManifestDto,
    proxyPort: Int,
    protocol: String = EncryptedDnsProtocolDoh,
) {
    val normalizedProtocol = protocol.trim().lowercase()
    val bootstrapIps = listOf(fixture.androidHost)
    val endpointHost =
        when (normalizedProtocol) {
            EncryptedDnsProtocolDot, EncryptedDnsProtocolDnsCrypt, EncryptedDnsProtocolDoq -> fixture.fixtureDomain
            else -> fixture.androidHost
        }
    val endpointPort =
        when (normalizedProtocol) {
            EncryptedDnsProtocolDot -> fixture.dnsDotPort
            EncryptedDnsProtocolDnsCrypt -> fixture.dnsDnscryptPort
            EncryptedDnsProtocolDoq -> fixture.dnsDoqPort
            else -> fixture.dnsHttpPort
        }
    val dohUrl =
        if (normalizedProtocol == EncryptedDnsProtocolDoh) {
            "http://${fixture.androidHost}:${fixture.dnsHttpPort}/dns-query"
        } else {
            ""
        }
    val tlsServerName =
        when (normalizedProtocol) {
            EncryptedDnsProtocolDot, EncryptedDnsProtocolDoq -> fixture.fixtureDomain
            EncryptedDnsProtocolDoh -> fixture.androidHost
            else -> ""
        }
    update {
        proxyIp = "127.0.0.1"
        this.proxyPort = proxyPort
        setDnsMode(DnsModeEncrypted)
        setDnsProviderId(DnsProviderCustom)
        setDnsIp(bootstrapIps.first())
        setDnsDohUrl(dohUrl)
        clearDnsDohBootstrapIps()
        addAllDnsDohBootstrapIps(if (dohUrl.isNotBlank()) bootstrapIps else emptyList())
        setEncryptedDnsProtocol(normalizedProtocol)
        setEncryptedDnsHost(endpointHost)
        setEncryptedDnsPort(endpointPort)
        setEncryptedDnsTlsServerName(tlsServerName)
        clearEncryptedDnsBootstrapIps()
        addAllEncryptedDnsBootstrapIps(bootstrapIps)
        setEncryptedDnsDohUrl(dohUrl)
        setEncryptedDnsDnscryptProviderName(
            if (normalizedProtocol == EncryptedDnsProtocolDnsCrypt) fixture.dnscryptProviderName else "",
        )
        setEncryptedDnsDnscryptPublicKey(
            if (normalizedProtocol == EncryptedDnsProtocolDnsCrypt) fixture.dnscryptPublicKey else "",
        )
    }
}

suspend fun AppSettingsRepository.applyPacketSmokePlainDns(
    proxyPort: Int,
    dnsIp: String,
) {
    update {
        proxyIp = "127.0.0.1"
        this.proxyPort = proxyPort
        setDnsMode(DnsModePlainUdp)
        setDnsProviderId(DnsProviderCustom)
        setDnsIp(dnsIp)
        setDnsDohUrl("")
        clearDnsDohBootstrapIps()
        setEncryptedDnsProtocol("")
        setEncryptedDnsHost("")
        setEncryptedDnsPort(0)
        setEncryptedDnsTlsServerName("")
        clearEncryptedDnsBootstrapIps()
        setEncryptedDnsDohUrl("")
        setEncryptedDnsDnscryptProviderName("")
        setEncryptedDnsDnscryptPublicKey("")
    }
}

suspend fun AppSettingsRepository.applyPacketSmokeEncryptedDns(
    proxyPort: Int,
    preset: PacketSmokeEncryptedDnsPreset,
) {
    update {
        proxyIp = "127.0.0.1"
        this.proxyPort = proxyPort
        setDnsMode(DnsModeEncrypted)
        setDnsProviderId(preset.providerId)
        setDnsIp(preset.bootstrapIps.firstOrNull().orEmpty())
        setDnsDohUrl(preset.dohUrl)
        clearDnsDohBootstrapIps()
        addAllDnsDohBootstrapIps(if (preset.dohUrl.isNotBlank()) preset.bootstrapIps else emptyList())
        setEncryptedDnsProtocol(preset.protocol)
        setEncryptedDnsHost(preset.host)
        setEncryptedDnsPort(preset.port)
        setEncryptedDnsTlsServerName(preset.tlsServerName)
        clearEncryptedDnsBootstrapIps()
        addAllEncryptedDnsBootstrapIps(preset.bootstrapIps)
        setEncryptedDnsDohUrl(preset.dohUrl)
        setEncryptedDnsDnscryptProviderName(preset.dnscryptProviderName)
        setEncryptedDnsDnscryptPublicKey(preset.dnscryptPublicKey)
    }
}

fun packetSmokeDeviceProfile(): PacketSmokeDeviceProfile = PacketSmokeDeviceProfile.fromInstrumentationArgs()

fun packetSmokeUsesPhysicalIndirectContract(): Boolean =
    packetSmokeDeviceProfile() == PacketSmokeDeviceProfile.PHYSICAL_INDIRECT

fun packetSmokeEncryptedDnsPreset(protocol: String): PacketSmokeEncryptedDnsPreset =
    PacketSmokeEncryptedDnsPresets.success(protocol)

fun packetSmokeEncryptedDnsFaultPreset(protocol: String): PacketSmokeEncryptedDnsPreset =
    PacketSmokeEncryptedDnsPresets.fault(protocol)

fun reserveLoopbackPort(): Int =
    ServerSocket(0).use { socket ->
        socket.localPort
    }

fun awaitUntil(
    timeoutMs: Long = 10_000,
    pollMs: Long = 50,
    condition: () -> Boolean,
) {
    val deadline = SystemClock.elapsedRealtime() + timeoutMs
    while (SystemClock.elapsedRealtime() < deadline) {
        if (condition()) {
            return
        }
        Thread.sleep(pollMs)
    }
    throw AssertionError("Timed out after ${timeoutMs}ms")
}

private data class VpnConsentUiObjectCandidate(
    val candidate: VpnConsentUiCandidate,
    val uiObject: UiObject2,
)

private data class VpnConsentAttemptSnapshot(
    val attempt: Int,
    val note: String,
    val activePackage: String?,
    val visiblePackages: List<String>,
    val attemptedSelectors: List<String>,
    val selectorMatches: List<String>,
    val fallbackCandidateCount: Int,
)

private data class VpnConsentArtifacts(
    val hierarchyPath: String?,
    val screenshotPath: String?,
)

fun ensureVpnConsentGranted(context: Context) {
    if (VpnService.prepare(context) == null) {
        return
    }

    val shellGrantAttempt = tryGrantVpnConsentViaShellAppOps(context)
    if (VpnService.prepare(context) == null) {
        return
    }

    val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
    val timeoutMs = vpnConsentTimeoutMs()
    val dialogPackages = vpnDialogPackages()
    val attempts = mutableListOf<VpnConsentAttemptSnapshot>()

    repeat(VpnConsentRetryCount) { attemptIndex ->
        if (VpnService.prepare(context) == null) {
            return
        }

        val consentIntent =
            requireNotNull(VpnService.prepare(context)) {
                "VPN consent intent disappeared unexpectedly"
            }.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(consentIntent)

        attemptGrantVpnConsent(
            context = context,
            device = device,
            dialogPackages = dialogPackages,
            timeoutMs = timeoutMs,
            attempt = attemptIndex + 1,
        )?.let(attempts::add) ?: return
    }

    val artifacts = captureVpnConsentArtifacts(context, device)
    throw AssertionError(
        buildString {
            appendLine("VPN consent dialog was not confirmed after $VpnConsentRetryCount attempts.")
            appendLine("dialogPackages=$dialogPackages")
            appendLine("timeoutMs=$timeoutMs")
            shellGrantAttempt?.let { appendLine("shellGrantAttempt=$it") }
            attempts.forEach { attempt ->
                appendLine(
                    "attempt ${attempt.attempt}: ${attempt.note}; " +
                        "activePackage=${attempt.activePackage ?: "<none>"}; " +
                        "visiblePackages=${attempt.visiblePackages}; " +
                        "attemptedSelectors=${attempt.attemptedSelectors}; " +
                        "selectorMatches=${attempt.selectorMatches}; " +
                        "fallbackCandidateCount=${attempt.fallbackCandidateCount}",
                )
            }
            artifacts.hierarchyPath?.let { appendLine("uiHierarchy=$it") }
            artifacts.screenshotPath?.let { appendLine("screenshot=$it") }
        }
    )
}

fun ensureLocalNetworkAccessGranted(context: Context) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
        return
    }
    if (ContextCompat.checkSelfPermission(context, NearbyWifiDevicesPermission) == PackageManager.PERMISSION_GRANTED) {
        return
    }

    val instrumentation = InstrumentationRegistry.getInstrumentation()
    val grantCommand = "pm grant ${context.packageName} $NearbyWifiDevicesPermission"
    val appOpsCommand = "cmd appops set ${context.packageName} NEARBY_WIFI_DEVICES allow"
    runCatching {
        val descriptor = instrumentation.uiAutomation.executeShellCommand(grantCommand)
        ParcelFileDescriptor.AutoCloseInputStream(descriptor).bufferedReader().use(BufferedReader::readText)
    }
    runCatching {
        val descriptor = instrumentation.uiAutomation.executeShellCommand(appOpsCommand)
        ParcelFileDescriptor.AutoCloseInputStream(descriptor).bufferedReader().use(BufferedReader::readText)
    }
    awaitUntil(timeoutMs = LocalNetworkPermissionGrantTimeoutMs, pollMs = 100) {
        ContextCompat.checkSelfPermission(context, NearbyWifiDevicesPermission) == PackageManager.PERMISSION_GRANTED
    }
}

fun selectReachableFixtureManifest(
    context: Context,
    fixture: FixtureManifestDto,
): FixtureManifestDto {
    val candidates =
        buildList {
            if (isLikelyEmulator()) {
                add(LoopbackFixtureHost)
            }
            if (fixture.androidHost != LoopbackFixtureHost) {
                add(fixture.androidHost)
            }
            if (!isLikelyEmulator()) {
                add(LoopbackFixtureHost)
            }
        }
            .distinct()
    val probes = candidates.map { host ->
        probeAppProcessTcpConnect(
            context = context,
            host = host,
            port = fixture.tcpEchoPort,
        )
    }
    val reachable = probes.firstOrNull(AppProcessTcpProbeResult::ok) ?: throw AssertionError(
        buildString {
            append("App process could not reach the local fixture TCP endpoint. ")
            append("Candidates: ")
            append(
                probes.joinToString { probe ->
                    val detail =
                        if (probe.ok) {
                            "ok local=${probe.localAddress}:${probe.localPort}"
                        } else {
                            "${probe.errorClass}: ${probe.errorMessage}"
                        }
                    "${probe.host}:${probe.port} -> $detail"
                },
            )
        },
    )
    return if (reachable.host == fixture.androidHost) {
        fixture
    } else {
        fixture.copy(androidHost = reachable.host)
    }
}

fun probeAppProcessTcpConnect(
    context: Context,
    host: String,
    port: Int,
    timeoutMs: Long = DebugNetworkProbeTimeoutMs,
): AppProcessTcpProbeResult {
    val latch = CountDownLatch(1)
    val probeResult = AtomicReference<AppProcessTcpProbeResult?>()
    val intent =
        Intent(DebugNetworkProbeAction).apply {
            setClassName(context.packageName, DebugNetworkProbeReceiverClass)
            putExtra(DebugNetworkProbeExtraHost, host)
            putExtra(DebugNetworkProbeExtraPort, port)
            putExtra(DebugNetworkProbeExtraConnectTimeoutMs, timeoutMs.toInt())
        }
    context.sendOrderedBroadcast(
        intent,
        null,
        object : BroadcastReceiver() {
            override fun onReceive(
                context: Context?,
                intent: Intent?,
            ) {
                val extras = getResultExtras(false) ?: Bundle.EMPTY
                probeResult.set(
                    AppProcessTcpProbeResult(
                        host = host,
                        port = port,
                        ok = resultCode == Activity.RESULT_OK && extras.getBoolean(DebugNetworkProbeExtraOk, false),
                        localAddress = extras.getString(DebugNetworkProbeExtraLocalAddress),
                        localPort = extras.getInt(DebugNetworkProbeExtraLocalPort).takeIf { it > 0 },
                        response = extras.getString(DebugNetworkProbeExtraResponse),
                        errorClass = extras.getString(DebugNetworkProbeExtraErrorClass),
                        errorMessage = extras.getString(DebugNetworkProbeExtraErrorMessage),
                    ),
                )
                latch.countDown()
            }
        },
        null,
        Activity.RESULT_CANCELED,
        null,
        null,
    )
    check(latch.await(DebugNetworkProbeBroadcastTimeoutMs, TimeUnit.MILLISECONDS)) {
        "Timed out waiting for app-process TCP probe for $host:$port"
    }
    return requireNotNull(probeResult.get()) {
        "App-process TCP probe did not deliver a result for $host:$port"
    }
}

fun probeInstrumentationTcpConnect(
    host: String,
    port: Int,
    timeoutMs: Long = DebugNetworkProbeTimeoutMs,
): AppProcessTcpProbeResult =
    runCatching {
        Socket().use { socket ->
            socket.connect(InetSocketAddress(host, port), timeoutMs.toInt())
            AppProcessTcpProbeResult(
                host = host,
                port = port,
                ok = true,
                localAddress = socket.localAddress?.hostAddress,
                localPort = socket.localPort,
            )
        }
    }.getOrElse { error ->
        AppProcessTcpProbeResult(
            host = host,
            port = port,
            ok = false,
            errorClass = error::class.java.name,
            errorMessage = error.message,
        )
    }

fun probeAppProcessDns(
    context: Context,
    queryHost: String,
    serverHost: String = PacketSmokeMapDnsAddress,
    serverPort: Int = PacketSmokeMapDnsPort,
    timeoutMs: Long = DebugNetworkProbeTimeoutMs,
): AppProcessDnsProbeResult {
    val latch = CountDownLatch(1)
    val probeResult = AtomicReference<AppProcessDnsProbeResult?>()
    val intent =
        Intent(DebugDnsProbeAction).apply {
            setClassName(context.packageName, DebugNetworkProbeReceiverClass)
            putExtra(DebugNetworkProbeExtraHost, serverHost)
            putExtra(DebugNetworkProbeExtraPort, serverPort)
            putExtra(DebugNetworkProbeExtraReadTimeoutMs, timeoutMs.toInt())
            putExtra(DebugNetworkProbeExtraQueryHost, queryHost)
        }
    context.sendOrderedBroadcast(
        intent,
        null,
        object : BroadcastReceiver() {
            override fun onReceive(
                context: Context?,
                intent: Intent?,
            ) {
                val extras = getResultExtras(false) ?: Bundle.EMPTY
                probeResult.set(
                    AppProcessDnsProbeResult(
                        queryHost = queryHost,
                        serverHost = serverHost,
                        serverPort = serverPort,
                        ok = resultCode == Activity.RESULT_OK && extras.getBoolean(DebugNetworkProbeExtraOk, false),
                        rcode =
                            extras
                                .takeIf { it.containsKey(DebugNetworkProbeExtraDnsRcode) }
                                ?.getInt(DebugNetworkProbeExtraDnsRcode),
                        answers = extras.getStringArrayList(DebugNetworkProbeExtraDnsAnswers).orEmpty(),
                        latencyMs =
                            extras
                                .takeIf { it.containsKey(DebugNetworkProbeExtraDnsLatencyMs) }
                                ?.getLong(DebugNetworkProbeExtraDnsLatencyMs),
                        localAddress = extras.getString(DebugNetworkProbeExtraLocalAddress),
                        localPort = extras.getInt(DebugNetworkProbeExtraLocalPort).takeIf { it > 0 },
                        errorClass = extras.getString(DebugNetworkProbeExtraErrorClass),
                        errorMessage = extras.getString(DebugNetworkProbeExtraErrorMessage),
                    ),
                )
                latch.countDown()
            }
        },
        null,
        Activity.RESULT_CANCELED,
        null,
        null,
    )
    check(latch.await(DebugNetworkProbeBroadcastTimeoutMs, TimeUnit.MILLISECONDS)) {
        "Timed out waiting for app-process DNS probe for $queryHost via $serverHost:$serverPort"
    }
    return requireNotNull(probeResult.get()) {
        "App-process DNS probe did not deliver a result for $queryHost via $serverHost:$serverPort"
    }
}

fun probeInstrumentationDns(
    queryHost: String,
    serverHost: String = PacketSmokeMapDnsAddress,
    serverPort: Int = PacketSmokeMapDnsPort,
    timeoutMs: Long = DebugNetworkProbeTimeoutMs,
): AppProcessDnsProbeResult =
    runCatching {
        val requestId = Random.nextInt(0, 0x1_0000)
        val query = DebugDnsPacketCodec.buildQuery(queryHost, requestId)
        val startedAt = SystemClock.elapsedRealtime()
        DatagramSocket().use { socket ->
            socket.soTimeout = timeoutMs.toInt()
            socket.connect(InetSocketAddress(serverHost, serverPort))
            val outgoingPacket = DatagramPacket(query, query.size)
            socket.send(outgoingPacket)

            val responseBytes = ByteArray(1500)
            val incomingPacket = DatagramPacket(responseBytes, responseBytes.size)
            socket.receive(incomingPacket)

            val decoded =
                DebugDnsPacketCodec.decodeResponse(
                    packet = responseBytes.copyOf(incomingPacket.length),
                    expectedRequestId = requestId,
                )
            AppProcessDnsProbeResult(
                queryHost = queryHost,
                serverHost = serverHost,
                serverPort = serverPort,
                ok = true,
                rcode = decoded.rcode,
                answers = decoded.answers,
                latencyMs = SystemClock.elapsedRealtime() - startedAt,
                localAddress = socket.localAddress?.hostAddress,
                localPort = socket.localPort,
            )
        }
    }.getOrElse { error ->
        AppProcessDnsProbeResult(
            queryHost = queryHost,
            serverHost = serverHost,
            serverPort = serverPort,
            ok = false,
            errorClass = error::class.java.name,
            errorMessage = error.message,
        )
    }

fun appProcessTcpRoundTrip(
    context: Context,
    host: String,
    port: Int,
    payload: String,
    connectTimeoutMs: Long = DebugNetworkProbeTimeoutMs,
    readTimeoutMs: Long = 5_000L,
): AppProcessTcpProbeResult {
    val latch = CountDownLatch(1)
    val probeResult = AtomicReference<AppProcessTcpProbeResult?>()
    val intent =
        Intent(DebugNetworkProbeAction).apply {
            setClassName(context.packageName, DebugNetworkProbeReceiverClass)
            putExtra(DebugNetworkProbeExtraHost, host)
            putExtra(DebugNetworkProbeExtraPort, port)
            putExtra(DebugNetworkProbeExtraConnectTimeoutMs, connectTimeoutMs.toInt())
            putExtra(DebugNetworkProbeExtraReadTimeoutMs, readTimeoutMs.toInt())
            putExtra(DebugNetworkProbeExtraPayload, payload)
        }
    context.sendOrderedBroadcast(
        intent,
        null,
        object : BroadcastReceiver() {
            override fun onReceive(
                context: Context?,
                intent: Intent?,
            ) {
                val extras = getResultExtras(false) ?: Bundle.EMPTY
                probeResult.set(
                    AppProcessTcpProbeResult(
                        host = host,
                        port = port,
                        ok = resultCode == Activity.RESULT_OK && extras.getBoolean(DebugNetworkProbeExtraOk, false),
                        localAddress = extras.getString(DebugNetworkProbeExtraLocalAddress),
                        localPort = extras.getInt(DebugNetworkProbeExtraLocalPort).takeIf { it > 0 },
                        response = extras.getString(DebugNetworkProbeExtraResponse),
                        errorClass = extras.getString(DebugNetworkProbeExtraErrorClass),
                        errorMessage = extras.getString(DebugNetworkProbeExtraErrorMessage),
                    ),
                )
                latch.countDown()
            }
        },
        null,
        Activity.RESULT_CANCELED,
        null,
        null,
    )
    check(latch.await(DebugNetworkProbeBroadcastTimeoutMs, TimeUnit.MILLISECONDS)) {
        "Timed out waiting for app-process TCP round-trip for $host:$port"
    }
    return requireNotNull(probeResult.get()) {
        "App-process TCP round-trip did not deliver a result for $host:$port"
    }
}

fun ServiceTelemetrySnapshot.packetSmokeDeltaFrom(before: ServiceTelemetrySnapshot): PacketSmokeTelemetryDelta =
    PacketSmokeTelemetryDelta(
        txPackets = tunnelStats.txPackets - before.tunnelStats.txPackets,
        rxPackets = tunnelStats.rxPackets - before.tunnelStats.rxPackets,
        txBytes = tunnelStats.txBytes - before.tunnelStats.txBytes,
        rxBytes = tunnelStats.rxBytes - before.tunnelStats.rxBytes,
        dnsQueriesTotal = tunnelTelemetry.dnsQueriesTotal - before.tunnelTelemetry.dnsQueriesTotal,
        dnsFailuresTotal = tunnelTelemetry.dnsFailuresTotal - before.tunnelTelemetry.dnsFailuresTotal,
    )

private fun tryGrantVpnConsentViaShellAppOps(context: Context): String? {
    val command = "cmd appops set ${context.packageName} ACTIVATE_VPN allow"
    return runCatching {
        val descriptor = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
        ParcelFileDescriptor.AutoCloseInputStream(descriptor).bufferedReader().use(BufferedReader::readText)
        awaitUntil(timeoutMs = VpnConsentShellGrantTimeoutMs, pollMs = 100) {
            VpnService.prepare(context) == null
        }
        command
    }.getOrNull()
}

private fun attemptGrantVpnConsent(
    context: Context,
    device: UiDevice,
    dialogPackages: List<String>,
    timeoutMs: Long,
    attempt: Int,
): VpnConsentAttemptSnapshot? {
    val deadline = SystemClock.elapsedRealtime() + timeoutMs
    var lastSnapshot =
        VpnConsentAttemptSnapshot(
            attempt = attempt,
            note = "VPN consent dialog not observed yet.",
            activePackage = null,
            visiblePackages = emptyList(),
            attemptedSelectors = VpnConsentUiSelector.defaultPositiveButtonResources,
            selectorMatches = emptyList(),
            fallbackCandidateCount = 0,
        )

    while (SystemClock.elapsedRealtime() < deadline) {
        if (VpnService.prepare(context) == null) {
            return null
        }

        dialogPackages.forEach { packageName ->
            device.wait(Until.hasObject(By.pkg(packageName)), 250)
        }

        val activePackage = activeVpnDialogPackage(device, dialogPackages)
        val knownCandidates = resolveKnownPositiveButtonCandidates(device)
        val fallbackCandidates = resolveFallbackCandidates(device)
        val selectorMatches = knownCandidates.mapNotNull { it.candidate.resourceName }.distinct()
        val visiblePackages = visiblePackages(device, activePackage)
        val positive =
            selectKnownPositiveButton(activePackage, knownCandidates)
                ?: selectFallbackPositiveButton(activePackage, fallbackCandidates)

        lastSnapshot =
            VpnConsentAttemptSnapshot(
                attempt = attempt,
                note =
                    when {
                        activePackage == null -> "Waiting for VPN dialog package to become active."
                        positive == null -> "Dialog is visible, but no positive action candidate matched yet."
                        else -> "Positive action candidate resolved for VPN consent dialog."
                    },
                activePackage = activePackage,
                visiblePackages = visiblePackages,
                attemptedSelectors = VpnConsentUiSelector.defaultPositiveButtonResources,
                selectorMatches = selectorMatches,
                fallbackCandidateCount = fallbackCandidates.count { it.candidate.packageName == activePackage },
            )

        if (positive != null) {
            positive.click()
            val granted =
                runCatching {
                    awaitUntil(timeoutMs = 10_000) {
                        VpnService.prepare(context) == null
                    }
                    true
                }.getOrDefault(false)
            if (granted) {
                return null
            }
            return lastSnapshot.copy(
                note = "Clicked candidate ${describeCandidate(positive)} but VPN consent remained required.",
            )
        }

        device.waitForIdle(250)
    }

    return lastSnapshot.copy(
        note = "${lastSnapshot.note} Timed out after ${timeoutMs}ms.",
    )
}

private fun selectKnownPositiveButton(
    activePackage: String?,
    candidates: List<VpnConsentUiObjectCandidate>,
): UiObject2? {
    val selected =
        VpnConsentUiSelector.selectKnownPositiveButton(
            activeDialogPackage = activePackage,
            candidates = candidates.map(VpnConsentUiObjectCandidate::candidate),
        ) ?: return null
    return candidates.firstOrNull { it.candidate == selected }?.uiObject
}

private fun selectFallbackPositiveButton(
    activePackage: String?,
    candidates: List<VpnConsentUiObjectCandidate>,
): UiObject2? {
    val selected =
        VpnConsentUiSelector.selectFallbackPositiveButton(
            activeDialogPackage = activePackage,
            candidates = candidates.map(VpnConsentUiObjectCandidate::candidate),
        ) ?: return null
    return candidates.firstOrNull { it.candidate == selected }?.uiObject
}

private fun resolveKnownPositiveButtonCandidates(device: UiDevice): List<VpnConsentUiObjectCandidate> =
    VpnConsentUiSelector.defaultPositiveButtonResources.mapNotNull { resourceName ->
        device.findObject(By.res(resourceName))?.let { uiObject ->
            VpnConsentUiObjectCandidate(
                candidate = uiCandidate(uiObject),
                uiObject = uiObject,
            )
        }
    }

private fun resolveFallbackCandidates(device: UiDevice): List<VpnConsentUiObjectCandidate> =
    device.findObjects(By.clickable(true)).map { uiObject ->
        VpnConsentUiObjectCandidate(
            candidate = uiCandidate(uiObject),
            uiObject = uiObject,
        )
    }

private fun uiCandidate(uiObject: UiObject2): VpnConsentUiCandidate {
    val bounds = uiObject.visibleBounds
    return VpnConsentUiCandidate(
        packageName = uiObject.applicationPackage,
        resourceName = uiObject.resourceName,
        clickable = uiObject.isClickable,
        enabled = uiObject.isEnabled,
        bottom = bounds.bottom,
        right = bounds.right,
    )
}

private fun activeVpnDialogPackage(
    device: UiDevice,
    dialogPackages: List<String>,
): String? {
    val currentPackage = runCatching { device.currentPackageName }.getOrNull()
    if (currentPackage != null && currentPackage in dialogPackages) {
        return currentPackage
    }

    return dialogPackages.firstOrNull { packageName ->
        device.findObject(By.pkg(packageName)) != null
    }
}

private fun visiblePackages(
    device: UiDevice,
    activePackage: String?,
): List<String> =
    buildSet {
        activePackage?.let(::add)
        device.findObjects(By.clickable(true)).mapNotNullTo(this) { it.applicationPackage }
    }.toList().sorted()

private fun describeCandidate(candidate: UiObject2): String =
    buildString {
        append("package=${candidate.applicationPackage ?: "<unknown>"}")
        append(", resource=${candidate.resourceName ?: "<none>"}")
        candidate.text?.takeIf { it.isNotBlank() }?.let { append(", text=$it") }
    }

private fun vpnConsentTimeoutMs(): Long {
    val configuredTimeout =
        InstrumentationRegistry
            .getArguments()
            .getString(VpnConsentTimeoutArg)
            ?.toLongOrNull()
    return configuredTimeout ?: if (isLikelyEmulator()) EmulatorVpnConsentTimeoutMs else PhysicalDeviceVpnConsentTimeoutMs
}

private fun vpnConsentPackageHints(): List<String> =
    InstrumentationRegistry
        .getArguments()
        .getString(VpnConsentPackageHintsArg)
        ?.split(',')
        .orEmpty()

private fun vpnDialogPackages(): List<String> =
    VpnConsentUiSelector.orderedDialogPackages(vpnConsentPackageHints())

private fun captureVpnConsentArtifacts(
    context: Context,
    device: UiDevice,
): VpnConsentArtifacts {
    val directory = File(context.cacheDir, "vpn-consent-diagnostics").apply { mkdirs() }
    val prefix = "vpn-consent-${System.currentTimeMillis()}"
    val hierarchyPath =
        File(directory, "$prefix.xml").let { file ->
            if (runCatching { device.dumpWindowHierarchy(file) }.isSuccess) {
                file.absolutePath
            } else {
                null
            }
        }
    val screenshotPath =
        File(directory, "$prefix.png").let { file ->
            if (runCatching { device.takeScreenshot(file) }.getOrDefault(false)) {
                file.absolutePath
            } else {
                null
            }
        }
    return VpnConsentArtifacts(
        hierarchyPath = hierarchyPath,
        screenshotPath = screenshotPath,
    )
}

fun socksTcpRoundTrip(
    proxyPort: Int,
    host: String,
    port: Int,
    payload: ByteArray,
): ByteArray {
    val socket = socksConnect(proxyPort, host, port)
    socket.getOutputStream().write(payload)
    socket.getOutputStream().flush()
    return socket.inputStream.readNBytes(payload.size).also { socket.close() }
}

fun directTcpRoundTrip(
    host: String,
    port: Int,
    payload: ByteArray,
): ByteArray {
    val socket = Socket()
    socket.connect(InetSocketAddress(host, port), 5_000)
    socket.soTimeout = 5_000
    socket.getOutputStream().write(payload)
    socket.getOutputStream().flush()
    return socket.inputStream.readNBytes(payload.size).also { socket.close() }
}

fun socksTlsHandshake(
    proxyPort: Int,
    targetHost: String,
    targetPort: Int,
    sniHost: String,
): String {
    val socket = socksConnect(proxyPort, targetHost, targetPort)
    return tlsHandshake(socket, sniHost, targetPort)
}

fun directTlsHandshake(
    targetHost: String,
    targetPort: Int,
    sniHost: String,
): String {
    val socket = Socket()
    socket.connect(InetSocketAddress(targetHost, targetPort), 5_000)
    socket.soTimeout = 5_000
    return tlsHandshake(socket, sniHost, targetPort)
}

private fun tlsHandshake(
    socket: Socket,
    sniHost: String,
    targetPort: Int,
): String {
    val trustAll =
        object : X509TrustManager {
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
    val sslContext = SSLContext.getInstance("TLS")
    sslContext.init(null, arrayOf(trustAll), SecureRandom())
    val sslSocket =
        sslContext.socketFactory.createSocket(socket, sniHost, targetPort, true) as SSLSocket
    sslSocket.startHandshake()
    return sslSocket.inputStream.bufferedReader().use(BufferedReader::readText)
}

fun execShell(command: String): String {
    val descriptor = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
    return ParcelFileDescriptor.AutoCloseInputStream(descriptor).bufferedReader().use(BufferedReader::readText)
}

private fun parseManifest(body: String): FixtureManifestDto {
    val json = org.json.JSONObject(body)
    return FixtureManifestDto(
        bindHost = json.getString("bindHost"),
        androidHost = json.getString("androidHost"),
        tcpEchoPort = json.getInt("tcpEchoPort"),
        udpEchoPort = json.getInt("udpEchoPort"),
        tlsEchoPort = json.getInt("tlsEchoPort"),
        dnsUdpPort = json.getInt("dnsUdpPort"),
        dnsHttpPort = json.getInt("dnsHttpPort"),
        dnsDotPort = json.getInt("dnsDotPort"),
        dnsDnscryptPort = json.getInt("dnsDnscryptPort"),
        dnsDoqPort = json.getInt("dnsDoqPort"),
        socks5Port = json.getInt("socks5Port"),
        controlPort = json.getInt("controlPort"),
        fixtureDomain = json.getString("fixtureDomain"),
        fixtureIpv4 = json.getString("fixtureIpv4"),
        dnsAnswerIpv4 = json.getString("dnsAnswerIpv4"),
        tlsCertificatePem = json.getString("tlsCertificatePem"),
        dnscryptProviderName = json.getString("dnscryptProviderName"),
        dnscryptPublicKey = json.getString("dnscryptPublicKey"),
    )
}

private fun parseEvents(body: String): List<FixtureEventDto> {
    val array = org.json.JSONArray(body)
    return buildList(array.length()) {
        for (index in 0 until array.length()) {
            val json = array.getJSONObject(index)
            add(
                FixtureEventDto(
                    service = json.getString("service"),
                    protocol = json.getString("protocol"),
                    peer = json.getString("peer"),
                    target = json.getString("target"),
                    detail = json.getString("detail"),
                    bytes = json.getInt("bytes"),
                    sni = json.optString("sni").takeIf { it.isNotBlank() },
                    createdAt = json.getLong("createdAt"),
                ),
            )
        }
    }
}

private fun socksConnect(
    proxyPort: Int,
    host: String,
    port: Int,
): Socket {
    val socket = Socket()
    socket.connect(InetSocketAddress("127.0.0.1", proxyPort), 5_000)
    socket.soTimeout = 5_000
    val output = socket.getOutputStream()
    val input = socket.getInputStream()

    output.write(byteArrayOf(0x05, 0x01, 0x00))
    check(input.readNBytes(2).contentEquals(byteArrayOf(0x05, 0x00))) { "SOCKS auth failed" }

    val request =
        buildList {
            add(0x05)
            add(0x01)
            add(0x00)
            val numericHost = numericHostBytes(host)
            if (numericHost != null) {
                add(numericHost.first)
                numericHost.second.forEach { add(it.toInt() and 0xff) }
            } else {
                val hostBytes = host.toByteArray(StandardCharsets.UTF_8)
                add(0x03)
                add(hostBytes.size)
                hostBytes.forEach { add(it.toInt() and 0xff) }
            }
            add((port shr 8) and 0xff)
            add(port and 0xff)
        }.map(Int::toByte).toByteArray()
    output.write(request)

    val reply = input.readNBytes(4)
    check(reply.size == 4 && reply[1] == 0.toByte()) { "SOCKS connect failed: ${reply.toList()}" }
    when (reply[3].toInt() and 0xff) {
        0x01 -> {
            input.readNBytes(6)
        }

        0x03 -> {
            val length = input.read()
            input.readNBytes(length + 2)
        }

        0x04 -> {
            input.readNBytes(18)
        }
    }
    return socket
}

private fun numericHostBytes(host: String): Pair<Int, ByteArray>? {
    ipv4LiteralBytes(host)?.let { return 0x01 to it }
    ipv6LiteralBytes(host)?.let { return 0x04 to it }
    return null
}

private fun ipv4LiteralBytes(host: String): ByteArray? {
    val parts = host.split('.')
    if (parts.size != 4) {
        return null
    }
    val octets = ByteArray(4)
    for ((index, part) in parts.withIndex()) {
        val value = part.toIntOrNull() ?: return null
        if (value !in 0..255) {
            return null
        }
        octets[index] = value.toByte()
    }
    return octets
}

private fun ipv6LiteralBytes(host: String): ByteArray? {
    if (!host.contains(':')) {
        return null
    }
    return runCatching { java.net.InetAddress.getByName(host) }.getOrNull()?.let { address ->
        if (address is Inet6Address) {
            address.address
        } else {
            null
        }
    }
}
