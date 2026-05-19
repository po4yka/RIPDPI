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
import java.util.ArrayList
import java.util.Arrays
import java.util.Random

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
private const val ProbeThreadName = "test-network-probe"
private const val DefaultConnectTimeoutMs = 3_000
private const val DefaultReadTimeoutMs = 5_000
private const val MaxPort = 65_535
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

class TestNetworkProbeReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context?,
        intent: Intent?,
    ) {
        if (intent == null) {
            return
        }

        val action = intent.action
        if (!ActionProbeTcp.equals(action) && !ActionProbeDns.equals(action)) {
            return
        }

        val pendingResult = goAsync()
        val worker =
            Thread(
                ProbeRunnable(
                    receiver = this,
                    action = action,
                    intent = intent,
                    pendingResult = pendingResult,
                ),
                ProbeThreadName,
            )
        worker.isDaemon = true
        worker.start()
    }

    fun completeProbe(
        action: String?,
        intent: Intent?,
        pendingResult: PendingResult?,
    ) {
        if (intent == null || pendingResult == null) {
            return
        }

        val extras = Bundle()
        val resultCode =
            try {
                if (ActionProbeDns.equals(action)) {
                    runDnsProbe(intent, extras)
                } else {
                    runTcpProbe(intent, extras)
                }
                Activity.RESULT_OK
            } catch (error: Throwable) {
                extras.putBoolean(ExtraOk, false)
                extras.putString(ExtraErrorClass, error.javaClass.name)
                extras.putString(ExtraErrorMessage, error.message)
                Activity.RESULT_CANCELED
            }

        pendingResult.resultCode = resultCode
        pendingResult.setResultExtras(extras)
        pendingResult.finish()
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

        requireProbeHost(host, "Missing host extra")
        requireProbePort(port, "Invalid port extra: $port")

        val socket = Socket()
        try {
            socket.connect(InetSocketAddress(host, port), connectTimeoutMs)
            socket.soTimeout = readTimeoutMs
            extras.putBoolean(ExtraOk, true)
            putLocalSocket(extras, socket)

            if (payload != null) {
                val payloadBytes = utf8Bytes(payload)
                val output: OutputStream = socket.getOutputStream()
                output.write(payloadBytes)
                output.flush()
                socket.shutdownOutput()

                val input: InputStream = socket.getInputStream()
                extras.putString(ExtraResponse, readTcpProbeResponse(input, payloadBytes.size))
            }
        } finally {
            socket.close()
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

        requireProbeHost(serverHost, "Missing host extra")
        requireProbePort(serverPort, "Invalid port extra: $serverPort")
        requireProbeHost(queryHost, "Missing query host extra")
        val checkedQueryHost = queryHost ?: throw IllegalArgumentException("Missing query host extra")

        val requestId = Random().nextInt(0x1_0000)
        val query = buildDnsQuery(checkedQueryHost, requestId)
        val startedAt = SystemClock.elapsedRealtime()
        val socket = DatagramSocket()
        try {
            socket.soTimeout = timeoutMs
            socket.connect(InetSocketAddress(serverHost, serverPort))
            putLocalDatagramSocket(extras, socket)

            socket.send(DatagramPacket(query, query.size))

            val responseBytes = ByteArray(DnsPacketMaxBytes)
            val incomingPacket = DatagramPacket(responseBytes, responseBytes.size)
            socket.receive(incomingPacket)

            val answers = ArrayList<String>()
            val response = Arrays.copyOf(responseBytes, incomingPacket.length)
            val rcode = decodeDnsResponse(response, requestId, answers)
            extras.putBoolean(ExtraOk, true)
            extras.putInt(ExtraDnsRcode, rcode)
            extras.putStringArrayList(ExtraDnsAnswers, answers)
            extras.putLong(ExtraDnsLatencyMs, SystemClock.elapsedRealtime() - startedAt)
        } finally {
            socket.close()
        }
    }

    private fun putLocalSocket(
        extras: Bundle,
        socket: Socket,
    ) {
        val localAddress = socket.localAddress
        if (localAddress != null) {
            extras.putString(ExtraLocalAddress, localAddress.hostAddress)
        }
        extras.putInt(ExtraLocalPort, socket.localPort)
    }

    private fun putLocalDatagramSocket(
        extras: Bundle,
        socket: DatagramSocket,
    ) {
        val localAddress = socket.localAddress
        if (localAddress != null) {
            extras.putString(ExtraLocalAddress, localAddress.hostAddress)
        }
        extras.putInt(ExtraLocalPort, socket.localPort)
    }

    private fun readTcpProbeResponse(
        input: InputStream,
        expectedBytes: Int,
    ): String {
        if (expectedBytes < 0) {
            throw IllegalArgumentException("expectedBytes must be non-negative")
        }
        val response = ByteArrayOutputStream()
        val buffer = ByteArray(4 * 1024)
        while (expectedBytes == 0 || response.size() < expectedBytes) {
            val maxRead =
                if (expectedBytes == 0) {
                    buffer.size
                } else {
                    Math.min(buffer.size, expectedBytes - response.size())
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
        return String(response.toByteArray(), StandardCharsets.UTF_8)
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
        writeDnsHostname(output, hostname)
        output.write(0)
        writeU16(output, 1)
        writeU16(output, 1)
        return output.toByteArray()
    }

    private fun writeDnsHostname(
        output: ByteArrayOutputStream,
        hostname: String,
    ) {
        var start = 0
        while (start <= hostname.length) {
            val dot = indexOfDot(hostname, start)
            val end =
                if (dot == -1) {
                    hostname.length
                } else {
                    dot
                }
            if (end == start) {
                throw IllegalArgumentException("hostname contains an empty label: $hostname")
            }
            val labelBytes = asciiDnsLabelBytes(hostname, start, end)
            if (labelBytes.size > DnsMaxLabelBytes) {
                throw IllegalArgumentException("hostname label exceeds 63 octets")
            }
            output.write(labelBytes.size)
            output.write(labelBytes, 0, labelBytes.size)
            if (dot == -1) {
                return
            }
            start = dot + 1
        }
    }

    private fun asciiDnsLabelBytes(
        hostname: String,
        start: Int,
        end: Int,
    ): ByteArray {
        val bytes = ByteArray(end - start)
        var index = start
        while (index < end) {
            val code = hostname[index].code
            if (code > 0x7F) {
                throw IllegalArgumentException("hostname label must be ASCII")
            }
            bytes[index - start] = code.toByte()
            index += 1
        }
        return bytes
    }

    private fun indexOfDot(
        hostname: String,
        start: Int,
    ): Int {
        var index = start
        while (index < hostname.length) {
            if (hostname[index] == '.') {
                return index
            }
            index += 1
        }
        return -1
    }

    private fun decodeDnsResponse(
        packet: ByteArray,
        expectedRequestId: Int,
        answers: ArrayList<String>,
    ): Int {
        requireAvailable(packet, 0, DnsHeaderBytes, "DNS packet too short: ${packet.size}")
        val requestId = readU16(packet, 0)
        if (requestId != expectedRequestId) {
            throw IllegalArgumentException("Unexpected DNS request ID: expected=$expectedRequestId actual=$requestId")
        }
        val flags = readU16(packet, 2)
        val questionCount = readU16(packet, 4)
        val answerCount = readU16(packet, 6)
        var offset = DnsHeaderBytes
        var questionIndex = 0
        while (questionIndex < questionCount) {
            offset = skipName(packet, offset)
            requireAvailable(packet, offset, DnsQuestionTrailerBytes, "DNS question section truncated")
            offset += DnsQuestionTrailerBytes
            questionIndex += 1
        }

        var answerIndex = 0
        while (answerIndex < answerCount) {
            offset = skipName(packet, offset)
            requireAvailable(packet, offset, DnsAnswerHeaderBytes, "DNS answer section truncated")
            val type = readU16(packet, offset)
            val dataLength = readU16(packet, offset + DnsDataLengthOffset)
            offset += DnsAnswerHeaderBytes
            requireAvailable(packet, offset, dataLength, "DNS rdata section truncated")
            if (type == DnsTypeA && dataLength == Ipv4ByteCount) {
                addAddressAnswer(answers, packet, offset, dataLength)
            } else if (type == DnsTypeAaaa && dataLength == Ipv6ByteCount) {
                addAddressAnswer(answers, packet, offset, dataLength)
            } else if (type == DnsTypeCname || type == DnsTypePtr) {
                answers.add(readName(packet, offset))
            }
            offset += dataLength
            answerIndex += 1
        }

        return flags and DnsRcodeMask
    }

    private fun addAddressAnswer(
        answers: ArrayList<String>,
        packet: ByteArray,
        offset: Int,
        dataLength: Int,
    ) {
        val hostAddress = InetAddress.getByAddress(Arrays.copyOfRange(packet, offset, offset + dataLength)).hostAddress
        if (hostAddress != null) {
            answers.add(hostAddress)
        }
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
            if (length and DnsPointerMask != 0) {
                throw IllegalArgumentException("Unsupported DNS label prefix: $length")
            }
            current += 1
            requireAvailable(packet, current, length, "DNS label truncated")
            current += length
        }
    }

    private fun readName(
        packet: ByteArray,
        offset: Int,
    ): String {
        val labels = ArrayList<String>()
        var current = offset
        var guard = 0
        while (guard < packet.size) {
            requireAvailable(packet, current, 1, "DNS name truncated")
            val length = packet[current].toInt() and 0xFF
            if (length == 0) {
                return joinLabels(labels)
            }
            if (length and DnsPointerMask == DnsPointerValue) {
                requireAvailable(packet, current, 2, "DNS compression pointer truncated")
                current = ((length and DnsPointerOffsetMask) shl 8) or (packet[current + 1].toInt() and 0xFF)
            } else {
                if (length and DnsPointerMask != 0) {
                    throw IllegalArgumentException("Unsupported DNS label prefix: $length")
                }
                current += 1
                requireAvailable(packet, current, length, "DNS label truncated")
                labels.add(String(packet, current, length, StandardCharsets.UTF_8))
                current += length
            }
            guard += 1
        }
        throw IllegalArgumentException("DNS name compression loop")
    }

    private fun joinLabels(labels: ArrayList<String>): String {
        val builder = StringBuilder()
        var index = 0
        while (index < labels.size) {
            if (index > 0) {
                builder.append('.')
            }
            builder.append(labels[index])
            index += 1
        }
        return builder.toString()
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
        if (offset < 0 || length < 0 || offset + length > packet.size) {
            throw IllegalArgumentException(message)
        }
    }

    private fun requireProbeHost(
        host: String?,
        message: String,
    ) {
        if (host == null || isBlank(host)) {
            throw IllegalArgumentException(message)
        }
    }

    private fun requireProbePort(
        port: Int,
        message: String,
    ) {
        if (port < 1 || port > MaxPort) {
            throw IllegalArgumentException(message)
        }
    }

    private fun isBlank(value: String): Boolean {
        var index = 0
        while (index < value.length) {
            if (!Character.isWhitespace(value[index])) {
                return false
            }
            index += 1
        }
        return true
    }

    private fun utf8Bytes(value: String): ByteArray {
        val buffer = StandardCharsets.UTF_8.encode(value)
        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)
        return bytes
    }
}

private class ProbeRunnable(
    private val receiver: TestNetworkProbeReceiver?,
    private val action: String?,
    private val intent: Intent?,
    private val pendingResult: BroadcastReceiver.PendingResult?,
) : Runnable {
    override fun run() {
        val checkedReceiver = receiver ?: return
        checkedReceiver.completeProbe(action, intent, pendingResult)
    }
}
