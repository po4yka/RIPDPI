package com.poyka.ripdpi.services

import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.FileDescriptor
import kotlin.concurrent.thread

@OptIn(ExperimentalCoroutinesApi::class)
class VpnProtectSocketServerTest {
    @Test
    fun `handle client session waits for stall gate before writing ack`() {
        val gate = HarnessStallGate()
        val monitor = TestVpnProtectFailureMonitor()
        val server =
            VpnProtectSocketServer(
                socketPath = "/tmp/unused-protect.sock",
                protectFailureMonitor = monitor,
                fdProtector = { true },
                beforeProtectAncillaryFds = gate::stall,
            )
        val session = FakeProtectSocketClientSession()

        val worker = thread(start = true) { server.handleClientSession(session) }

        assertTrue(gate.awaitEntered())
        assertArrayEquals(byteArrayOf(), session.ackBytes())

        gate.release()
        worker.join(5_000L)

        assertArrayEquals(byteArrayOf(0), session.ackBytes())
    }
}

private class FakeProtectSocketClientSession(
    override val ancillaryFileDescriptors: Array<FileDescriptor>? = emptyArray(),
) : ProtectSocketClientSession {
    private val output = ByteArrayOutputStream()

    override fun readHandshake(): Int = 1

    override fun writeAck(success: Boolean) {
        output.write(if (success) 0 else 1)
    }

    fun ackBytes(): ByteArray = output.toByteArray()

    override fun close() = Unit
}
