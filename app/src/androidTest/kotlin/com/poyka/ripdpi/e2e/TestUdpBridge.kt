package com.poyka.ripdpi.e2e

import androidx.test.platform.app.InstrumentationRegistry
import java.io.Closeable
import java.io.EOFException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/** Test-only datagram transport over an owned USB TCP reverse port. */
class TestUdpBridge internal constructor(
    controlPort: Int,
    token: String,
) : Closeable {
    private val stopped = AtomicBoolean(false)
    private val failure = AtomicReference<Throwable?>()
    private val flows = linkedMapOf<InetSocketAddress, Int>()
    private val clients = mutableMapOf<Int, InetSocketAddress>()
    private val expiresAt = System.nanoTime() + MaxLifetimeNanos
    private val loopback = InetAddress.getByName("127.0.0.1")
    private val udp = DatagramSocket(InetSocketAddress(loopback, 0)).apply { soTimeout = 1_000 }
    private val tcp = Socket()
    private val workers: List<Thread>
    val port: Int get() = udp.localPort

    init {
        try {
            require(controlPort in 1..65_535 && token.matches(Regex("[0-9a-f]{64}")))
            tcp.connect(InetSocketAddress(loopback, controlPort), 5_000)
            tcp.tcpNoDelay = true
            tcp.soTimeout = 1_000
            tcp.getOutputStream().write(token.chunked(2).map { it.toInt(16).toByte() }.toByteArray())
            workers =
                listOf(
                    thread(name = "owned-usb-udp-send") { transfer { sendDatagrams() } },
                    thread(name = "owned-usb-udp-receive") { transfer { receiveDatagrams() } },
                )
        } catch (error: Throwable) {
            udp.close()
            tcp.close()
            throw error
        }
    }

    private fun transfer(action: () -> Unit) {
        try {
            action()
        } catch (error: Throwable) {
            if (!stopped.get()) {
                failure.compareAndSet(null, error)
                stopped.set(true)
                udp.close()
                tcp.close()
            }
        }
    }

    private fun sendDatagrams() {
        val bytes = ByteArray(65_507)
        val output = tcp.getOutputStream()
        while (!stopped.get()) {
            check(System.nanoTime() < expiresAt) { "Owned USB datagram lifetime exceeded" }
            val packet = DatagramPacket(bytes, bytes.size)
            try {
                udp.receive(packet)
            } catch (_: SocketTimeoutException) {
                continue
            }
            require(packet.length in 1..65_507 && packet.address.isLoopbackAddress)
            val source = InetSocketAddress(packet.address, packet.port)
            val flowId =
                synchronized(flows) {
                    flows[source] ?: run {
                        check(flows.size < MaxFlows) { "Owned USB datagram flow bound exceeded" }
                        (flows.size + 1).also { id ->
                            flows[source] = id
                            clients[id] = source
                        }
                    }
                }
            output.write(
                ByteBuffer
                    .allocate(HeaderSize)
                    .put(FrameVersion)
                    .putInt(flowId)
                    .putShort(packet.length.toShort())
                    .array(),
            )
            output.write(packet.data, packet.offset, packet.length)
            output.flush()
        }
    }

    private fun receiveDatagrams() {
        val input = tcp.getInputStream()

        fun readExact(size: Int): ByteArray {
            val bytes = ByteArray(size)
            var offset = 0
            val deadline = minOf(expiresAt, System.nanoTime() + 120_000_000_000L)
            while (offset < size && !stopped.get()) {
                check(System.nanoTime() < deadline) { "Owned USB datagram read deadline exceeded" }
                try {
                    val count = input.read(bytes, offset, size - offset)
                    if (count < 0) throw EOFException("Owned USB datagram stream ended")
                    offset += count
                } catch (error: SocketTimeoutException) {
                    if (System.nanoTime() >= deadline) throw error
                }
            }
            if (offset != size) throw EOFException("Owned USB datagram stream closed")
            return bytes
        }
        while (!stopped.get()) {
            val header = ByteBuffer.wrap(readExact(HeaderSize))
            require(header.get() == FrameVersion) { "Invalid owned USB datagram version" }
            val flowId = header.int
            val size = header.short.toInt() and 65_535
            require(size in 1..65_507)
            val peer = synchronized(flows) { requireNotNull(clients[flowId]) { "Unknown owned USB datagram flow" } }
            val bytes = readExact(size)
            udp.send(DatagramPacket(bytes, bytes.size, peer))
        }
    }

    override fun close() {
        stopped.set(true)
        udp.close()
        tcp.close()
        workers.forEach { it.join(3_000) }
        check(workers.none { it.isAlive }) { "Owned USB datagram worker did not stop" }
        failure.get()?.let { throw IllegalStateException("Owned USB datagram bridge failed", it) }
    }

    companion object {
        private const val FrameVersion: Byte = 1
        private const val HeaderSize = 7
        private const val MaxFlows = 64
        private const val MaxLifetimeNanos = 600_000_000_000L

        fun fromArguments(): TestUdpBridge? {
            val args = InstrumentationRegistry.getArguments()
            val port = args.getString("ripdpi.usbUdpBridgePort") ?: return null
            check(args.getString("ripdpi.usbDataScope") == "true")
            return TestUdpBridge(port.toInt(), requireNotNull(args.getString("ripdpi.usbUdpBridgeToken")))
        }
    }
}
