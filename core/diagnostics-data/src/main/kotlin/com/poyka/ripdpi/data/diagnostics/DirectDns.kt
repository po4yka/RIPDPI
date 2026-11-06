package com.poyka.ripdpi.data.diagnostics

import com.poyka.ripdpi.data.NumericIp
import okhttp3.Dns
import org.xbill.DNS.AAAARecord
import org.xbill.DNS.ARecord
import org.xbill.DNS.DClass
import org.xbill.DNS.Flags
import org.xbill.DNS.Message
import org.xbill.DNS.Rcode
import org.xbill.DNS.Section
import java.io.DataOutputStream
import java.io.EOFException
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

internal class DirectDns(
    servers: List<String>,
    private val addressFamily: DetectionAddressFamily = DetectionAddressFamily.ANY,
    private val port: Int = 53,
    private val timeoutMillis: Int = 3_000,
    private val binding: DetectionNetworkBinding = DetectionNetworkBinding.None,
) : Dns {
    private val serverAddresses = servers.mapNotNull { NumericIp.parseAddress(it.trim())?.toInetAddress() }

    override fun lookup(hostname: String): List<InetAddress> {
        NumericIp.parseAddress(hostname)?.let { return listOf(it.toInetAddress()) }
        if (serverAddresses.isEmpty()) throw UnknownHostException("No valid direct DNS servers configured")
        val resolved = mutableListOf<InetAddress>()
        var lastFailure: IOException? = null
        for (recordType in recordTypes()) {
            try {
                resolved += queryServers(hostname, recordType)
            } catch (error: IOException) {
                lastFailure = error
            }
        }
        return resolved.distinctBy { it.hostAddress }.ifEmpty {
            throw UnknownHostException(lastFailure?.message ?: "No DNS addresses").apply { initCause(lastFailure) }
        }
    }

    private fun queryServers(
        hostname: String,
        recordType: DnsRecordType,
    ): List<InetAddress> {
        var lastFailure: IOException? = null
        for (server in serverAddresses) {
            try {
                val addresses = query(server, hostname, recordType)
                if (addresses.isNotEmpty()) return addresses
            } catch (error: IOException) {
                lastFailure = error
            }
        }
        if (lastFailure != null) throw lastFailure
        return emptyList()
    }

    private fun query(
        server: InetAddress,
        hostname: String,
        recordType: DnsRecordType,
    ): List<InetAddress> {
        val query = DnsMessages.query(hostname, recordType.wireCode)
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis.toLong())
        val udp = udpResponse(server, query, deadline)
        val response = if (udp.header.getFlag(Flags.TC.toInt())) tcpResponse(server, query, deadline) else udp
        if (response.header.getFlag(Flags.TC.toInt())) throw IOException("Truncated TCP DNS response")
        if (response.rcode != Rcode.NOERROR) throw UnknownHostException("DNS response code ${response.rcode}")
        val owner = DnsMessages.answerChain(response).last()
        return response
            .getSection(Section.ANSWER)
            .filter { it.dClass == DClass.IN && it.type == recordType.wireCode && it.name == owner }
            .mapNotNull { record ->
                when (record) {
                    is ARecord -> record.address
                    is AAAARecord -> record.address
                    else -> null
                }
            }.distinctBy { it.hostAddress }
    }

    private fun udpResponse(
        server: InetAddress,
        query: ByteArray,
        deadline: Long,
    ): Message =
        DatagramSocket().use { socket ->
            ResolverSocketBinder.bind(socket, binding)
            socket.connect(server, port)
            socket.send(DatagramPacket(query, query.size))
            val packet = DatagramPacket(ByteArray(DnsMessages.MAX_MESSAGE_BYTES), DnsMessages.MAX_MESSAGE_BYTES)
            var result: Message? = null
            while (result == null) {
                socket.soTimeout = remainingMillis(deadline)
                packet.length = packet.data.size
                socket.receive(packet)
                result =
                    try {
                        DnsMessages.response(packet.data.copyOf(packet.length), query)
                    } catch (_: IOException) {
                        // Ignore unrelated or invalid datagrams within the original request deadline.
                        null
                    }
            }
            result
        }

    private fun tcpResponse(
        server: InetAddress,
        query: ByteArray,
        deadline: Long,
    ): Message =
        Socket().use { socket ->
            socket.bind(InetSocketAddress(0))
            ResolverSocketBinder.bind(socket, binding)
            socket.connect(InetSocketAddress(server, port), remainingMillis(deadline))
            DataOutputStream(socket.getOutputStream()).apply {
                writeShort(query.size)
                write(query)
                flush()
            }
            val lengthBytes = socket.readExactly(TcpLengthBytes, deadline)
            val length =
                ((lengthBytes[0].toInt() and ByteMask) shl Byte.SIZE_BITS) or (lengthBytes[1].toInt() and ByteMask)
            DnsMessages.response(socket.readExactly(length, deadline), query)
        }

    private fun Socket.readExactly(
        length: Int,
        deadline: Long,
    ): ByteArray {
        val result = ByteArray(length)
        val input = getInputStream()
        var offset = 0
        while (offset < length) {
            soTimeout = remainingMillis(deadline)
            val count = input.read(result, offset, length - offset)
            if (count < 0) throw EOFException("Incomplete DNS TCP frame")
            offset += count
        }
        return result
    }

    private fun recordTypes(): List<DnsRecordType> =
        when (addressFamily) {
            DetectionAddressFamily.ANY -> listOf(DnsRecordType.A, DnsRecordType.AAAA)
            DetectionAddressFamily.IPV4 -> listOf(DnsRecordType.A)
            DetectionAddressFamily.IPV6 -> listOf(DnsRecordType.AAAA)
        }

    private companion object {
        const val TcpLengthBytes = 2
        const val ByteMask = 255

        fun remainingMillis(deadline: Long): Int {
            val remaining = deadline - System.nanoTime()
            if (remaining <= 0) throw SocketTimeoutException("DNS query deadline exceeded")
            return TimeUnit.NANOSECONDS
                .toMillis(remaining)
                .coerceIn(1, Int.MAX_VALUE.toLong())
                .toInt()
        }
    }
}
