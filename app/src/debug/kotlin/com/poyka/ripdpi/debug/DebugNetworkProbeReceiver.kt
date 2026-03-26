package com.poyka.ripdpi.debug

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import java.io.ByteArrayOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.charset.StandardCharsets
import kotlin.random.Random
import kotlin.concurrent.thread

class DebugNetworkProbeReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        if (intent.action != ActionProbeTcp && intent.action != ActionProbeDns) {
            return
        }

        val pendingResult = goAsync()

        thread(name = "debug-network-probe", isDaemon = true) {
            val extras = Bundle()
            val resultCode =
                runCatching {
                    when (intent.action) {
                        ActionProbeDns -> runDnsProbe(intent, extras)
                        else -> runTcpProbe(intent, extras)
                    }

                    Activity.RESULT_OK
                }.getOrElse { error ->
                    val failure = error.toDebugProbeFailure()
                    extras.putBoolean(ExtraOk, false)
                    extras.putString(ExtraErrorClass, failure.errorClass)
                    extras.putString(ExtraErrorMessage, failure.errorMessage)
                    Activity.RESULT_CANCELED
                }

            pendingResult.resultCode = resultCode
            pendingResult.setResultExtras(extras)
            pendingResult.finish()
        }
    }

    companion object {
        const val ActionProbeTcp = "com.poyka.ripdpi.debug.PROBE_TCP"
        const val ActionProbeDns = "com.poyka.ripdpi.debug.PROBE_DNS"
        const val ExtraHost = "host"
        const val ExtraPort = "port"
        const val ExtraConnectTimeoutMs = "connect_timeout_ms"
        const val ExtraReadTimeoutMs = "read_timeout_ms"
        const val ExtraPayload = "payload"
        const val ExtraQueryHost = "query_host"
        const val ExtraOk = "ok"
        const val ExtraLocalAddress = "local_address"
        const val ExtraLocalPort = "local_port"
        const val ExtraResponse = "response"
        const val ExtraDnsRcode = "rcode"
        const val ExtraDnsAnswers = "answers"
        const val ExtraDnsLatencyMs = "latency_ms"
        const val ExtraErrorClass = "error_class"
        const val ExtraErrorMessage = "error_message"

        private const val DefaultConnectTimeoutMs = 3_000
        private const val DefaultReadTimeoutMs = 5_000
    }

    private fun runTcpProbe(
        intent: Intent,
        extras: Bundle,
    ) {
        val host = intent.getStringExtra(ExtraHost)
        val port = intent.getIntExtra(ExtraPort, -1)
        val connectTimeoutMs = intent.getIntExtra(ExtraConnectTimeoutMs, DefaultConnectTimeoutMs)
        val readTimeoutMs = intent.getIntExtra(ExtraReadTimeoutMs, DefaultReadTimeoutMs)
        val payload = intent.getStringExtra(ExtraPayload)

        require(!host.isNullOrBlank()) { "Missing host extra" }
        require(port in 1..65535) { "Invalid port extra: $port" }

        Socket().use { socket ->
            socket.connect(InetSocketAddress(host, port), connectTimeoutMs)
            socket.soTimeout = readTimeoutMs
            extras.putBoolean(ExtraOk, true)
            extras.putString(ExtraLocalAddress, socket.localAddress?.hostAddress)
            extras.putInt(ExtraLocalPort, socket.localPort)

            if (payload != null) {
                val output = socket.getOutputStream()
                output.write(payload.toByteArray(StandardCharsets.UTF_8))
                output.flush()
                socket.shutdownOutput()

                val response = ByteArrayOutputStream()
                socket.getInputStream().use { input ->
                    val buffer = ByteArray(4 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) {
                            break
                        }
                        response.write(buffer, 0, read)
                    }
                }
                extras.putString(ExtraResponse, response.toString(StandardCharsets.UTF_8.name()))
            }
        }
    }

    private fun runDnsProbe(
        intent: Intent,
        extras: Bundle,
    ) {
        val serverHost = intent.getStringExtra(ExtraHost)
        val serverPort = intent.getIntExtra(ExtraPort, -1)
        val timeoutMs = intent.getIntExtra(ExtraReadTimeoutMs, DefaultReadTimeoutMs)
        val queryHost = intent.getStringExtra(ExtraQueryHost)

        require(!serverHost.isNullOrBlank()) { "Missing host extra" }
        require(serverPort in 1..65535) { "Invalid port extra: $serverPort" }
        require(!queryHost.isNullOrBlank()) { "Missing query host extra" }

        val requestId = Random.nextInt(0, 0x1_0000)
        val query = DebugDnsPacketCodec.buildQuery(queryHost, requestId)
        val startMs = SystemClock.elapsedRealtime()

        DatagramSocket().use { socket ->
            socket.soTimeout = timeoutMs
            socket.connect(InetSocketAddress(serverHost, serverPort))
            extras.putString(ExtraLocalAddress, socket.localAddress?.hostAddress)
            extras.putInt(ExtraLocalPort, socket.localPort)

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
            extras.putBoolean(ExtraOk, true)
            extras.putInt(ExtraDnsRcode, decoded.rcode)
            extras.putStringArrayList(ExtraDnsAnswers, ArrayList(decoded.answers))
            extras.putLong(ExtraDnsLatencyMs, SystemClock.elapsedRealtime() - startMs)
        }
    }
}
