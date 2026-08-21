package com.poyka.ripdpi.diagnostics

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LogcatSnapshotCollectorTest {
    @Test
    fun `logcat command remains limited to the app pid`() {
        val command = buildLogcatCommand(pid = 73, sinceTimestampMs = 1700000000000L)

        assertTrue(command.contains("--pid=73"))
        assertTrue(command.contains("-T"))
        assertTrue(command.contains("ripdpi:V"))
        assertTrue(!command.contains("*:V"))
    }

    @Test
    fun `capture returns snapshot when output is non blank`() =
        runTest {
            val output = "03-12 10:00:00.000 I/RIPDPI: diagnostics ready\n"
            val collector =
                object : LogcatSnapshotCollector() {
                    override fun readLogcatOutput(sinceTimestampMs: Long?): LogcatReadOutput =
                        output.asLogcatReadOutput(timeBound = sinceTimestampMs != null)
                }

            val snapshot = collector.capture()

            requireNotNull(snapshot)
            assertEquals(output, snapshot.content)
            assertEquals(LogcatSnapshotCollector.AppVisibleSnapshotScope, snapshot.captureScope)
            assertEquals(output.toByteArray(Charsets.UTF_8).size, snapshot.byteCount)
        }

    @Test
    fun `capture with timestamp uses time bound scope`() =
        runTest {
            val output = "03-12 10:00:00.000 I/RIPDPI: diagnostics ready\n"
            val collector =
                object : LogcatSnapshotCollector() {
                    override fun readLogcatOutput(sinceTimestampMs: Long?): LogcatReadOutput =
                        output.asLogcatReadOutput(timeBound = sinceTimestampMs != null)
                }

            val snapshot = collector.capture(sinceTimestampMs = 1700000000000L)

            requireNotNull(snapshot)
            assertEquals(LogcatSnapshotCollector.TimeBoundSnapshotScope, snapshot.captureScope)
        }

    @Test
    fun `capture returns null when output is blank`() =
        runTest {
            val collector =
                object : LogcatSnapshotCollector() {
                    override fun readLogcatOutput(sinceTimestampMs: Long?): LogcatReadOutput =
                        "   ".asLogcatReadOutput(timeBound = sinceTimestampMs != null)
                }

            assertNull(collector.capture())
        }

    @Test
    fun `capture returns null when logcat command fails`() =
        runTest {
            val collector =
                object : LogcatSnapshotCollector() {
                    override fun readLogcatOutput(sinceTimestampMs: Long?): LogcatReadOutput =
                        throw java.io.IOException("logcat unavailable")
                }

            assertNull(collector.capture())
        }

    @Test
    fun `capture enforces utf8 byte budget and reports truncation`() =
        runTest {
            val collector =
                object : LogcatSnapshotCollector() {
                    override fun readLogcatOutput(sinceTimestampMs: Long?): LogcatReadOutput =
                        "ж"
                            .repeat(LogcatSnapshotCollector.MAX_LOGCAT_BYTES)
                            .asLogcatReadOutput(timeBound = sinceTimestampMs != null)
                }

            val snapshot = requireNotNull(collector.capture())

            assertTrue(snapshot.truncated)
            assertTrue(snapshot.byteCount <= LogcatSnapshotCollector.MAX_LOGCAT_BYTES)
            assertEquals(snapshot.byteCount, snapshot.content.toByteArray(Charsets.UTF_8).size)
        }

    @Test
    fun `capture retains newest utf8 tail when output exceeds budget`() =
        runTest {
            val collector =
                object : LogcatSnapshotCollector() {
                    override fun readLogcatOutput(sinceTimestampMs: Long?): LogcatReadOutput =
                        (
                            "oldest-marker\n" +
                                "ж".repeat(LogcatSnapshotCollector.MAX_LOGCAT_BYTES) +
                                "\nnewest-marker"
                        ).asLogcatReadOutput(timeBound = sinceTimestampMs != null)
                }

            val snapshot = requireNotNull(collector.capture())

            assertTrue(snapshot.truncated)
            assertTrue(snapshot.content.contains("newest-marker"))
            assertTrue(!snapshot.content.contains("oldest-marker"))
            assertTrue(snapshot.byteCount <= LogcatSnapshotCollector.MAX_LOGCAT_BYTES)
        }

    @Test
    fun `time bound capture retains complete startup and newest markers across unicode truncation`() =
        runTest {
            val startupMarker = "vpn-startup-complete-marker"
            val newestMarker = "latest-runtime-complete-marker"
            val collector =
                object : LogcatSnapshotCollector() {
                    override fun readLogcatOutput(sinceTimestampMs: Long?): LogcatReadOutput =
                        (
                            "$startupMarker\n" +
                                "ж".repeat(LogcatSnapshotCollector.MAX_LOGCAT_BYTES) +
                                "\n$newestMarker\n"
                        ).asLogcatReadOutput(timeBound = sinceTimestampMs != null)
                }

            val snapshot = requireNotNull(collector.capture(sinceTimestampMs = 1700000000000L))

            assertTrue(snapshot.truncated)
            assertTrue(snapshot.content.contains(startupMarker))
            assertTrue(snapshot.content.contains(newestMarker))
            assertTrue(snapshot.content.contains(LogcatTruncationMarker.trim()))
            assertTrue(snapshot.byteCount <= LogcatSnapshotCollector.MAX_LOGCAT_BYTES)
            assertEquals(snapshot.byteCount, snapshot.content.toByteArray(Charsets.UTF_8).size)
        }

    @Test
    fun `time bound oversized logcat reports observed byte loss with complete retained lines`() =
        runTest {
            val startupLine = "03-12 10:00:00.000 I/ripdpi: startup-complete"
            val newestLine = "03-12 10:04:00.000 I/ripdpi: runtime-complete"
            val source =
                buildString {
                    append(startupLine).append('\n')
                    repeat(LogcatSnapshotCollector.MAX_LOGCAT_BYTES / 16) {
                        append("03-12 10:02:00.000 I/ripdpi: жжжжжжжж\n")
                    }
                    append(newestLine).append('\n')
                }
            val retained =
                listOf(
                    startupLine,
                    LogcatTruncationMarker.trim(),
                    newestLine,
                    "",
                ).joinToString("\n")
            val sourceByteCount = source.toByteArray(Charsets.UTF_8).size.toLong()
            val retainedByteCount = retained.toByteArray(Charsets.UTF_8).size.toLong()
            val collector =
                object : LogcatSnapshotCollector() {
                    override fun readLogcatOutput(sinceTimestampMs: Long?): LogcatReadOutput =
                        LogcatReadOutput(
                            content = retained,
                            sourceByteCount = sourceByteCount,
                            retainedByteCount = retainedByteCount,
                            droppedByteCount = sourceByteCount - retainedByteCount,
                            preCollectionRingLoss = LogcatPreCollectionRingLossStatus.UNKNOWN,
                            truncated = true,
                        )
                }

            val snapshot = requireNotNull(collector.capture(sinceTimestampMs = 1700000000000L))

            assertEquals(sourceByteCount, snapshot.sourceByteCount)
            assertEquals(retainedByteCount, snapshot.retainedByteCount)
            assertEquals(sourceByteCount - retainedByteCount, snapshot.droppedByteCount)
            assertEquals(LogcatPreCollectionRingLossStatus.UNKNOWN, snapshot.preCollectionRingLoss)
            assertTrue(snapshot.truncated)
            assertTrue(snapshot.content.contains(LogcatTruncationMarker.trim()))
            assertEquals(
                listOf(startupLine, LogcatTruncationMarker.trim(), newestLine, ""),
                snapshot.content.split('\n'),
            )
        }

    @Test
    fun `line aligned time bound retention reports retained bounds and observed loss`() {
        val firstLine = "03-12 10:00:00.000 I/ripdpi: startup"
        val droppedLine = "03-12 10:01:00.000 I/ripdpi: жжжжжжжжжжжжжжжж"
        val lastLine = "03-12 10:02:00.000 I/ripdpi: restored"
        val source = "$firstLine\n${droppedLine.repeat(4)}\n$lastLine\n"

        val output =
            readLineAlignedLogcatOutput(
                bytes = source.toByteArray(Charsets.UTF_8),
                retainHead = true,
                maxBytes = 160,
            )

        assertTrue(output.truncated)
        assertEquals(source.toByteArray(Charsets.UTF_8).size.toLong(), output.sourceByteCount)
        assertEquals(output.sourceByteCount - output.droppedByteCount, output.retainedByteCount)
        assertEquals(LogcatPreCollectionRingLossStatus.UNKNOWN, output.preCollectionRingLoss)
        assertEquals("03-12 10:00:00.000", output.earliestRetainedTimestamp)
        assertEquals("03-12 10:02:00.000", output.latestRetainedTimestamp)
        assertTrue(output.content.contains(LogcatTruncationMarker.trim()))
        assertTrue(output.content.contains(firstLine))
        assertTrue(output.content.contains(lastLine))
        assertTrue(!output.content.contains("жжжжжжжжжжжжжжжж"))
        assertTrue(output.content.endsWith('\n'))
    }
}

private fun String.asLogcatReadOutput(timeBound: Boolean): LogcatReadOutput {
    val originalBytes = toByteArray(Charsets.UTF_8)
    val bytes =
        if (timeBound) {
            headAndTailUtf8Bytes(this, LogcatSnapshotCollector.MAX_LOGCAT_BYTES)
        } else {
            tailUtf8Bytes(this, LogcatSnapshotCollector.MAX_LOGCAT_BYTES)
        }
    return LogcatReadOutput(
        content = bytes.toString(Charsets.UTF_8),
        byteCount = bytes.size,
        truncated = originalBytes.size > bytes.size,
    )
}
