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
