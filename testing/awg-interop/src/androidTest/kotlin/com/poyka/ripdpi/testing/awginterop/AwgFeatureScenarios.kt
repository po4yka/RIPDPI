package com.poyka.ripdpi.testing.awginterop

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import java.io.Closeable
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException

/** Small application oracles paired with the independent peer's authenticated wire journal. */
internal class AwgFeatureScenarios(
    private val associate: () -> Pair<Socket, Int>,
) {
    fun run(scenario: String): JSONObject {
        val result = JSONObject().put("phases", JSONArray())
        when (scenario) {
            "small-datagrams", "timer-retry-loss" -> {
                smallPair()
            }

            "timer-persistent" -> {
                Thread.sleep(6200)
            }

            "timer-idle" -> {
                smallPair()
                Thread.sleep(3700)
            }

            "timer-rekey" -> {
                smallPair()
                Thread.sleep(2500)
                smallPair()
                Thread.sleep(300)
                smallPair()
            }

            "timer-reject" -> {
                smallPair()
                result.put("expired_probe", expiry())
            }

            "cookies-enabled", "cookies-disabled" -> {
                result.put("phases", cookies(scenario == "cookies-disabled"))
            }

            "negative-transport" -> {
                result.put("phases", negativeTransport())
            }

            else -> {
                error("unsupported feature scenario $scenario")
            }
        }
        return result
    }

    private fun smallPair() {
        Session(associate()).use { it.pair("awg-padding-check".toByteArray()) }
    }

    private fun expiry(): JSONObject =
        Session(associate()).use { session ->
            Thread.sleep(4500)
            val expired = "awg-expired-probe".toByteArray()
            session.sendPair(expired)
            session.timeout(1000)
            val started = System.nanoTime()
            try {
                session.receive()
                error("expired key delivered data while fresh responses were dropped")
            } catch (_: SocketTimeoutException) {
                // Retain this association while the peer holds fresh responses.
            }
            val result = JSONObject().put("no_reply", true).put("wait_ms", (System.nanoTime() - started) / 1_000_000)
            Thread.sleep(3000)
            session.timeout(5000)
            session.receivePair(expired)
            session.pair("awg-recover-probe".toByteArray())
            result
        }

    private fun cookies(disabled: Boolean): JSONArray =
        Session(associate()).use { session ->
            session.send("ARM:cookie")
            val phases =
                if (disabled) {
                    listOf("invalid-mac1", "admission", "exhausted-noise", "challenge-budget", "recovery")
                } else {
                    listOf("invalid-mac1", "admission", "bad-mac2", "valid-mac2", "challenge-budget", "recovery")
                }
            phases.forEach { phase ->
                session.expect("FENCE:$phase")
                session.send("ACK:$phase")
            }
            session.expect("DONE:cookie")
            JSONArray(phases)
        }

    private fun negativeTransport(): JSONArray =
        Session(associate()).use { session ->
            session.send("ARM:transport")
            val phases = listOf("corrupt", "wrong-hp", "foreign-source", "replay", "ambiguous", "malformed-ip")
            phases.forEach { mode ->
                val payload = ByteArray(256) { 'x'.code.toByte() }
                "DATA:$mode:".toByteArray().copyInto(payload)
                if (mode == "replay" || mode == "ambiguous") session.expect(payload)
                // If a rejected packet arrives, or replay delivers twice, this exact-next
                // fence comparison fails before an ACK can release the next phase.
                session.expect("FENCE:$mode")
                session.send("ACK:$mode")
                if (mode in setOf("corrupt", "wrong-hp", "foreign-source")) {
                    session.expect(payload)
                    session.send("CONFIRMED:$mode")
                }
            }
            session.expect("DONE:transport")
            JSONArray(phases)
        }

    private class Session(
        association: Pair<Socket, Int>,
    ) : Closeable {
        private val control = association.first
        private val relayPort = association.second
        private val loopback = InetAddress.getByName("127.0.0.1")
        private val udp =
            try {
                DatagramSocket(null)
            } catch (failure: Throwable) {
                control.close()
                throw failure
            }

        init {
            try {
                require(relayPort in 1..65535)
                udp.bind(InetSocketAddress(loopback, 0))
                udp.soTimeout = 5000
            } catch (failure: Throwable) {
                udp.close()
                control.close()
                throw failure
            }
        }

        fun timeout(milliseconds: Int) {
            udp.soTimeout = milliseconds
        }

        fun send(value: String) = sendFrame(frame(41002, value.toByteArray()))

        fun expect(value: String) = expect(value.toByteArray())

        fun expect(payload: ByteArray) {
            assertArrayEquals("feature payload and source must match", frame(41002, payload), receive())
        }

        fun sendPair(payload: ByteArray) {
            listOf(41002, 41003).forEach { sendFrame(frame(it, payload)) }
        }

        fun receivePair(payload: ByteArray) {
            val pending = listOf(41002, 41003).map { frame(it, payload) }.toMutableList()
            repeat(2) {
                val received = receive()
                val index = pending.indexOfFirst { it.contentEquals(received) }
                assertTrue("each request must return once with its exact source", index >= 0)
                pending.removeAt(index)
            }
        }

        fun pair(payload: ByteArray) {
            sendPair(payload)
            receivePair(payload)
        }

        fun receive(): ByteArray {
            val packet = DatagramPacket(ByteArray(2048), 2048)
            udp.receive(packet)
            assertEquals("unexpected feature relay address", loopback, packet.address)
            assertEquals("unexpected feature relay port", relayPort, packet.port)
            return packet.data.copyOf(packet.length)
        }

        private fun sendFrame(frame: ByteArray) {
            udp.send(DatagramPacket(frame, frame.size, loopback, relayPort))
        }

        private fun frame(
            port: Int,
            payload: ByteArray,
        ) = byteArrayOf(0, 0, 0, 1, 10, 77, 0, 1, (port shr 8).toByte(), port.toByte()) + payload

        override fun close() {
            udp.close()
            control.close()
        }
    }

    companion object {
        val names =
            setOf(
                "small-datagrams",
                "timer-persistent",
                "timer-idle",
                "timer-retry-loss",
                "timer-retry-exhaust",
                "timer-rekey",
                "timer-reject",
                "cookies-enabled",
                "cookies-disabled",
                "negative-transport",
            )

        fun configure(
            config: JSONObject,
            scenario: String,
        ) {
            if (scenario == "small-datagrams") return
            val parameters = config.getJSONObject("amnezia").getJSONObject("v3")
            when (scenario) {
                "timer-persistent" -> {
                    config.put("persistentKeepalive", 1).put("persistentKeepaliveMax", 2)
                }

                "timer-idle" -> {
                    parameters.put("keepaliveTimeout", "1")
                }

                "timer-retry-loss" -> {
                    parameters.put("rekeyTimeout", "1")
                }

                "timer-retry-exhaust" -> {
                    parameters.put("rekeyTimeout", "1").put("maxHandshakeAttempts", "1")
                }

                "timer-reject" -> {
                    parameters
                        .put(
                            "rejectAfterTime",
                            "3-4",
                        ).put("rekeyTimeout", "1")
                        .put("keepaliveTimeout", "1")
                }

                "timer-rekey" -> {
                    parameters.put("rekeyAfterTime", "2").put("rekeyTimeout", "1")
                }

                "cookies-disabled" -> {
                    parameters.put("disableCookies", true)
                }
            }
        }
    }
}
