package com.poyka.ripdpi.services

import com.poyka.ripdpi.data.FailureReason
import com.poyka.ripdpi.services.testsupport.HarnessStallGate
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.FileDescriptor
import java.io.FileInputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

class VpnProtectSocketServerTest {
    @Test
    fun `handle client session rejects handshake without ancillary fd`() {
        val monitor = RecordingVpnProtectFailureMonitor()
        val server = createServer(monitor = monitor)
        val session = FakeProtectSocketClientSession(ancillaryFileDescriptors = null)

        try {
            server.handleClientSession(session)

            assertArrayEquals(byteArrayOf(1), session.ackBytes())
            assertEquals(1, monitor.reportedEvents.size)
            assertEquals(-1, monitor.reportedEvents.single().fd)
            assertTrue(monitor.reportedEvents.single().reason is FailureReason.NativeError)
            assertEquals(
                "protect request did not include an SCM_RIGHTS file descriptor",
                monitor.reportedEvents.single().detail,
            )
        } finally {
            server.stop()
        }
    }

    @Test
    fun `handle client session waits for stalled read before writing ack`() {
        val gate = HarnessStallGate()
        val server = createServer()
        val session = FakeProtectSocketClientSession(beforeRead = gate::stall)

        try {
            val worker = thread(start = true) { server.handleClientSession(session) }

            assertTrue(gate.awaitEntered())
            assertArrayEquals(byteArrayOf(), session.ackBytes())

            gate.release()
            worker.join(5_000L)

            assertArrayEquals(byteArrayOf(0), session.ackBytes())
        } finally {
            gate.release()
            server.stop()
        }
    }

    @Test
    fun `dispatch client session isolates stalled handler from next client`() {
        val gate = HarnessStallGate()
        val server =
            createServer(
                handlerConcurrency = 2,
                maxPendingSessions = 0,
            )
        val stalledSession = FakeProtectSocketClientSession(beforeRead = gate::stall)
        val fastSession = FakeProtectSocketClientSession()

        try {
            assertTrue(server.dispatchClientSession(stalledSession))
            assertTrue(gate.awaitEntered())

            assertTrue(server.dispatchClientSession(fastSession))
            assertTrue(fastSession.awaitAck())

            assertArrayEquals(byteArrayOf(0), fastSession.ackBytes())
            assertArrayEquals(byteArrayOf(), stalledSession.ackBytes())
        } finally {
            gate.release()
            server.stop()
        }
    }

    @Test
    fun `dispatch client session rejects overload instead of blocking accept loop`() {
        val gate = HarnessStallGate()
        val server =
            createServer(
                handlerConcurrency = 1,
                maxPendingSessions = 0,
            )
        val stalledSession = FakeProtectSocketClientSession(beforeRead = gate::stall)
        val rejectedSession = FakeProtectSocketClientSession()

        try {
            assertTrue(server.dispatchClientSession(stalledSession))
            assertTrue(gate.awaitEntered())

            assertFalse(server.dispatchClientSession(rejectedSession))
            assertTrue(rejectedSession.awaitAck())
            assertArrayEquals(byteArrayOf(1), rejectedSession.ackBytes())
            assertEquals(0, rejectedSession.readCount())
            assertTrue(rejectedSession.awaitClosed())
        } finally {
            gate.release()
            server.stop()
        }
    }

    @Test
    fun `stop closes queued sessions without running pending handler`() {
        val gate = HarnessStallGate()
        val server =
            createServer(
                handlerConcurrency = 1,
                maxPendingSessions = 1,
                handlerJoinTimeoutMs = 100L,
            )
        val stalledSession = FakeProtectSocketClientSession(beforeRead = gate::stall)
        val queuedSession = FakeProtectSocketClientSession()

        try {
            assertTrue(server.dispatchClientSession(stalledSession))
            assertTrue(gate.awaitEntered())
            assertTrue(server.dispatchClientSession(queuedSession))
            assertArrayEquals(byteArrayOf(), queuedSession.ackBytes())

            val stopper = thread(start = true) { server.stop() }

            stopper.join(1_000L)
            assertFalse(stopper.isAlive)
            assertTrue(queuedSession.awaitClosed())
            assertEquals(0, queuedSession.readCount())
            assertArrayEquals(byteArrayOf(), queuedSession.ackBytes())
        } finally {
            gate.release()
            server.stop()
        }
    }

