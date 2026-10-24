package com.poyka.ripdpi.testing.awginterop

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.util.Base64
import androidx.test.platform.app.InstrumentationRegistry
import com.poyka.ripdpi.core.RipDpiAmneziaWgNativeBindings
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/** Native-core evidence only: every tunneled exchange uses the real production AWG JNI library. */
class AwgNativeInteropTest {
    @Test
    fun nativeCoreExchangesDualStackPayloads() {
        val args = InstrumentationRegistry.getArguments()
        val mtu = requireNotNull(args.getString("awgMtu")).toInt()
        val cycle = requireNotNull(args.getString("awgCycle")).toInt()
        require(mtu in 1280..1420 && cycle in 0..3)
        assertEquals("arm64-v8a", Build.SUPPORTED_ABIS.first())
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertNoActiveVpn(context)
        awaitIpv4Underlay(context)
        assertNull("The runner must obtain real Android VPN authorization", VpnService.prepare(context))
        assertNoActiveVpn(context)
        val config =
            JSONObject()
                .put("enabled", true)
                .put("profileId", "native-interop")
                .put("privateKey", key(7))
                .put("peerPublicKey", args.getString("awgPublicKey"))
                .put("presharedKey", key(5))
                .put("endpointHost", "10.0.2.2")
                .put("endpointIpv4", "10.0.2.2")
                .put("endpointPort", requireNotNull(args.getString("awgPeerPort")).toInt())
                .put("interfaceAddressV4", "10.77.0.2/32")
                .put("interfaceAddressV6", "fd77::2/128")
                .put("mtu", mtu)
                .put("localSocksHost", "127.0.0.1")
                .put("localSocksPort", 0)
                .put(
                    "amnezia",
                    JSONObject()
                        .put("wireProfile", "awg-3.1-ranges-v1")
                        .put("jc", 4)
                        .put("jmin", 64)
                        .put("jmax", 96)
                        .put("s1", 8)
                        .put("s2", 12)
                        .put("s3", 0)
                        .put("s4", 0)
                        .put("h1", "100-199")
                        .put("h2", "200-299")
                        .put("h3", "300-399")
                        .put("h4", "400-499")
                        .put("i1", "<b 0xf0f1><r 16><rd 8><rc 8><t>")
                        .put("i2", "<b 0xf2>")
                        .put("i3", "<b 0xf3>")
                        .put("i4", "<b 0xf4>")
                        .put("i5", "<b 0xf5>"),
                )
        val bindings = RipDpiAmneziaWgNativeBindings()
        val protection = RecordingVpnService()
        val handle = bindings.jniCreate(config.toString())
        assertTrue("real JNI create must return a handle", handle != 0L)
        var protectionToken = 0L
        val exit = AtomicInteger(-1)
        var worker: Thread? = null
        val measurements = JSONArray()
        try {
            protectionToken = RipDpiAmneziaWgNativeBindings.jniRegisterVpnProtect(protection)
            assertTrue("real JNI protection registration must return a token", protectionToken != 0L)
            worker = thread(name = "awg-interop-native") { exit.set(bindings.jniStart(handle)) }
            val listener = awaitListener(bindings, handle, exit)
            listOf("10.77.0.1" to 41001, "fd77::1" to 41004).forEach { (host, port) ->
                val payload = ByteArray(4096) { (it % 251).toByte() }
                socks(listener, 1, host, port).first.use { socket ->
                    DataOutputStream(socket.getOutputStream()).apply {
                        writeInt(payload.size)
                        write(payload)
                        flush()
                    }
                    val echoed = ByteArray(payload.size)
                    DataInputStream(socket.getInputStream()).readFully(echoed)
                    assertArrayEquals(payload, echoed)
                    measurements.put(measure("tcp", host, payload, echoed))
                }
            }
            val (control, relayPort) = socks(listener, 3, "0.0.0.0", 0)
            control.use {
                DatagramSocket().use { udp ->
                    udp.soTimeout = TimeoutMs
                    listOf("10.77.0.1" to 41002, "fd77::1" to 41005).forEach { (host, port) ->
                        val frames =
                            (0..1).map { offset ->
                                udpFrame(host, port + offset, ByteArray(900) { (it % 251).toByte() })
                            }
                        frames.forEach {
                            udp.send(
                                DatagramPacket(it, it.size, InetAddress.getByName("127.0.0.1"), relayPort),
                            )
                        }
                        val responses =
                            (0..1).map {
                                val packet = DatagramPacket(ByteArray(2048), 2048)
                                udp.receive(packet)
                                assertEquals(relayPort, packet.port)
                                packet.data.copyOf(packet.length)
                            }
                        frames.forEach { sent ->
                            val received = responses.single { it.contentEquals(sent) }
                            measurements.put(measure("udp", host, sent, received))
                        }
                    }
                    listOf("10.77.0.1" to 1, "fd77::1" to 28).forEach { (host, type) ->
                        val query =
                            byteArrayOf(0x43, 0x21, 1, 0, 0, 1, 0, 0, 0, 0, 0, 0, 10) +
                                "acceptance".toByteArray() + byteArrayOf(4) + "test".toByteArray() +
                                byteArrayOf(0, 0, type.toByte(), 0, 1)
                        val sent = udpFrame(host, 53, query)
                        udp.send(DatagramPacket(sent, sent.size, InetAddress.getByName("127.0.0.1"), relayPort))
                        val packet = DatagramPacket(ByteArray(2048), 2048)
                        udp.receive(packet)
                        assertEquals(relayPort, packet.port)
                        val received = packet.data.copyOf(packet.length)
                        val prefixSize = if (':' in host) 22 else 10
                        assertArrayEquals(sent.copyOf(prefixSize), received.copyOf(prefixSize))
                        val response = received.copyOfRange(prefixSize, received.size)
                        assertArrayEquals(query.copyOf(2), response.copyOf(2))
                        assertTrue("DNS QR flag", response[2].toInt() and 128 != 0)
                        assertEquals("one DNS answer", 1, response[7].toInt())
                        val expected = InetAddress.getByName(if (type == 1) "192.0.2.53" else "fd77::53").address
                        assertArrayEquals(expected, response.takeLast(expected.size).toByteArray())
                        measurements.put(
                            measure("dns", host, sent, received).put("answer_type", type).put("transaction_id", 0x4321),
                        )
                    }
                }
            }
            val evidence =
                JSONObject()
                    .put("nonce", args.getString("awgNonce"))
                    .put("mtu", mtu)
                    .put("cycle", cycle)
                    .put("peer_port", requireNotNull(args.getString("awgPeerPort")).toInt())
                    .put("abi", Build.SUPPORTED_ABIS.first())
                    .put("api", Build.VERSION.SDK_INT)
                    .put("socket_protection", protection.observation())
                    .put("measurements", measurements)
            InstrumentationRegistry.getInstrumentation().sendStatus(
                2,
                Bundle().apply {
                    putString("awgEvidence", evidence.toString())
                },
            )
        } finally {
            try {
                bindings.jniStop(handle)
                worker?.join(5000)
                assertFalse("native shutdown must finish", worker?.isAlive == true)
                bindings.jniDestroy(handle)
            } finally {
                RipDpiAmneziaWgNativeBindings.jniUnregisterVpnProtect(protectionToken)
            }
        }
        assertEquals("native start must exit cleanly", 0, exit.get())
        assertNoActiveVpn(context)
    }

