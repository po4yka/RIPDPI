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
import java.io.Closeable
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
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.FutureTask
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

internal class DirectDns(
    servers: List<String>,
    private val addressFamily: DetectionAddressFamily = DetectionAddressFamily.ANY,
    private val port: Int = 53,
    private val timeoutMillis: Int = 3_000,
    private val binding: DetectionNetworkBinding = DetectionNetworkBinding.None,
) : Dns {
    private val serverAddresses = servers.mapNotNull { NumericIp.parseAddress(it.trim())?.toInetAddress() }

    override fun lookup(hostname: String): List<InetAddress> = lookup(hostname, QueryState())

    override fun newCall(request: Dns.Request): Dns.Call = QueryCall(request)

    private fun lookup(
        hostname: String,
        state: QueryState,
    ): List<InetAddress> {
        state.checkActive()
        NumericIp.parseAddress(hostname)?.let { return listOf(it.toInetAddress()) }
        if (serverAddresses.isEmpty()) throw UnknownHostException("No valid direct DNS servers configured")
        val resolved = mutableListOf<InetAddress>()
        var lastFailure: IOException? = null
        for (recordType in recordTypes()) {
            state.checkActive()
            try {
                resolved += queryServers(hostname, recordType, state)
            } catch (error: IOException) {
                state.checkActive()
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
        state: QueryState,
    ): List<InetAddress> {
        var lastFailure: IOException? = null
        for (server in serverAddresses) {
            state.checkActive()
            try {
                val addresses = query(server, hostname, recordType, state)
                if (addresses.isNotEmpty()) return addresses
            } catch (error: IOException) {
                state.checkActive()
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
        state: QueryState,
    ): List<InetAddress> {
        val query = DnsMessages.query(hostname, recordType.wireCode)
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis.toLong())
        val udp = udpResponse(server, query, deadline, state)
        val response = if (udp.header.getFlag(Flags.TC.toInt())) tcpResponse(server, query, deadline, state) else udp
        if (response.header.getFlag(Flags.TC.toInt())) throw IOException("Truncated TCP DNS response")
        if (response.rcode != Rcode.NOERROR) throw UnknownHostException("DNS response code ${response.rcode}")
        val owner = DnsMessages.answerChain(response).last()
        return response
            .getSection(Section.ANSWER)
            .filter { it.dClass == DClass.IN && it.type == recordType.wireCode && it.name == owner }
            .mapNotNull { record ->
                when (record) {
                    is ARecord -> record.address
                    is AAAARecord -> record.ipv6Address()
                    else -> null
                }
            }.distinctBy { it.hostAddress }
    }

    private fun udpResponse(
        server: InetAddress,
        query: ByteArray,
        deadline: Long,
        state: QueryState,
    ): Message =
        state.withSocket(DatagramSocket()) { socket ->
            ResolverSocketBinder.bind(socket, binding)
            socket.connect(server, port)
            socket.send(DatagramPacket(query, query.size))
            val packet = DatagramPacket(ByteArray(DnsMessages.MAX_MESSAGE_BYTES), DnsMessages.MAX_MESSAGE_BYTES)
            var result: Message? = null
            while (result == null) {
                state.checkActive()
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
        state: QueryState,
    ): Message =
        state.withSocket(Socket()) { socket ->
            socket.bind(InetSocketAddress(0))
            ResolverSocketBinder.bind(socket, binding)
            socket.connect(InetSocketAddress(server, port), remainingMillis(deadline))
            DataOutputStream(socket.getOutputStream()).apply {
                writeShort(query.size)
                write(query)
                flush()
            }
            val lengthBytes = socket.readExactly(TcpLengthBytes, deadline, state)
            val length =
                ((lengthBytes[0].toInt() and ByteMask) shl Byte.SIZE_BITS) or (lengthBytes[1].toInt() and ByteMask)
            DnsMessages.response(socket.readExactly(length, deadline, state), query)
        }

    private fun Socket.readExactly(
        length: Int,
        deadline: Long,
        state: QueryState,
    ): ByteArray {
        val result = ByteArray(length)
        val input = getInputStream()
        var offset = 0
        while (offset < length) {
            state.checkActive()
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

    private inner class QueryCall(
        override val request: Dns.Request,
    ) : Dns.Call {
        private val lock = Any()
        private val state = QueryState()
        private var enqueued = false
        private var completed = false
        private var cancelled = false
        private var callback: Dns.Callback? = null
        private var task: FutureTask<Unit>? = null

        override fun enqueue(callback: Dns.Callback) {
            val failure =
                synchronized(lock) {
                    check(!enqueued) { "Already enqueued" }
                    enqueued = true
                    if (cancelled) {
                        completed = true
                        IOException("DNS lookup cancelled")
                    } else {
                        this.callback = callback
                        val submitted = FutureTask { resolve() }
                        task = submitted
                        try {
                            lookupExecutor.execute(submitted)
                            null
                        } catch (error: RejectedExecutionException) {
                            completed = true
                            this.callback = null
                            IOException("Direct DNS lookup capacity exceeded", error)
                        }
                    }
                }
            if (failure != null) callback.onFailure(this, failure)
        }

        override fun cancel() {
            val pending =
                synchronized(lock) {
                    if (completed || cancelled) return
                    cancelled = true
                    completed = enqueued
                    val pendingCallback = callback
                    callback = null
                    task to pendingCallback
                }
            state.cancel()
            pending.first?.let {
                it.cancel(true)
                lookupExecutor.remove(it)
            }
            pending.second?.onFailure(this, IOException("DNS lookup cancelled"))
        }

        override fun isCanceled(): Boolean = synchronized(lock) { cancelled }

        @Suppress("TooGenericExceptionCaught")
        private fun resolve() {
            var failure: IOException? = null
            val records =
                try {
                    lookup(request.hostname, state).map { Dns.Record.IpAddress(request.hostname, it) }
                } catch (error: Exception) {
                    failure = error as? IOException ?: IOException("Direct DNS lookup failed", error)
                    emptyList()
                }
            val listener =
                synchronized(lock) {
                    if (completed) return
                    completed = true
                    callback.also { callback = null }
                } ?: return
            if (failure == null) listener.onRecords(this, true, records) else listener.onFailure(this, failure)
        }
    }

    private class QueryState {
        private val lock = Any()
        private var cancelled = false
        private var activeSocket: Closeable? = null

        fun checkActive() =
            synchronized(lock) {
                if (cancelled) throw IOException("DNS lookup cancelled")
            }

        fun <T : Closeable, R> withSocket(
            socket: T,
            block: (T) -> R,
        ): R =
            socket.use {
                synchronized(lock) {
                    checkActive()
                    activeSocket = socket
                }
                try {
                    block(socket)
                } finally {
                    synchronized(lock) {
                        if (activeSocket === socket) activeSocket = null
                    }
                }
            }

        fun cancel() {
            val socket =
                synchronized(lock) {
                    cancelled = true
                    activeSocket.also { activeSocket = null }
                }
            try {
                socket?.close()
            } catch (_: IOException) {
                // Cancellation remains terminal when the socket was already closed.
            }
        }
    }

    private companion object {
        const val TcpLengthBytes = 2
        const val ByteMask = 255
        const val MaxConcurrentLookups = 4
        const val MaxQueuedLookups = 32
        const val LookupKeepAliveSeconds = 30L
        private val threadIndex = AtomicInteger()
        private val lookupExecutor =
            ThreadPoolExecutor(
                MaxConcurrentLookups,
                MaxConcurrentLookups,
                LookupKeepAliveSeconds,
                TimeUnit.SECONDS,
                ArrayBlockingQueue(MaxQueuedLookups),
                { runnable ->
                    Thread(runnable, "ripdpi-direct-dns-${threadIndex.incrementAndGet()}").apply { isDaemon = true }
                },
            ).apply { allowCoreThreadTimeOut(true) }

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