    @Test
    fun `stop returns without waiting indefinitely for stalled handler`() {
        val gate = HarnessStallGate()
        val server =
            createServer(
                handlerConcurrency = 1,
                maxPendingSessions = 0,
                handlerJoinTimeoutMs = 100L,
            )
        val stalledSession = FakeProtectSocketClientSession(beforeRead = gate::stall)

        try {
            assertTrue(server.dispatchClientSession(stalledSession))
            assertTrue(gate.awaitEntered())

            val stopper = thread(start = true) { server.stop() }

            stopper.join(1_000L)
            assertFalse(stopper.isAlive)
            assertTrue(stalledSession.awaitClosed())
            assertArrayEquals(byteArrayOf(), stalledSession.ackBytes())
        } finally {
            gate.release()
            server.stop()
        }
    }

    @Test
    fun `dispatch client session rejects after shutdown without running handler`() {
        val server = createServer()
        val session = FakeProtectSocketClientSession()

        server.stop()

        assertFalse(server.dispatchClientSession(session))
        assertTrue(session.awaitAck())
        assertArrayEquals(byteArrayOf(1), session.ackBytes())
        assertEquals(0, session.readCount())
        assertTrue(session.awaitClosed())
    }

    @Test
    fun `handle client session reports fd extraction failure and writes negative ack`() {
        val monitor = RecordingVpnProtectFailureMonitor()
        val server =
            createServer(
                monitor = monitor,
                fileDescriptorIntExtractor =
                    ProtectSocketFileDescriptorIntExtractor {
                        ProtectSocketFdExtractionResult.Failed(
                            ProtectSocketFdExtractionError.MissingDescriptorField(listOf("descriptor")),
                        )
                    },
            )
        val session =
            FakeProtectSocketClientSession(
                ancillaryFileDescriptors = arrayOf(FileDescriptor()),
            )

        try {
            server.handleClientSession(session)

            assertArrayEquals(byteArrayOf(1), session.ackBytes())
            assertEquals(1, monitor.reportedEvents.size)
            assertEquals(-1, monitor.reportedEvents.single().fd)
            assertTrue(monitor.reportedEvents.single().reason is FailureReason.NativeError)
        } finally {
            server.stop()
        }
    }

    @Test
    fun `handle client session keeps protecting remaining fds after one protect rejection`() {
        val monitor = RecordingVpnProtectFailureMonitor()
        val extractorCalls = AtomicInteger(0)
        val extractedValues = intArrayOf(10, 20, 30)
        val server =
            createServer(
                monitor = monitor,
                // fd 20 is refused; the loop must keep going and still protect fd 30.
                fdProtector = { fdInt -> fdInt != 20 },
                fileDescriptorIntExtractor =
                    ProtectSocketFileDescriptorIntExtractor {
                        ProtectSocketFdExtractionResult.Extracted(
                            extractedValues[extractorCalls.getAndIncrement()],
                        )
                    },
            )
        val session =
            FakeProtectSocketClientSession(
                ancillaryFileDescriptors =
                    arrayOf(FileDescriptor(), FileDescriptor(), FileDescriptor()),
            )

        try {
            server.handleClientSession(session)

            // The per-fd loop did not short-circuit after the fd 20 rejection.
            assertEquals(3, extractorCalls.get())
            // allProtected is monotone-false: a later success (fd 30) does not flip it back.
            assertArrayEquals(byteArrayOf(1), session.ackBytes())
            // Only the rejected fd is reported; the two protected fds are silent.
            assertEquals(1, monitor.reportedEvents.size)
            assertEquals(20, monitor.reportedEvents.single().fd)
            assertTrue(monitor.reportedEvents.single().reason is FailureReason.PermissionLost)
            assertEquals(
                "VpnService.protect() returned false",
                monitor.reportedEvents.single().detail,
            )
        } finally {
            server.stop()
        }
    }