    private fun awaitListener(
        bindings: RipDpiAmneziaWgNativeBindings,
        handle: Long,
        exit: AtomicInteger,
    ): InetSocketAddress {
        val deadline = System.nanoTime() + 15_000_000_000L
        while (System.nanoTime() < deadline) {
            val telemetry = bindings.jniPollTelemetry(handle)?.let(::JSONObject)
            check(exit.get() == -1) {
                "native start exited before readiness: code=${exit.get()} error=${telemetry?.optString("lastError")}"
            }
            if (telemetry?.optString("state") == "running" && !telemetry.isNull("listenerAddress")) {
                val endpoint = telemetry.getString("listenerAddress").split(':')
                assertEquals("127.0.0.1", endpoint[0])
                return InetSocketAddress(endpoint[0], endpoint[1].toInt())
            }
            Thread.sleep(25)
        }
        error("authenticated AWG readiness deadline exceeded")
    }

    private fun assertNoActiveVpn(context: Context) {
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        assertFalse(
            "This native-core host must not establish an Android VPN",
            connectivity
                .getNetworkCapabilities(connectivity.activeNetwork)
                ?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true,
        )
    }

    private fun awaitIpv4Underlay(context: Context) {
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        val endpoint = InetAddress.getByName("10.0.2.2")
        val deadline = System.nanoTime() + 15_000_000_000L
        while (System.nanoTime() < deadline) {
            assertNoActiveVpn(context)
            val network = connectivity.activeNetwork
            val capabilities = connectivity.getNetworkCapabilities(network)
            val routes = network?.let(connectivity::getLinkProperties)?.routes.orEmpty()
            if (capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true &&
                routes.any { it.matches(endpoint) }
            ) {
                return
            }
            Thread.sleep(25)
        }
        error("Android IPv4 underlay is not ready for the owned peer")
    }

