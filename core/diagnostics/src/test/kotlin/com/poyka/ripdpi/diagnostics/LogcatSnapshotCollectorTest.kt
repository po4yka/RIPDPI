package com.poyka.ripdpi.diagnostics

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LogcatSnapshotCollectorTest {
    @Test
    fun `capture returns snapshot when output is non blank`() =
        runTest {
            val output = "03-12 10:00:00.000 I/RIPDPI: diagnostics ready\n"
            val collector =
                object : LogcatSnapshotCollector() {
                    override fun readLogcatOutput(sinceTimestampMs: Long?): String = output
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
                    override fun readLogcatOutput(sinceTimestampMs: Long?): String = output
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
                    override fun readLogcatOutput(sinceTimestampMs: Long?): String = "   "
                }

            assertNull(collector.capture())
        }

    @Test
    fun `capture returns null when logcat command fails`() =
        runTest {
            val collector =
                object : LogcatSnapshotCollector() {
                    override fun readLogcatOutput(sinceTimestampMs: Long?): String =
                        throw java.io.IOException("logcat unavailable")
                }

            assertNull(collector.capture())
        }

    @Test
    fun `capture enforces utf8 byte budget and reports truncation`() =
        runTest {
            val collector =
                object : LogcatSnapshotCollector() {
                    override fun readLogcatOutput(sinceTimestampMs: Long?): String =
                        "ж".repeat(LogcatSnapshotCollector.MAX_LOGCAT_BYTES)
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
                    override fun readLogcatOutput(sinceTimestampMs: Long?): String =
                        "oldest-marker\n" +
                            "ж".repeat(LogcatSnapshotCollector.MAX_LOGCAT_BYTES) +
                            "\nnewest-marker"
                }

            val snapshot = requireNotNull(collector.capture())

            assertTrue(snapshot.truncated)
            assertTrue(snapshot.content.contains("newest-marker"))
            assertTrue(!snapshot.content.contains("oldest-marker"))
            assertTrue(snapshot.byteCount <= LogcatSnapshotCollector.MAX_LOGCAT_BYTES)
        }

    @Test
    fun `time bound capture retains startup and newest markers within utf8 budget`() =
        runTest {
            val startupMarker = "1700000000.000 123 456 I ripdpi: diagnostics-startup-marker\n"
            val newestMarker = "1700000999.000 123 456 I ripdpi: diagnostics-newest-marker\n"
            val output =
                startupMarker +
                    "ж".repeat(LogcatSnapshotCollector.MAX_LOGCAT_BYTES) +
                    newestMarker
            val collector =
                object : LogcatSnapshotCollector() {
                    override fun readLogcatOutput(sinceTimestampMs: Long?): String = output
                }

            val snapshot = requireNotNull(collector.capture(sinceTimestampMs = 1700000000000L))

            assertEquals(
                listOf(true, true, true, true, true),
                listOf(
                    snapshot.truncated,
                    snapshot.byteCount <= LogcatSnapshotCollector.MAX_LOGCAT_BYTES,
                    snapshot.content.contains(startupMarker),
                    snapshot.content.contains(newestMarker),
                    snapshot.content == snapshot.content.toByteArray(Charsets.UTF_8).toString(Charsets.UTF_8),
                ),
            )
        }
}