    @Test
    fun `handle client session reports protect rejection with real fd and permission lost reason`() {
        val monitor = RecordingVpnProtectFailureMonitor()
        val server =
            createServer(
                monitor = monitor,
                // protect() returns false (refused) rather than throwing or failing extraction.
                fdProtector = { false },
                fileDescriptorIntExtractor =
                    ProtectSocketFileDescriptorIntExtractor {
                        ProtectSocketFdExtractionResult.Extracted(42)
                    },
            )
        val session =
            FakeProtectSocketClientSession(
                ancillaryFileDescriptors = arrayOf(FileDescriptor()),
            )

        try {
            server.handleClientSession(session)

            assertArrayEquals(byteArrayOf(1), session.ackBytes())
            assertEquals(1, monitor.reportedEvents.size)
            // The Rejected branch reports the REAL extracted fd, not -1 (the extraction-Failed path).
            assertEquals(42, monitor.reportedEvents.single().fd)
            // ...and PermissionLost("VPN"), not NativeError.
            assertEquals(
                FailureReason.PermissionLost("VPN"),
                monitor.reportedEvents.single().reason,
            )
            assertEquals(
                "VpnService.protect() returned false",
                monitor.reportedEvents.single().detail,
            )
        } finally {
            server.stop()
        }
    }

    @Test
    fun `reflective fd extractor returns descriptor value for open file`() {
        val tempFile =
            kotlin.io.path
                .createTempFile()
                .toFile()
        tempFile.writeText("ok")

        FileInputStream(tempFile).use { input ->
            val result = ReflectiveProtectSocketFileDescriptorIntExtractor.extract(input.fd)
            val extracted = result as ProtectSocketFdExtractionResult.Extracted
            assertTrue(extracted.value >= 0)
        }

        tempFile.delete()
    }

    private fun createServer(
        monitor: VpnProtectFailureMonitor = RecordingVpnProtectFailureMonitor(),
        fileDescriptorIntExtractor: ProtectSocketFileDescriptorIntExtractor =
            ProtectSocketFileDescriptorIntExtractor {
                ProtectSocketFdExtractionResult.Extracted(42)
            },
        fdProtector: (Int) -> Boolean = { true },
        handlerConcurrency: Int = 1,
        maxPendingSessions: Int = 0,
        handlerJoinTimeoutMs: Long = 1_000L,
    ): VpnProtectSocketServer =
        VpnProtectSocketServer(
            socketPath = "/tmp/unused-protect.sock",
            protectFailureMonitor = monitor,
            fdProtector = fdProtector,
            fileDescriptorIntExtractor = fileDescriptorIntExtractor,
            handlerConcurrency = handlerConcurrency,
            maxPendingSessions = maxPendingSessions,
            handlerJoinTimeoutMs = handlerJoinTimeoutMs,
        )
}

private class FakeProtectSocketClientSession(
    override val ancillaryFileDescriptors: Array<FileDescriptor>? = arrayOf(FileDescriptor()),
    private val beforeRead: () -> Unit = {},
) : ProtectSocketClientSession {
    private val output = ByteArrayOutputStream()
    private val ackWritten = CountDownLatch(1)
    private val closed = CountDownLatch(1)
    private val readAttempts = AtomicInteger(0)

    override fun readHandshake(): Int {
        readAttempts.incrementAndGet()
        beforeRead()
        return 1
    }

    override fun writeAck(success: Boolean) {
        synchronized(output) {
            output.write(if (success) 0 else 1)
        }
        ackWritten.countDown()
    }

    fun ackBytes(): ByteArray = synchronized(output) { output.toByteArray() }

    fun awaitAck(): Boolean = ackWritten.await(5, TimeUnit.SECONDS)

    fun awaitClosed(): Boolean = closed.await(5, TimeUnit.SECONDS)

    fun readCount(): Int = readAttempts.get()

    override fun close() {
        closed.countDown()
    }
}

private class RecordingVpnProtectFailureMonitor : VpnProtectFailureMonitor {
    val reportedEvents = mutableListOf<VpnProtectFailureEvent>()

    private val eventFlow = kotlinx.coroutines.flow.MutableSharedFlow<VpnProtectFailureEvent>(extraBufferCapacity = 8)

    override val events: kotlinx.coroutines.flow.SharedFlow<VpnProtectFailureEvent> = eventFlow

    override fun report(event: VpnProtectFailureEvent) {
        reportedEvents += event
        eventFlow.tryEmit(event)
    }
}
