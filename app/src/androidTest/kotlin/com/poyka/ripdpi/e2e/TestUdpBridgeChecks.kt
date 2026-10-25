package com.poyka.ripdpi.e2e

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.SocketException
import java.security.SecureRandom

/** Runs on Android before Hysteria traffic; all sockets stay on loopback. */
internal fun assertUsbDatagramBridgeFlowIsolation() {
    val loopback = InetAddress.getByName("127.0.0.1")
    val tokenBytes = ByteArray(32).also { SecureRandom().nextBytes(it) }
    val token = tokenBytes.joinToString("") { "%02x".format(it) }
    ServerSocket(0, 1, loopback).use { listener ->
        listener.soTimeout = 3_000
        val failure =
            assertThrows(IllegalStateException::class.java) {
                TestUdpBridge(listener.localPort, token).use { bridge ->
                    listener.accept().use { channel ->
                        channel.soTimeout = 3_000
                        val input = DataInputStream(channel.getInputStream())
                        val output = DataOutputStream(channel.getOutputStream())
                        val supplied = ByteArray(32).also(input::readFully)
                        assertArrayEquals(tokenBytes, supplied)
                        DatagramSocket(InetSocketAddress(loopback, 0)).use { first ->
                            DatagramSocket(InetSocketAddress(loopback, 0)).use { second ->
                                first.soTimeout = 3_000
                                second.soTimeout = 3_000
                                verifyConcurrentReplies(first, second, bridge.port, input, output)
                                output.writeBridgeFrame(3, "unknown".toByteArray())
                                try {
                                    assertEquals(-1, input.read())
                                } catch (_: SocketException) {
                                    // Rejecting a header with unread payload can close with TCP RST.
                                }
                            }
                        }
                    }
                }
            }
        assertEquals("Owned USB datagram bridge failed", failure.message)
        assertEquals("Unknown owned USB datagram flow", failure.cause?.message)
    }
}

private fun verifyConcurrentReplies(
    first: DatagramSocket,
    second: DatagramSocket,
    bridgePort: Int,
    input: DataInputStream,
    output: DataOutputStream,
) {
    val destination = InetSocketAddress("127.0.0.1", bridgePort)
    val firstPayload = "first-source".toByteArray()
    val secondPayload = "second-source".toByteArray()
    first.send(DatagramPacket(firstPayload, firstPayload.size, destination))
    second.send(DatagramPacket(secondPayload, secondPayload.size, destination))
    val frames = listOf(input.readBridgeFrame(), input.readBridgeFrame())
    val firstFlow = frames.single { it.second.contentEquals(firstPayload) }.first
    val secondFlow = frames.single { it.second.contentEquals(secondPayload) }.first
    assertNotEquals(firstFlow, secondFlow)
    assertEquals(setOf(1, 2), setOf(firstFlow, secondFlow))
    output.writeBridgeFrame(secondFlow, "second-reply".toByteArray())
    assertEquals("second-reply", second.receiveBridgeReply())
    output.writeBridgeFrame(firstFlow, "first-reply".toByteArray())
    assertEquals("first-reply", first.receiveBridgeReply())
}

private fun DataInputStream.readBridgeFrame(): Pair<Int, ByteArray> {
    assertEquals(1, readUnsignedByte())
    val flowId = readInt()
    val size = readUnsignedShort()
    check(flowId in 1..64 && size in 1..65_507)
    return flowId to ByteArray(size).also(::readFully)
}

private fun DataOutputStream.writeBridgeFrame(
    flowId: Int,
    payload: ByteArray,
) {
    writeByte(1)
    writeInt(flowId)
    writeShort(payload.size)
    write(payload)
    flush()
}

private fun DatagramSocket.receiveBridgeReply(): String {
    val packet = DatagramPacket(ByteArray(128), 128)
    receive(packet)
    return String(packet.data, packet.offset, packet.length, Charsets.UTF_8)
}