    /** Records the actual platform result; it never fabricates protection success. */
    class RecordingVpnService : VpnService() {
        private val attempted = AtomicInteger()
        private val successful = AtomicInteger()

        override fun protect(socket: Int): Boolean {
            attempted.incrementAndGet()
            return super.protect(socket).also { if (it) successful.incrementAndGet() }
        }

        fun observation(): JSONObject {
            assertEquals("one real Android protection call", 1, attempted.get())
            assertEquals("Android must accept socket protection", 1, successful.get())
            return JSONObject().put("attempted", attempted.get()).put("successful", successful.get())
        }
    }

    private fun socks(
        listener: InetSocketAddress,
        command: Int,
        host: String,
        port: Int,
    ): Pair<Socket, Int> {
        val socket = Socket()
        try {
            socket.connect(listener, TimeoutMs)
            socket.soTimeout = TimeoutMs
            val input = DataInputStream(socket.getInputStream())
            val output = socket.getOutputStream()
            output.write(byteArrayOf(5, 1, 0))
            assertEquals(5, input.readUnsignedByte())
            assertEquals(0, input.readUnsignedByte())
            output.write(byteArrayOf(5, command.toByte(), 0) + address(host, port))
            val reply = ByteArray(10)
            input.readFully(reply)
            assertArrayEquals(byteArrayOf(5, 0, 0, 1), reply.copyOf(4))
            val boundPort = ((reply[8].toInt() and 255) shl 8) or (reply[9].toInt() and 255)
            return socket to boundPort
        } catch (failure: Throwable) {
            socket.close()
            throw failure
        }
    }

    private fun address(
        host: String,
        port: Int,
    ): ByteArray =
        byteArrayOf(if (':' in host) 4 else 1) + InetAddress.getByName(host).address +
            byteArrayOf((port shr 8).toByte(), port.toByte())

    private fun udpFrame(
        host: String,
        port: Int,
        payload: ByteArray,
    ) = byteArrayOf(0, 0, 0) + address(host, port) + payload

    private fun key(value: Int) = Base64.encodeToString(ByteArray(32) { value.toByte() }, Base64.NO_WRAP)

    private fun measure(
        protocol: String,
        host: String,
        sent: ByteArray,
        received: ByteArray,
    ) = JSONObject()
        .put("protocol", protocol)
        .put("family", if (':' in host) 6 else 4)
        .put("sent_bytes", sent.size)
        .put("received_bytes", received.size)
        .put("sent_sha256", sha256(sent))
        .put("received_sha256", sha256(received))

    private fun sha256(value: ByteArray) =
        MessageDigest.getInstance("SHA-256").digest(value).joinToString("") {
            "%02x".format(it.toInt() and 255)
        }

    private companion object {
        const val TimeoutMs = 5000
    }
}
