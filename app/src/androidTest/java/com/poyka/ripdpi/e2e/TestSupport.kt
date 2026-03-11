package com.poyka.ripdpi.e2e

import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import java.io.BufferedReader
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.regex.Pattern
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509TrustManager
private const val DefaultFixtureControlHost = "10.0.2.2"
private const val DefaultFixtureControlPort = 46090

data class FixtureManifestDto(
    val bindHost: String,
    val androidHost: String,
    val tcpEchoPort: Int,
    val udpEchoPort: Int,
    val tlsEchoPort: Int,
    val dnsUdpPort: Int,
    val dnsHttpPort: Int,
    val socks5Port: Int,
    val controlPort: Int,
    val fixtureDomain: String,
    val fixtureIpv4: String,
    val dnsAnswerIpv4: String,
    val tlsCertificatePem: String,
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
            parseManifest(body)
        }

    fun events(): List<FixtureEventDto> =
        request("/events") { body ->
            parseEvents(body)
        }

    fun resetEvents() {
        val connection = openConnection("/events/reset", method = "POST")
        connection.outputStream.use { output ->
            output.write(ByteArray(0))
        }
        connection.inputStream.close()
        connection.disconnect()
    }

    fun setFault(spec: FixtureFaultSpecDto) {
        val connection = openConnection("/faults", method = "POST")
        connection.outputStream.use { output ->
            val body =
                org.json.JSONObject()
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

    fun resetFaults() {
        val connection = openConnection("/faults/reset", method = "POST")
        connection.outputStream.use { output ->
            output.write(ByteArray(0))
        }
        connection.inputStream.close()
        connection.disconnect()
    }

    private fun <T> request(
        path: String,
        decode: (String) -> T,
    ): T {
        val connection = openConnection(path)
        val body =
            connection.inputStream.bufferedReader().use { reader -> reader.readText() }
        connection.disconnect()
        return decode(body)
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
            val host = args.getString("ripdpi.fixtureControlHost") ?: DefaultFixtureControlHost
            val port = args.getString("ripdpi.fixtureControlPort")?.toIntOrNull() ?: DefaultFixtureControlPort
            return LocalFixtureClient(host, port)
        }
    }
}

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

fun ensureVpnPrepared(context: Context) {
    if (VpnService.prepare(context) == null) {
        return
    }

    val consentIntent =
        requireNotNull(VpnService.prepare(context)) {
            "VPN consent intent disappeared unexpectedly"
        }.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    context.startActivity(consentIntent)
    val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
    val deadline = SystemClock.elapsedRealtime() + 10_000

    while (SystemClock.elapsedRealtime() < deadline) {
        val positive =
            device.findObject(By.res("android:id/button1"))
                ?: device.findObject(By.text(Pattern.compile("(?i)ok|allow|continue")))
        if (positive != null) {
            positive.click()
            awaitUntil(timeoutMs = 10_000) {
                VpnService.prepare(context) == null
            }
            return
        }
        device.waitForIdle(250)
    }

    throw AssertionError("VPN consent dialog was not confirmed")
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

fun httpConnectTlsHandshake(
    proxyPort: Int,
    targetHost: String,
    targetPort: Int,
    sniHost: String,
): String {
    val socket = Socket()
    socket.connect(InetSocketAddress("127.0.0.1", proxyPort), 5_000)
    socket.soTimeout = 5_000
    socket.getOutputStream().write(
        "CONNECT $targetHost:$targetPort HTTP/1.1\r\nHost: $targetHost:$targetPort\r\n\r\n".toByteArray(),
    )
    val headers = readHttpHeaders(socket.getInputStream())
    check(headers.contains("HTTP/1.1 200 OK")) { "CONNECT failed: $headers" }

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

fun execShell(command: String): String =
    UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).executeShellCommand(command)

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
        socks5Port = json.getInt("socks5Port"),
        controlPort = json.getInt("controlPort"),
        fixtureDomain = json.getString("fixtureDomain"),
        fixtureIpv4 = json.getString("fixtureIpv4"),
        dnsAnswerIpv4 = json.getString("dnsAnswerIpv4"),
        tlsCertificatePem = json.getString("tlsCertificatePem"),
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

    val hostBytes = host.toByteArray(StandardCharsets.UTF_8)
    val request =
        buildList {
            add(0x05)
            add(0x01)
            add(0x00)
            add(0x03)
            add(hostBytes.size)
            hostBytes.forEach { add(it.toInt() and 0xff) }
            add((port shr 8) and 0xff)
            add(port and 0xff)
        }.map(Int::toByte).toByteArray()
    output.write(request)

    val reply = input.readNBytes(4)
    check(reply.size == 4 && reply[1] == 0.toByte()) { "SOCKS connect failed: ${reply.toList()}" }
    when (reply[3].toInt() and 0xff) {
        0x01 -> input.readNBytes(6)
        0x03 -> {
            val length = input.read()
            input.readNBytes(length + 2)
        }
        0x04 -> input.readNBytes(18)
    }
    return socket
}

private fun readHttpHeaders(input: InputStream): String {
    val buffer = StringBuilder()
    val bytes = ByteArray(1)
    while (!buffer.endsWith("\r\n\r\n")) {
        val read = input.read(bytes)
        if (read <= 0) {
            break
        }
        buffer.append(String(bytes, 0, read, StandardCharsets.UTF_8))
    }
    return buffer.toString()
}
