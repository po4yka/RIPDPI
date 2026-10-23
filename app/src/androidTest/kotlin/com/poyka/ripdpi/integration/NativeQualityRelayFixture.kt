package com.poyka.ripdpi.integration

import java.io.DataInputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/** An owned SIP004 peer forwards opaque authenticated TLS bytes, with an actual slow-link variant. */
internal class NativeQualityRelayFixture(
    private val originPort: Int,
    private val downstreamDelayMillis: Long,
) : AutoCloseable {
    private val loopback = InetAddress.getByName("127.0.0.1")
    private val listener = ServerSocket(0, 16, loopback)
    private val workers = Executors.newFixedThreadPool(12)
    private val sockets = CopyOnWriteArrayList<Socket>()
    val relayedConnections = AtomicInteger()
    val failures = CopyOnWriteArrayList<Throwable>()
    val port: Int get() = listener.localPort
    private val acceptor =
        thread(isDaemon = true, name = "owned-quality-relay") {
            try {
                while (!listener.isClosed) {
                    val socket = listener.accept()
                    sockets.add(socket)
                    workers.submit { forward(socket) }
                }
            } catch (_: java.io.IOException) {
                // Owned listener shutdown.
            }
        }

    private fun forward(socket: Socket) {
        try {
            socket.use { client ->
                client.soTimeout = 15_000
                val input = DataInputStream(client.inputStream)
                val decoder = FixtureAead(ByteArray(32).also(input::readFully))
                val first = decoder.readFrame(input)
                check(first[0].toInt() == 3)
                val nameLength = first[1].toInt() and 0xff
                val host = first.copyOfRange(2, 2 + nameLength).toString(Charsets.UTF_8)
                check(host in setOf("control-a.example.test", "control-b.example.test"))
                val addressEnd = nameLength + 4
                val targetPort =
                    ((first[addressEnd - 2].toInt() and 0xff) shl 8) or
                        (first[addressEnd - 1].toInt() and 0xff)
                check(targetPort == originPort)
                Socket(loopback, originPort).also(sockets::add).use { target ->
                    target.soTimeout = 15_000
                    relayedConnections.incrementAndGet()
                    target.outputStream.write(first, addressEnd, first.size - addressEnd)
                    target.outputStream.flush()
                    val upload =
                        workers.submit {
                            try {
                                while (!target.isClosed) {
                                    target.outputStream.write(decoder.readFrame(input))
                                    target.outputStream.flush()
                                }
                            } catch (_: java.io.IOException) {
                                // HTTP connection close or candidate cancellation.
                            }
                        }
                    try {
                        val salt = ByteArray(32).also { SecureRandom().nextBytes(it) }
                        val encoder = FixtureAead(salt)
                        client.outputStream.write(salt)
                        val bytes = ByteArray(8_192)
                        while (true) {
                            val read = target.inputStream.read(bytes)
                            if (read < 0) break
                            if (downstreamDelayMillis > 0) Thread.sleep(downstreamDelayMillis)
                            client.outputStream.write(encoder.writeFrame(bytes.copyOf(read)))
                            client.outputStream.flush()
                        }
                    } finally {
                        client.close()
                        target.close()
                        upload.cancel(true)
                    }
                }
            }
        } catch (_: java.io.IOException) {
            // Probe cancellation and TLS close are expected transport endings.
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (failure: Throwable) {
            failures.add(failure)
        }
    }

    override fun close() {
        listener.close()
        sockets.forEach { it.close() }
        workers.shutdownNow()
        check(workers.awaitTermination(5, TimeUnit.SECONDS))
        acceptor.join(5_000)
        check(failures.isEmpty()) { "Owned relay framing failed" }
    }
}
