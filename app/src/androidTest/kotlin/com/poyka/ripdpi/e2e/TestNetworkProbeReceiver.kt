package com.poyka.ripdpi.e2e

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.nio.charset.StandardCharsets
import kotlin.concurrent.thread
import kotlin.random.Random

class TestNetworkProbeReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        val action = intent.action
        if (action != ActionProbeTcp && action != ActionProbeDns) {
            return
        }

        val pendingResult = goAsync()
        thread(name = "test-network-probe", isDaemon = true) {
            val extras = Bundle()
            val resultCode =
                runCatching {
                    if (action == ActionProbeDns) {
                        runDnsProbe(intent, extras)
                    } else {
                        runTcpProbe(intent, extras)
                    }
                    Activity.RESULT_OK
                }.getOrElse { error ->
                    extras.putBoolean(ExtraOk, false)
                    extras.putString(ExtraErrorClass, error.javaClass.name)
                    extras.putString(ExtraErrorMessage, error.message)
                    Activity.RESULT_CANCELED
                }

            pendingResult.resultCode = resultCode
            pendingResult.setResultExtras(extras)
            pendingResult.finish()
        }
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
        require(port in 1..65_535) { "Invalid port extra: $port" }

        Socket().use { socket ->
            socket.connect(InetSocketAddress(host, port), connectTimeoutMs)
            socket.soTimeout = readTimeoutMs
            extras.putBoolean(ExtraOk, true)
            extras.putString(ExtraLocalAddress, socket.localAddress?.hostAddress)
            extras.putInt(ExtraLocalPort, socket.localPort)

            if (payload != null) {
                val payloadBytes = payload.toByteArray(StandardCharsets.UTF_8)
                val output: OutputStream = socket.getOutputStream()
                output.write(payloadBytes)
                output.flush()
                socket.shutdownOutput()

                val input: InputStream = socket.getInputStream()
                extras.putString(ExtraResponse, readTcpProbeResponse(input, payloadBytes.size))
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
        require(serverPort in 1..65_535) { "Invalid port extra: $serverPort" }
        require(!queryHost.isNullOrBlank()) { "Missing query host extra" }

        val requestId = Random.nextInt(0, 0x1_0000)
        val query = buildDnsQuery(queryHost, requestId)
        val startedAt = SystemClock.elapsedRealtime()

        DatagramSocket().use { socket ->
            socket.soTimeout = timeoutMs
            socket.connect(InetSocketAddress(serverHost, serverPort))
            extras.putString(ExtraLocalAddress, socket.localAddress?.hostAddress)
            extras.putInt(ExtraLocalPort, socket.localPort)

            socket.send(DatagramPacket(query, query.size))

            val responseBytes = ByteArray(DnsPacketMaxBytes)
            val incomingPacket = DatagramPacket(responseBytes, responseBytes.size)
            socket.receive(incomingPacket)

            val decoded = decodeDnsResponse(responseBytes.copyOf(incomingPacket.length), requestId)
            extras.putBoolean(ExtraOk, true)
            extras.putInt(ExtraDnsRcode, decoded.rcode)
            extras.putStringArrayList(ExtraDnsAnswers, ArrayList(decoded.answers))
            extras.putLong(ExtraDnsLatencyMs, SystemClock.elapsedRealtime() - startedAt)
        }
    }

    private fun readTcpProbeResponse(
        input: InputStream,
        expectedBytes: Int,
    ): String {
        require(expectedBytes >= 0) { "expectedBytes must be non-negative" }
        val response = ByteArrayOutputStream()
        val buffer = ByteArray(4 * 1024)
        while (expectedBytes == 0 || response.size() < expectedBytes) {
            val maxRead =
                if (expectedBytes == 0) {
                    buffer.size
                } else {
                    minOf(buffer.size, expectedBytes - response.size())
                }
            val read =
                try {
                    input.read(buffer, 0, maxRead)
                } catch (timeout: SocketTimeoutException) {
                    if (response.size() > 0) {
                        break
                    }
                    throw timeout
                }
            if (read <= 0) {
                break
            }
            response.write(buffer, 0, read)
        }
        return response.toString(StandardCharsets.UTF_8.name())
    }

    private fun buildDnsQuery(
        hostname: String,
        requestId: Int,
    ): ByteArray {
        val output = ByteArrayOutputStream()
        writeU16(output, requestId)
        writeU16(output, 0x0100)
        writeU16(output, 1)
        writeU16(output, 0)
        writeU16(output, 0)
        writeU16(output, 0)
        hostname.split('.').forEach { label ->
            require(label.isNotEmpty()) { "hostname contains an empty label: $hostname" }
            val labelBytes = label.toByteArray(StandardCharsets.UTF_8)
            require(labelBytes.size <= DnsMaxLabelBytes) { "hostname label exceeds 63 octets: $label" }
            output.write(labelBytes.size)
            output.write(labelBytes, 0, labelBytes.size)
        }
        output.write(0)
        writeU16(output, 1)
        writeU16(output, 1)
        return output.toByteArray()
    }

    private fun decodeDnsResponse(
        packet: ByteArray,
        expectedRequestId: Int,
    ): DecodedDnsResponse {
        require(packet.size >= DnsHeaderBytes) { "DNS packet too short: ${packet.size}" }
        val requestId = readU16(packet, 0)
        require(requestId == expectedRequestId) {
            "Unexpected DNS request ID: expected=$expectedRequestId actual=$requestId"
        }
        val flags = readU16(packet, 2)
        val questionCount = readU16(packet, 4)
        val answerCount = readU16(packet, 6)
        var offset = DnsHeaderBytes
        repeat(questionCount) {
            offset = skipName(packet, offset)
            requireAvailable(packet, offset, DnsQuestionTrailerBytes, "DNS question section truncated")
            offset += DnsQuestionTrailerBytes
        }

        val answers = mutableListOf<String>()
        repeat(answerCount) {
            offset = skipName(packet, offset)
            requireAvailable(packet, offset, DnsAnswerHeaderBytes, "DNS answer section truncated")
            val type = readU16(packet, offset)
            val dataLength = readU16(packet, offset + DnsDataLengthOffset)
            offset += DnsAnswerHeaderBytes
            requireAvailable(packet, offset, dataLength, "DNS rdata section truncated")
            when {
                type == DnsTypeA && dataLength == Ipv4ByteCount -> {
                    answers += InetAddress.getByAddress(packet.copyOfRange(offset, offset + dataLength)).hostAddress
                }

                type == DnsTypeAaaa && dataLength == Ipv6ByteCount -> {
                    answers += InetAddress.getByAddress(packet.copyOfRange(offset, offset + dataLength)).hostAddress
                }

                type == DnsTypeCname || type == DnsTypePtr -> {
                    answers += readName(packet, offset).name
                }
            }
            offset += dataLength
        }

        return DecodedDnsResponse(
            rcode = flags and DnsRcodeMask,
            answers = answers,
        )
    }

    private fun skipName(
        packet: ByteArray,
        offset: Int,
    ): Int {
        var current = offset
        while (true) {
            requireAvailable(packet, current, 1, "DNS name truncated")
            val length = packet[current].toInt() and 0xFF
            if (length == 0) {
                return current + 1
            }
            if (length and DnsPointerMask == DnsPointerValue) {
                requireAvailable(packet, current, 2, "DNS compression pointer truncated")
                return current + 2
            }
            require(length and DnsPointerMask == 0) { "Unsupported DNS label prefix: $length" }
            current += 1
            requireAvailable(packet, current, length, "DNS label truncated")
            current += length
        }
    }

    private fun readName(
        packet: ByteArray,
        offset: Int,
    ): DnsNameResult {
        val labels = mutableListOf<String>()
        var current = offset
        var consumedOffset = -1
        repeat(packet.size) {
            requireAvailable(packet, current, 1, "DNS name truncated")
            val length = packet[current].toInt() and 0xFF
            when {
                length == 0 -> {
                    return DnsNameResult(
                        name = labels.joinToString("."),
                        nextOffset = if (consumedOffset == -1) current + 1 else consumedOffset,
                    )
                }

                length and DnsPointerMask == DnsPointerValue -> {
                    requireAvailable(packet, current, 2, "DNS compression pointer truncated")
                    val pointer = ((length and DnsPointerOffsetMask) shl 8) or (packet[current + 1].toInt() and 0xFF)
                    if (consumedOffset == -1) {
                        consumedOffset = current + 2
                    }
                    current = pointer
                }

                else -> {
                    require(length and DnsPointerMask == 0) { "Unsupported DNS label prefix: $length" }
                    current += 1
                    requireAvailable(packet, current, length, "DNS label truncated")
                    labels += String(packet, current, length, StandardCharsets.UTF_8)
                    current += length
                }
            }
        }
        throw IllegalArgumentException("DNS name compression loop")
    }

    private fun readU16(
        packet: ByteArray,
        offset: Int,
    ): Int {
        requireAvailable(packet, offset, 2, "u16 field truncated")
        return ((packet[offset].toInt() and 0xFF) shl 8) or (packet[offset + 1].toInt() and 0xFF)
    }

    private fun writeU16(
        output: ByteArrayOutputStream,
        value: Int,
    ) {
        output.write((value ushr 8) and 0xFF)
        output.write(value and 0xFF)
    }

    private fun requireAvailable(
        packet: ByteArray,
        offset: Int,
        length: Int,
        message: String,
    ) {
        require(offset >= 0 && length >= 0 && offset + length <= packet.size) { message }
    }

    private data class DecodedDnsResponse(
        val rcode: Int,
        val answers: List<String>,
    )

    private data class DnsNameResult(
        val name: String,
        val nextOffset: Int,
    )

    private companion object {
        private const val ActionProbeTcp = "com.poyka.ripdpi.debug.PROBE_TCP"
        private const val ActionProbeDns = "com.poyka.ripdpi.debug.PROBE_DNS"
        private const val ExtraHost = "host"
        private const val ExtraPort = "port"
        private const val ExtraConnectTimeoutMs = "connect_timeout_ms"
        private const val ExtraReadTimeoutMs = "read_timeout_ms"
        private const val ExtraPayload = "payload"
        private const val ExtraQueryHost = "query_host"
        private const val ExtraOk = "ok"
        private const val ExtraLocalAddress = "local_address"
        private const val ExtraLocalPort = "local_port"
        private const val ExtraResponse = "response"
        private const val ExtraDnsRcode = "rcode"
        private const val ExtraDnsAnswers = "answers"
        private const val ExtraDnsLatencyMs = "latency_ms"
        private const val ExtraErrorClass = "error_class"
        private const val ExtraErrorMessage = "error_message"
        private const val DefaultConnectTimeoutMs = 3_000
        private const val DefaultReadTimeoutMs = 5_000
        private const val DnsHeaderBytes = 12
        private const val DnsQuestionTrailerBytes = 4
        private const val DnsAnswerHeaderBytes = 10
        private const val DnsDataLengthOffset = 8
        private const val DnsPacketMaxBytes = 1_500
        private const val DnsMaxLabelBytes = 63
        private const val DnsPointerMask = 0xC0
        private const val DnsPointerValue = 0xC0
        private const val DnsPointerOffsetMask = 0x3F
        private const val DnsTypeA = 1
        private const val DnsTypeCname = 5
        private const val DnsTypePtr = 12
        private const val DnsTypeAaaa = 28
        private const val DnsRcodeMask = 0x000F
        private const val Ipv4ByteCount = 4
        private const val Ipv6ByteCount = 16
    }
}
