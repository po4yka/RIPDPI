package com.poyka.ripdpi.debug

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import java.io.ByteArrayOutputStream
import java.io.File
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
            val (probeResult, activityResultCode) =
                runCatching {
                    when (intent.action) {
                        ActionProbeDns -> runDnsProbe(intent, extras)
                        else -> runTcpProbe(intent, extras)
                    } to Activity.RESULT_OK
                }.getOrElse { error ->
                    failureProbeResult(intent, extras, error) to Activity.RESULT_CANCELED
                }

            persistProbeResult(context, probeResult)

            pendingResult.resultCode = activityResultCode
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
        const val ExtraRequestId = "request_id"
        const val ExtraScenarioId = "scenario_id"
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
    ): PacketSmokeRunnerProbeResult {
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
            var responseText: String? = null

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
                responseText = response.toString(StandardCharsets.UTF_8.name())
                extras.putString(ExtraResponse, responseText)
            }

            return PacketSmokeRunnerProbeResult(
                requestId = intent.getStringExtra(ExtraRequestId).orEmpty(),
                scenarioId = intent.getStringExtra(ExtraScenarioId).orEmpty(),
                probeType = "tcp",
                host = host,
                port = port,
                ok = true,
                localAddress = socket.localAddress?.hostAddress,
                localPort = socket.localPort,
                response = responseText,
            )
        }
    }

    private fun runDnsProbe(
        intent: Intent,
        extras: Bundle,
    ): PacketSmokeRunnerProbeResult {
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
            val latencyMs = SystemClock.elapsedRealtime() - startMs
            extras.putLong(ExtraDnsLatencyMs, latencyMs)
            return PacketSmokeRunnerProbeResult(
                requestId = intent.getStringExtra(ExtraRequestId).orEmpty(),
                scenarioId = intent.getStringExtra(ExtraScenarioId).orEmpty(),
                probeType = "dns",
                host = serverHost,
                port = serverPort,
                ok = true,
                queryHost = queryHost,
                rcode = decoded.rcode,
                answers = decoded.answers,
                latencyMs = latencyMs,
                localAddress = socket.localAddress?.hostAddress,
                localPort = socket.localPort,
            )
        }
    }

    private fun failureProbeResult(
        intent: Intent,
        extras: Bundle,
        error: Throwable,
    ): PacketSmokeRunnerProbeResult {
        val failure = error.toDebugProbeFailure()
        extras.putBoolean(ExtraOk, false)
        extras.putString(ExtraErrorClass, failure.errorClass)
        extras.putString(ExtraErrorMessage, failure.errorMessage)
        return PacketSmokeRunnerProbeResult(
            requestId = intent.getStringExtra(ExtraRequestId).orEmpty(),
            scenarioId = intent.getStringExtra(ExtraScenarioId).orEmpty(),
            probeType = if (intent.action == ActionProbeDns) "dns" else "tcp",
            host = intent.getStringExtra(ExtraHost).orEmpty(),
            port = intent.getIntExtra(ExtraPort, -1),
            ok = false,
            queryHost = intent.getStringExtra(ExtraQueryHost),
            errorClass = failure.errorClass,
            errorMessage = failure.errorMessage,
        )
    }

    private fun persistProbeResult(
        context: Context,
        probeResult: PacketSmokeRunnerProbeResult,
    ) {
        if (probeResult.requestId.isBlank() || probeResult.scenarioId.isBlank()) {
            return
        }

        val scenarioDir = File(context.cacheDir, "packet-smoke/${probeResult.scenarioId}").apply { mkdirs() }
        File(scenarioDir, PacketSmokeProbeResultFileName).writeText(probeResult.toJson(), Charsets.UTF_8)
    }
}
